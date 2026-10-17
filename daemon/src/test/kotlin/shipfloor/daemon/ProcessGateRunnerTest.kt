package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProcessGateRunnerTest {
    @TempDir
    lateinit var dir: Path

    private val allowed = Regex("PATH|HOME|JAVA_HOME|LANG|LC_.*|GRADLE_.*")

    @Test
    fun `AC-1 the minimal environment keeps only PATH, HOME, JAVA_HOME, LANG, LC_ and GRADLE_ variables`() {
        val source = mapOf(
            "PATH" to "/usr/bin", "HOME" to "/home/dev", "JAVA_HOME" to "/jdk", "LANG" to "pt_BR.UTF-8",
            "LC_ALL" to "C.UTF-8", "LC_CTYPE" to "UTF-8", "GRADLE_USER_HOME" to "/g", "GRADLE_OPTS" to "-Xmx1g",
            "USER" to "dev", "SHELL" to "/bin/bash", "LANGUAGE" to "pt", "MY_GRADLE_X" to "1", "XLC_FOO" to "1",
        )

        assertThat(minimalEnvironment(source)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "PATH" to "/usr/bin", "HOME" to "/home/dev", "JAVA_HOME" to "/jdk", "LANG" to "pt_BR.UTF-8",
                "LC_ALL" to "C.UTF-8", "LC_CTYPE" to "UTF-8", "GRADLE_USER_HOME" to "/g", "GRADLE_OPTS" to "-Xmx1g",
            ),
        )
    }

    @Test
    fun `AC-2 token and secret variables of the daemon never reach the child`() {
        val source = mapOf(
            "PATH" to "/usr/bin", "GITHUB_TOKEN" to "ghp_secret", "ANTHROPIC_API_KEY" to "sk-secret",
            "AWS_SECRET_ACCESS_KEY" to "aws", "SHIPFLOOR_MCP_TOKEN" to "t",
        )

        val environment = minimalEnvironment(source)

        assertThat(environment.keys).containsExactly("PATH")
        assertThat(environment.values).noneMatch { it.contains("secret") }
    }

    @Test
    fun `AC-2 a real child process sees no variable outside the minimal set`() {
        val output = dir.resolve("env.out")

        val run = ProcessGateRunner().run(GateSpec("env", listOf("env"), dir, Duration.ofSeconds(30)), output)

        assertThat(run.exitCode).isEqualTo(0)
        val keys = Files.readAllLines(output).filter { it.contains("=") }.map { it.substringBefore("=") }
        assertThat(keys).isNotEmpty()
        assertThat(keys).allMatch { allowed.matches(it) }
    }

    @Test
    fun `AC-3 a gate with PATH, HOME, JAVA_HOME and LANG still runs and sees them`() {
        val output = dir.resolve("run.out")
        val spec = GateSpec("env", listOf("env"), dir, Duration.ofSeconds(30))

        val run = ProcessGateRunner().run(spec, output)

        assertThat(run.exitCode).isEqualTo(0)
        assertThat(run.timedOut).isFalse()
        val seen = Files.readAllLines(output).filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }
        listOf("PATH", "HOME", "JAVA_HOME", "LANG").forEach { name ->
            System.getenv(name)?.let { assertThat(seen[name]).`as`(name).isEqualTo(it) }
        }
        assertThat(seen).containsKey("PATH")
    }
}
