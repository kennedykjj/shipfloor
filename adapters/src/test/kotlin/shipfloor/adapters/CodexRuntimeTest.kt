package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult

object CodexRuntimeFixtures {
    val now: Instant = Instant.ofEpochMilli(1_700_000_000_000)
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val timeout: Duration = Duration.ofMinutes(30)

    fun aMcpConfig(dir: Path, servers: String): Path {
        val file = dir.resolve("mcp-${servers.hashCode()}.json")
        Files.writeString(file, """{"mcpServers": {$servers}}""")
        return file
    }

    fun aServer(name: String, command: String, args: String): String =
        """"$name": {"command": $command, "args": $args}"""

    const val TOKEN = "tok-SENTINEL-9f3a7c"

    fun aUrlServer(name: String, url: String, token: String = TOKEN): String =
        """"$name": {"url": "$url", "headers": {"Authorization": "Bearer $token"}}"""

    fun aRequest(
        mcpConfig: Path,
        worktree: Path,
        stage: String = "implement",
        model: String = "gpt-test",
    ) = SessionRequest(stage = stage, worktree = worktree, model = model, mcpConfig = mcpConfig, prompt = "prompt")
}

class RecordingProcessRunner(private val outcome: ProcessOutcome) : ProcessRunner {
    data class Call(
        val command: List<String>,
        val workingDir: Path,
        val stdoutFile: Path,
        val timeout: Duration,
        val env: Map<String, String>,
    )

    val calls = mutableListOf<Call>()

    override fun run(
        command: List<String>,
        workingDir: Path,
        stdoutFile: Path,
        timeout: Duration,
        env: Map<String, String>,
        envMode: EnvMode,
    ): ProcessOutcome {
        calls += Call(command, workingDir, stdoutFile, timeout, env)
        return outcome
    }
}

class CodexRuntimeTest {
    @TempDir
    lateinit var tmp: Path

    private fun transcripts(): Path = tmp.resolve("transcripts").also { Files.createDirectories(it) }

    private fun runtime(runner: ProcessRunner): CodexRuntime =
        CodexRuntime(runner, transcripts(), CodexRuntimeFixtures.clock, CodexRuntimeFixtures.timeout)

    private fun defaultConfig(): Path =
        CodexRuntimeFixtures.aMcpConfig(
            tmp,
            CodexRuntimeFixtures.aServer("daemon", "\"shipfloor-mcp\"", "[\"serve\", \"--stdio\"]"),
        )

    private fun overrides(command: List<String>): List<String> =
        command.withIndex().filter { it.value == "-c" }.map { command[it.index + 1] }

    @Test
    fun `AC-1 runs codex exec in the worktree with the daemon MCP config as overrides`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val worktree = tmp.resolve("wt")

        runtime(runner).launch(CodexRuntimeFixtures.aRequest(defaultConfig(), worktree))

