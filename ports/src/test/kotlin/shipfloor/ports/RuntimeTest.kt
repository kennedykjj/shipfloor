package shipfloor.ports

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

object RuntimeFixtures {
    fun aRequest(
        stage: String = "implement",
        worktree: Path = Path.of("/nonexistent/wt/21"),
        model: String = "model-x",
        mcpConfig: Path = Path.of("/nonexistent/mcp.json"),
    ): SessionRequest = SessionRequest(stage, worktree, model, mcpConfig, "prompt")
}

class RuntimeTest {

    @Test
    fun `AC-1 launch returns exit code and transcript path for the request`() {
        val transcript = Path.of("/t/out.jsonl")
        val runtime = object : Runtime {
            override fun launch(request: SessionRequest): SessionResult =
                SessionResult.Exited(7, transcript)
        }

        val result = runtime.launch(RuntimeFixtures.aRequest())

        assertThat(result).isEqualTo(SessionResult.Exited(7, transcript))
    }

    @Test
    fun `AC-2 fake scripted to succeed exits 0 with a transcript path`() {
        val result = FakeRuntime(listOf(SessionScript.Succeed)).launch(RuntimeFixtures.aRequest())

        assertThat(result).isInstanceOf(SessionResult.Exited::class.java)
        val exited = result as SessionResult.Exited
        assertThat(exited.exitCode).isEqualTo(0)
        assertThat(exited.transcriptPath.toString()).isNotBlank()
    }

    @Test
    fun `AC-3 fake scripted to fail exits non-zero with a transcript path`() {
        val result = FakeRuntime(listOf(SessionScript.Fail(3))).launch(RuntimeFixtures.aRequest())

        assertThat(result).isInstanceOf(SessionResult.Exited::class.java)
        val exited = result as SessionResult.Exited
        assertThat(exited.exitCode).isEqualTo(3)
        assertThat(exited.transcriptPath.toString()).isNotBlank()
    }

    @Test
    fun `AC-4 fake scripted to hang returns Hung immediately`() {
        val result = FakeRuntime(listOf(SessionScript.Hang)).launch(RuntimeFixtures.aRequest())

        assertThat(result).isInstanceOf(SessionResult.Hung::class.java)
        assertThat((result as SessionResult.Hung).transcriptPath.toString()).isNotBlank()
    }

    @Test
    fun `AC-5 fake records stage worktree model and mcp config of each launch`() {
        val fake = FakeRuntime(listOf(SessionScript.Succeed, SessionScript.Succeed))
        val first = RuntimeFixtures.aRequest(stage = "spec", model = "m1")
        val second = RuntimeFixtures.aRequest(
            stage = "qa",
            worktree = Path.of("/other/wt"),
            model = "m2",
            mcpConfig = Path.of("/other/mcp.json"),
        )

        fake.launch(first)
        fake.launch(second)

        assertThat(fake.launched).containsExactly(first, second)
    }

    @Test
    fun `AC-6 fake runs without touching the disk and exhausted script throws with call index`() {
        val wt = Files.createTempDirectory("rt-ac6")
        val request = RuntimeFixtures.aRequest(worktree = wt, mcpConfig = wt.resolve("mcp.json"))
        val fake = FakeRuntime(listOf(SessionScript.Succeed))

        val result = fake.launch(request) as SessionResult.Exited

        assertThat(Files.exists(result.transcriptPath)).isFalse()
        assertThat(Files.list(wt).use { it.count() }).isEqualTo(0)

        val error = assertThrows<RuntimeException> { fake.launch(request) }
        assertThat(error.message).contains("1")
        Files.delete(wt)
    }
}
