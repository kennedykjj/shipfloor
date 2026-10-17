package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProcessRunnerImplTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `filter keeps the allowed names and drops credential-like ones`() {
        val source = mapOf(
            "PATH" to "/bin",
            "HOME" to "/home/x",
            "LANG" to "C",
            "LC_ALL" to "C",
            "ACLI_SITE" to "s",
            "ACLI_API_TOKEN" to "t",
            "GITHUB_TOKEN" to "t",
            "DB_PASSWORD" to "p",
            "AWS_SECRET" to "s",
            "SSH_KEY" to "k",
            "EDITOR" to "vi",
        )

        val kept = ProcessRunnerImpl.filterEnvironment(source)

        assertThat(kept.keys).containsExactlyInAnyOrder("PATH", "HOME", "LANG", "LC_ALL", "ACLI_SITE")
    }

    @Test
    fun `filtered mode hides inherited variables from the child but keeps explicit ones`() {
        val out = dir.resolve("out.txt")

        val outcome = ProcessRunnerImpl().run(
            command = listOf("/usr/bin/env"),
            workingDir = dir,
            stdoutFile = out,
            timeout = Duration.ofSeconds(10),
            env = mapOf("EXPLICIT_VALUE" to "yes"),
            envMode = EnvMode.FILTERED,
        )

        val names = Files.readAllLines(out).map { it.substringBefore('=') }
        assertThat(outcome).isEqualTo(ProcessOutcome.Exited(0))
        assertThat(names).contains("EXPLICIT_VALUE")
        assertThat(names).doesNotContain("USER", "SHELL", "TERM")
    }
}