        val call = runner.calls.single()
        assertThat(call.command.take(3)).containsExactly("codex", "exec", "--json")
        assertThat(call.command).containsSubsequence("--cd", worktree.toString())
        assertThat(call.command).containsSubsequence("--model", "gpt-test")
        assertThat(call.workingDir).isEqualTo(worktree)
        assertThat(call.timeout).isEqualTo(CodexRuntimeFixtures.timeout)
        assertThat(overrides(call.command)).containsExactlyInAnyOrder(
            "mcp_servers.daemon.command=\"shipfloor-mcp\"",
            "mcp_servers.daemon.args=[\"serve\", \"--stdio\"]",
        )
    }

    private val token = CodexRuntimeFixtures.TOKEN
    private val loopbackUrl = "http://127.0.0.1:8080/mcp"

    private fun urlConfig(vararg servers: String): Path =
        CodexRuntimeFixtures.aMcpConfig(tmp, servers.joinToString(", "))

    private fun launchUrl(runner: ProcessRunner, config: Path) =
        runtime(runner).launch(CodexRuntimeFixtures.aRequest(config, tmp.resolve("wt")))

    @Test
    fun `AC-1 url server applies url and bearer env var name, token goes in env`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = urlConfig(CodexRuntimeFixtures.aUrlServer("my-daemon", loopbackUrl))

        launchUrl(runner, config)

        val call = runner.calls.single()
        assertThat(overrides(call.command)).containsExactlyInAnyOrder(
            "mcp_servers.my-daemon.url=\"$loopbackUrl\"",
            "mcp_servers.my-daemon.bearer_token_env_var=\"SHIPFLOOR_MCP_TOKEN_MY_DAEMON\"",
        )
        assertThat(call.env).containsExactly(entry("SHIPFLOOR_MCP_TOKEN_MY_DAEMON", token))
    }

    @Test
    fun `AC-1 url server on ipv6 and localhost loopback is accepted`() {
        listOf("http://[::1]:8080/mcp", "https://localhost:9/mcp").forEach { url ->
            val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))

            launchUrl(runner, urlConfig(CodexRuntimeFixtures.aUrlServer("daemon", url)))

            assertThat(overrides(runner.calls.single().command)).contains("mcp_servers.daemon.url=\"$url\"")
        }
    }

    @Test
    fun `AC-2 no command element contains the token or the Authorization value`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = urlConfig(CodexRuntimeFixtures.aUrlServer("daemon", loopbackUrl))

        launchUrl(runner, config)

        val call = runner.calls.single()
        assertThat(call.command).noneMatch { it.contains(token) || it.contains("Bearer") }
        assertThat(call.env.values).contains(token)
    }

    @Test
    fun `AC-3 unreadable mcpConfig error cites the path and not the io message`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val dir = Files.createDirectories(tmp.resolve("not-a-file"))

        assertThatThrownBy { launchUrl(runner, dir) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining(dir.toString())
            .hasNoCause()
            .message().doesNotContain("Is a directory").doesNotContain("${dir}:")
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `AC-4 invalid mcpConfig error cites path and field without content or library message`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = CodexRuntimeFixtures.aMcpConfig(
            tmp,
            """"daemon": {"url": 123, "headers": {"Authorization": "Bearer $token"}}""",
        )

        assertThatThrownBy { launchUrl(runner, config) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining(config.toString())
            .hasMessageContaining("mcpServers.daemon.url")
            .hasNoCause()
            .message().doesNotContain(token).doesNotContain("Unexpected")
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `AC-4 malformed json error does not repeat the file content`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = tmp.resolve("broken.json")
        Files.writeString(config, """{"mcpServers": {"daemon": {"headers": {"Authorization": "Bearer $token""")

        assertThatThrownBy { launchUrl(runner, config) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining(config.toString())
            .hasNoCause()
            .message().doesNotContain(token)
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `AC-5 command server gets no extra env and unchanged overrides`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))

        launchUrl(runner, defaultConfig())

        val call = runner.calls.single()
        assertThat(call.env).isEmpty()
        assertThat(overrides(call.command)).containsExactlyInAnyOrder(
            "mcp_servers.daemon.command=\"shipfloor-mcp\"",
            "mcp_servers.daemon.args=[\"serve\", \"--stdio\"]",
        )
    }

    @Test
    fun `AC-1 security variable collision between a-b and a_b fails naming only the servers`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = urlConfig(
            CodexRuntimeFixtures.aUrlServer("a-b", loopbackUrl),
            CodexRuntimeFixtures.aUrlServer("a_b", loopbackUrl),
        )

        assertThatThrownBy { launchUrl(runner, config) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("a-b")
            .hasMessageContaining("a_b")
            .message().doesNotContain(token)
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `AC-1 security url server with invalid name fails without leaking the token`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = urlConfig(CodexRuntimeFixtures.aUrlServer("bad.name=x", loopbackUrl))

        assertThatThrownBy { launchUrl(runner, config) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("bad.name=x")
            .message().doesNotContain(token)
        assertThat(runner.calls).isEmpty()
    }

    @Test
    fun `AC-2 security non-loopback or non-http url with a token fails without leaking it`() {
        listOf("http://evil.example.com/mcp", "https://10.0.0.5/mcp", "ftp://127.0.0.1/mcp").forEach { url ->
            val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))

            assertThatThrownBy { launchUrl(runner, urlConfig(CodexRuntimeFixtures.aUrlServer("daemon", url))) }
                .isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining("daemon")
                .hasMessageContaining("mcpServers.daemon.url")
                .message().doesNotContain(token).doesNotContain("is required")
            assertThat(runner.calls).isEmpty()
        }
        val control = RecordingProcessRunner(ProcessOutcome.Exited(0))
        launchUrl(control, urlConfig(CodexRuntimeFixtures.aUrlServer("daemon", loopbackUrl)))
        assertThat(control.calls).hasSize(1)
    }

    @Test
    fun `AC-2 security url with quotes and backslashes is escaped in the override`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = urlConfig(CodexRuntimeFixtures.aUrlServer("daemon", "http://127.0.0.1/a\\\"b\\\\c"))

        launchUrl(runner, config)

        assertThat(overrides(runner.calls.single().command))
            .contains("mcp_servers.daemon.url=\"http://127.0.0.1/a\\\"b\\\\c\"")
    }

    @Test
    fun `AC-2 returns transcript path and exit code on normal end`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))

        val result = runtime(runner).launch(CodexRuntimeFixtures.aRequest(defaultConfig(), tmp.resolve("wt")))

        val expected = transcripts().resolve("implement-1700000000000.jsonl")
        assertThat(result).isEqualTo(SessionResult.Exited(0, expected))
        assertThat(runner.calls.single().stdoutFile).isEqualTo(expected)
    }

    @Test
    fun `AC-3 non-zero exit is returned with the transcript path`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(3))

        val result = runtime(runner).launch(CodexRuntimeFixtures.aRequest(defaultConfig(), tmp.resolve("wt")))

        assertThat(result).isEqualTo(
            SessionResult.Exited(3, transcripts().resolve("implement-1700000000000.jsonl")),
        )
    }

    @Test
    fun `AC-3 timeout is reported as Hung with the transcript path`() {
        val runner = RecordingProcessRunner(ProcessOutcome.TimedOut)

        val result = runtime(runner).launch(CodexRuntimeFixtures.aRequest(defaultConfig(), tmp.resolve("wt")))

        assertThat(result).isEqualTo(
            SessionResult.Hung(transcripts().resolve("implement-1700000000000.jsonl")),
        )
    }

    @Test
    fun `AC-4 is usable wherever the Runtime port is expected`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val port: Runtime = runtime(runner)

        val result = port.launch(CodexRuntimeFixtures.aRequest(defaultConfig(), tmp.resolve("wt")))

        assertThat(result).isInstanceOf(SessionResult.Exited::class.java)
        assertThat(runner.calls).hasSize(1)
    }

    @Test
    fun `AC-5 refuses a server name outside the allowed charset`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = CodexRuntimeFixtures.aMcpConfig(
            tmp,
            CodexRuntimeFixtures.aServer("bad.name=x", "\"cmd\"", "[]"),
        )

        assertThatThrownBy { runtime(runner).launch(CodexRuntimeFixtures.aRequest(config, tmp.resolve("wt"))) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("bad.name=x")
        assertThat(runner.calls).isEmpty()
        assertThat(Files.list(transcripts()).use { it.count() }).isZero()
    }

    @Test
    fun `AC-5 refuses a stage outside the allowed charset`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val request = CodexRuntimeFixtures.aRequest(defaultConfig(), tmp.resolve("wt"), stage = "../evil")

        assertThatThrownBy { runtime(runner).launch(request) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("stage")
        assertThat(runner.calls).isEmpty()
        assertThat(Files.list(transcripts()).use { it.count() }).isZero()
    }

    @Test
    fun `AC-5 encodes quotes and backslashes in command and args as TOML text`() {
        val runner = RecordingProcessRunner(ProcessOutcome.Exited(0))
        val config = CodexRuntimeFixtures.aMcpConfig(
            tmp,
            CodexRuntimeFixtures.aServer("daemon", "\"a\\\"b\\\\c\"", "[\"x\\\"y\", \"p\\\\q\"]"),
        )

        runtime(runner).launch(CodexRuntimeFixtures.aRequest(config, tmp.resolve("wt")))

        assertThat(overrides(runner.calls.single().command)).containsExactlyInAnyOrder(
            "mcp_servers.daemon.command=\"a\\\"b\\\\c\"",
            "mcp_servers.daemon.args=[\"x\\\"y\", \"p\\\\q\"]",
        )
    }
}
