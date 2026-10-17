package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult

object ClaudeRuntimeFixtures {
    val fixedClock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)

    /** Writes an executable sh script and returns its path. */
    fun aScript(dir: Path, name: String, body: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    /** A fake claude that records cwd, args and stdin under [rec] and exits with [exitCode]. */
    fun aRecordingClaude(dir: Path, rec: Path, exitCode: Int = 0, extra: String = ""): Path =
        aScript(
            dir,
            "fake-claude",
            """
            echo started > "${rec}/started"
            pwd > "${rec}/cwd"
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > "${rec}/args"
            cat > "${rec}/stdin"
            echo stdout-line
            echo stderr-line >&2
            $extra
            exit $exitCode
            """.trimIndent(),
        )

    fun aRequest(
        stage: String = "implement",
        worktree: Path,
        model: String = "model-x",
        mcpConfig: Path = Path.of("/tmp/mcp.json"),
        prompt: String = "stage skill for $stage",
    ): SessionRequest = SessionRequest(stage, worktree, model, mcpConfig, prompt)

    fun runtime(binary: Path, transcriptDir: Path, timeout: Duration = Duration.ofSeconds(30)) =
        ClaudeRuntime(binary.toString(), transcriptDir, timeout, fixedClock)

    fun runtime(binary: Path, transcriptDir: Path, provider: RuntimeProvider) =
        ClaudeRuntime(binary.toString(), transcriptDir, Duration.ofSeconds(30), fixedClock, provider = provider)

    /** Records the subprocess environment into `env` under [rec]. */
    fun anEnvRecordingClaude(dir: Path, rec: Path): Path =
        aRecordingClaude(dir, rec, extra = "env > \"${rec}/env\"")

    fun recordedEnv(rec: Path): Map<String, String> =
        Files.readAllLines(rec.resolve("env")).filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    val providerVars = listOf(
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_BASE_URL", "AWS_REGION", "AWS_PROFILE",
        "CLOUD_ML_REGION", "ANTHROPIC_VERTEX_PROJECT_ID", "ANTHROPIC_BEDROCK_BASE_URL", "ANTHROPIC_VERTEX_BASE_URL",
    )

    fun fileCount(dir: Path): Long =
        if (Files.exists(dir)) Files.list(dir).use { it.count() } else 0L
}

/** Entry point of a child JVM whose own environment plays the daemon's: provider from env, then one launch. */
object ProviderEnvProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val runtime = ClaudeRuntime(
            args[0], Path.of(args[1]), Duration.ofSeconds(30), ClaudeRuntimeFixtures.fixedClock,
            provider = RuntimeProvider.fromEnv(System.getenv()),
        )
        runtime.launch(SessionRequest("implement", Path.of(args[2]), "model-x", Path.of("/tmp/mcp.json"), "prompt"))
    }
}

class ClaudeRuntimeTest {

    @TempDir
    lateinit var tmp: Path

    private fun dirs(): Triple<Path, Path, Path> {
        val wt = Files.createDirectory(tmp.resolve("wt"))
        val rec = Files.createDirectory(tmp.resolve("rec"))
        val transcripts = tmp.resolve("transcripts")
        return Triple(wt, rec, transcripts)
    }

    @Test
    fun `AC-1 runs claude headless in the worktree with the request prompt on stdin`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        ClaudeRuntimeFixtures.runtime(binary, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))

        assertThat(Path.of(Files.readString(rec.resolve("cwd")).trim()).toRealPath())
            .isEqualTo(wt.toRealPath())
        assertThat(Files.readAllLines(rec.resolve("args"))).contains("-p")
        assertThat(Files.readString(rec.resolve("stdin")).trim()).isEqualTo("stage skill for implement")
    }

    @Test
    fun `AC-2 passes the daemon mcp config strictly and the model`() {
        val (wt, rec, transcripts) = dirs()
        val mcp = tmp.resolve("daemon-mcp.json")
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        ClaudeRuntimeFixtures.runtime(binary, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt, mcpConfig = mcp, model = "model-y"))

        val args = Files.readAllLines(rec.resolve("args"))
        assertThat(args).contains("--strict-mcp-config")
        assertThat(args[args.indexOf("--mcp-config") + 1]).isEqualTo(mcp.toString())
        assertThat(args[args.indexOf("--model") + 1]).isEqualTo("model-y")
    }

    @Test
    fun `AC-2 limits the built-in tools to reading and allows the factory mcp server`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        ClaudeRuntimeFixtures.runtime(binary, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))

        val args = Files.readAllLines(rec.resolve("args"))
        assertThat(args[args.indexOf("--tools") + 1]).isEqualTo("Read,Glob,Grep")
        assertThat(args[args.indexOf("--allowedTools") + 1]).isEqualTo("mcp__shipfloor")
    }

    @Test
    fun `AC-3 returns the transcript path holding stdout and stderr`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        val result = ClaudeRuntimeFixtures.runtime(binary, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))

        val path = result.transcriptPath
        assertThat(path.parent.toRealPath()).isEqualTo(transcripts.toRealPath())
        assertThat(path.fileName.toString()).startsWith("implement-").endsWith(".jsonl").contains("2026")
        assertThat(Files.readString(path)).contains("stdout-line").contains("stderr-line")
    }

    @Test
    fun `AC-4 returns the exit code including non-zero`() {
        val (wt, rec, transcripts) = dirs()
        val ok = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec, exitCode = 0)
        val okResult = ClaudeRuntimeFixtures.runtime(ok, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))
        assertThat(okResult).isInstanceOf(SessionResult.Exited::class.java)
        assertThat((okResult as SessionResult.Exited).exitCode).isEqualTo(0)

        val failDir = Files.createDirectory(tmp.resolve("failbin"))
        val failing = ClaudeRuntimeFixtures.aRecordingClaude(failDir, rec, exitCode = 3)
        val failResult = ClaudeRuntimeFixtures.runtime(failing, transcripts)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))
        assertThat(failResult).isInstanceOf(SessionResult.Exited::class.java)
        assertThat((failResult as SessionResult.Exited).exitCode).isEqualTo(3)
    }

    @Test
    fun `AC-5 missing binary raises a clean error naming binary and stage`() {
        val (wt, _, transcripts) = dirs()
        val missing = tmp.resolve("no-such-claude")

        val error = assertThrows<RuntimeBinaryNotFoundException> {
            ClaudeRuntimeFixtures.runtime(missing, transcripts)
                .launch(ClaudeRuntimeFixtures.aRequest(stage = "spec", worktree = wt))
        }

        assertThat(error.message).isEqualTo("claude binary not found: $missing (stage=spec)")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `AC-6 usable as the Runtime port and returns Exited`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec, exitCode = 0)
        val runtime: Runtime = ClaudeRuntimeFixtures.runtime(binary, transcripts)

        val result = runtime.launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))

        assertThat(result).isInstanceOf(SessionResult.Exited::class.java)
        assertThat(Files.exists(result.transcriptPath)).isTrue()
    }

    @Test
    fun `AC-7 timeout kills the process and its descendants and returns Hung`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aScript(
            tmp,
            "hanging-claude",
            """
            echo partial-output
            sleep 300 &
            echo ${'$'}! > "${rec}/child.pid"
            wait
            """.trimIndent(),
        )

        val result = ClaudeRuntimeFixtures.runtime(binary, transcripts, Duration.ofSeconds(2))
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))

        assertThat(result).isInstanceOf(SessionResult.Hung::class.java)
        assertThat(Files.readString(result.transcriptPath)).contains("partial-output")
        val childPid = Files.readString(rec.resolve("child.pid")).trim().toLong()
        ProcessHandle.of(childPid).ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
        assertThat(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)).isFalse()
    }

    @Test
    fun `AC-8 invalid stage is refused naming the field with no process and no file`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        for (stage in listOf("Bad_Stage", "", "1abc", "a b", "../x")) {
            val error = assertThrows<RuntimeException> {
                ClaudeRuntimeFixtures.runtime(binary, transcripts)
                    .launch(ClaudeRuntimeFixtures.aRequest(stage = stage, worktree = wt))
            }
            assertThat(error).isNotInstanceOf(RuntimeBinaryNotFoundException::class.java)
            assertThat(error.message).contains("stage")
        }

        assertThat(Files.exists(rec.resolve("started"))).isFalse()
        assertThat(ClaudeRuntimeFixtures.fileCount(transcripts)).isEqualTo(0)
    }

    @Test
    fun `AC-8 nonexistent worktree is refused naming the field with no process and no file`() {
        val (_, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.aRecordingClaude(tmp, rec)

        val error = assertThrows<RuntimeException> {
            ClaudeRuntimeFixtures.runtime(binary, transcripts)
                .launch(ClaudeRuntimeFixtures.aRequest(worktree = tmp.resolve("missing-wt")))
        }

        assertThat(error).isNotInstanceOf(RuntimeBinaryNotFoundException::class.java)
        assertThat(error.message).contains("worktree")
        assertThat(Files.exists(rec.resolve("started"))).isFalse()
        assertThat(ClaudeRuntimeFixtures.fileCount(transcripts)).isEqualTo(0)
    }

    // --- runtime provider (#87) ---

    private fun launchWith(provider: RuntimeProvider): Map<String, String> {
        val base = Files.createTempDirectory(tmp, "launch")
        val wt = Files.createDirectory(base.resolve("wt"))
        val rec = Files.createDirectory(base.resolve("rec"))
        val transcripts = base.resolve("transcripts")
        val binary = ClaudeRuntimeFixtures.anEnvRecordingClaude(base, rec)
        ClaudeRuntimeFixtures.runtime(binary, transcripts, provider)
            .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))
        return ClaudeRuntimeFixtures.recordedEnv(rec)
    }

    @Test
    fun `provider AC-1 bedrock gives the subprocess the use flag, region and optional profile`() {
        val withProfile = launchWith(RuntimeProvider.Bedrock("us-east-1", "corp"))
        assertThat(withProfile).containsEntry("CLAUDE_CODE_USE_BEDROCK", "1")
            .containsEntry("AWS_REGION", "us-east-1").containsEntry("AWS_PROFILE", "corp")

        // Without a configured profile AWS_PROFILE is inherited from the daemon (#223), so it is not asserted here.
        val noProfile = launchWith(RuntimeProvider.fromEnv(mapOf("SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "eu-west-1")))
        assertThat(noProfile).containsEntry("CLAUDE_CODE_USE_BEDROCK", "1").containsEntry("AWS_REGION", "eu-west-1")
    }

    @Test
    fun `provider AC-2 vertex gives the subprocess the use flag, region and project`() {
        val env = launchWith(
            RuntimeProvider.fromEnv(
                mapOf("SHIPFLOOR_PROVIDER" to "vertex", "SHIPFLOOR_PROVIDER_REGION" to "us-east5", "SHIPFLOOR_PROVIDER_PROJECT" to "my-proj"),
            ),
        )

        assertThat(env).containsEntry("CLAUDE_CODE_USE_VERTEX", "1")
            .containsEntry("CLOUD_ML_REGION", "us-east5").containsEntry("ANTHROPIC_VERTEX_PROJECT_ID", "my-proj")
            .doesNotContainKey("CLAUDE_CODE_USE_BEDROCK")
    }

    @Test
    fun `provider AC-3 proxy gives the subprocess the base url`() {
        val env = launchWith(
            RuntimeProvider.fromEnv(mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "https://llm.corp.example/v1")),
        )

        assertThat(env).containsEntry("ANTHROPIC_BASE_URL", "https://llm.corp.example/v1")
            .doesNotContainKey("CLAUDE_CODE_USE_BEDROCK").doesNotContainKey("CLAUDE_CODE_USE_VERTEX")
    }

    /** Each call gets its own directory, so one test may probe several daemon environments. */
    private fun probeEnv(daemonEnv: Map<String, String>): Map<String, String> {
        val base = Files.createTempDirectory(tmp, "probe")
        val wt = Files.createDirectory(base.resolve("wt"))
        val rec = Files.createDirectory(base.resolve("rec"))
        val transcripts = base.resolve("transcripts")
        val log = base.resolve("probe.log")
        val binary = ClaudeRuntimeFixtures.anEnvRecordingClaude(base, rec)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "shipfloor.adapters.ProviderEnvProbe",
            binary.toString(), transcripts.toString(), wt.toString(),
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().also { e ->
            e.keys.removeAll(ClaudeRuntimeFixtures.providerVars.toSet())
            e.keys.removeAll { it.startsWith("SHIPFLOOR_PROVIDER") }
            e.putAll(daemonEnv)
        }
        val probe = builder.start()
        assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue()
        assertThat(probe.exitValue()).`as`(Files.readString(log)).isEqualTo(0)
        return ClaudeRuntimeFixtures.recordedEnv(rec)
    }

    @Test
    fun `provider AC-4 without a provider none of the provider variables of the daemon environment reach the subprocess`() {
        val leaked = ClaudeRuntimeFixtures.providerVars.associateWith { "leak-$it" }

        val env = probeEnv(leaked + mapOf("ANTHROPIC_AUTH_TOKEN" to "tok-123"))

        ClaudeRuntimeFixtures.providerVars.forEach { assertThat(env).doesNotContainKey(it) }
        assertThat(env).containsEntry("ANTHROPIC_AUTH_TOKEN", "tok-123")
    }

    @Test
    fun `provider AC-4 a configured provider replaces stale provider variables of the daemon environment`() {
        val leaked = ClaudeRuntimeFixtures.providerVars.associateWith { "leak-$it" }

        val env = probeEnv(leaked + mapOf("SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "us-east-1"))

        assertThat(env).containsEntry("CLAUDE_CODE_USE_BEDROCK", "1").containsEntry("AWS_REGION", "us-east-1")
        listOf("CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_BASE_URL", "CLOUD_ML_REGION", "ANTHROPIC_VERTEX_PROJECT_ID",
            "ANTHROPIC_BEDROCK_BASE_URL", "ANTHROPIC_VERTEX_BASE_URL").forEach { assertThat(env).doesNotContainKey(it) }
        assertThat(env).containsEntry("AWS_PROFILE", "leak-AWS_PROFILE")
    }

    @Test
    fun `provider AC-5 invalid configuration is refused naming the field, never the value`() {
        val cases = listOf(
            mapOf("SHIPFLOOR_PROVIDER" to "gcp-magic-77") to "SHIPFLOOR_PROVIDER",
            mapOf("SHIPFLOOR_PROVIDER" to "bedrock") to "SHIPFLOOR_PROVIDER_REGION",
            mapOf("SHIPFLOOR_PROVIDER" to "vertex", "SHIPFLOOR_PROVIDER_PROJECT" to "p") to "SHIPFLOOR_PROVIDER_REGION",
            mapOf("SHIPFLOOR_PROVIDER" to "vertex", "SHIPFLOOR_PROVIDER_REGION" to "us-east5") to "SHIPFLOOR_PROVIDER_PROJECT",
            mapOf("SHIPFLOOR_PROVIDER" to "proxy") to "SHIPFLOOR_PROVIDER_URL",
            mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "ftp://proxy-secret-host/x") to "SHIPFLOOR_PROVIDER_URL",
            mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "https://user:hunter2@host/x") to "SHIPFLOOR_PROVIDER_URL",
            mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "https://host/x?key=hunter2") to "SHIPFLOOR_PROVIDER_URL",
            mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "https://host/x#hunter2") to "SHIPFLOOR_PROVIDER_URL",
        )

        cases.forEach { (env, field) ->
            val error = assertThrows<InvalidRuntimeProviderException> { RuntimeProvider.fromEnv(env) }
            assertThat(error.message).`as`(env.toString()).contains(field)
            assertThat(error.message).doesNotContain("hunter2").doesNotContain("gcp-magic-77")
                .doesNotContain("proxy-secret-host")
        }
        assertThat(RuntimeProvider.fromEnv(emptyMap())).isEqualTo(RuntimeProvider.Anthropic)
    }

    @Test
    fun `provider AC-5 a runtime built with an invalid provider starts no process and writes no transcript`() {
        val (wt, rec, transcripts) = dirs()
        val binary = ClaudeRuntimeFixtures.anEnvRecordingClaude(tmp, rec)

        listOf(RuntimeProvider.Proxy("ftp://host/x"), RuntimeProvider.Bedrock(""), RuntimeProvider.Vertex("us-east5", "")).forEach { bad ->
            assertThrows<InvalidRuntimeProviderException> {
                ClaudeRuntimeFixtures.runtime(binary, transcripts, bad)
                    .launch(ClaudeRuntimeFixtures.aRequest(worktree = wt))
            }
        }

        assertThat(Files.exists(rec.resolve("started"))).isFalse()
        assertThat(ClaudeRuntimeFixtures.fileCount(transcripts)).isEqualTo(0)
    }

    @Test
    fun `provider AC-7 credentials are not part of the configuration and come from the daemon environment`() {
        val config = mapOf(
            "SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "us-east-1",
            "AWS_SECRET_ACCESS_KEY" to "secret-key-4242", "AWS_ACCESS_KEY_ID" to "akia-4242", "ANTHROPIC_AUTH_TOKEN" to "tok-4242",
        )

        val provider = RuntimeProvider.fromEnv(config)

        assertThat(provider.environment().keys).containsExactlyInAnyOrder("CLAUDE_CODE_USE_BEDROCK", "AWS_REGION")
        assertThat(provider.toString()).doesNotContain("4242")

        val inherited = probeEnv(config)
        assertThat(inherited).containsEntry("AWS_SECRET_ACCESS_KEY", "secret-key-4242")
            .containsEntry("AWS_ACCESS_KEY_ID", "akia-4242").containsEntry("ANTHROPIC_AUTH_TOKEN", "tok-4242")
    }

    @Test
    fun `provider AC-7 a url with embedded credentials is refused without echoing them`() {
        val error = assertThrows<InvalidRuntimeProviderException> {
            RuntimeProvider.Proxy("https://svc:hunter2@proxy.corp/v1").environment()
        }

        assertThat(error.message).contains("SHIPFLOOR_PROVIDER_URL").doesNotContain("hunter2").doesNotContain("svc")
    }

    // --- bedrock without a profile keeps the daemon AWS_PROFILE (#223) ---

    @Test
    fun `223 AC-1 bedrock without a profile passes the daemon AWS_PROFILE to the subprocess`() {
        val env = probeEnv(
            mapOf(
                "SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "sa-east-1", "AWS_PROFILE" to "daemon-profile",
            ),
        )

        assertThat(env).containsEntry("AWS_PROFILE", "daemon-profile")
            .containsEntry("CLAUDE_CODE_USE_BEDROCK", "1").containsEntry("AWS_REGION", "sa-east-1")
    }

    @Test
    fun `223 AC-2 bedrock with a configured profile overrides the daemon AWS_PROFILE`() {
        val env = probeEnv(
            mapOf(
                "SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "sa-east-1",
                "SHIPFLOOR_PROVIDER_PROFILE" to "configured-profile", "AWS_PROFILE" to "daemon-profile",
            ),
        )

        assertThat(env).containsEntry("AWS_PROFILE", "configured-profile")
    }

    @Test
    fun `223 AC-3 without bedrock the daemon AWS_PROFILE does not reach the subprocess`() {
        val daemonProfile = mapOf("AWS_PROFILE" to "daemon-profile")
        val configs = listOf(
            emptyMap(),
            mapOf("SHIPFLOOR_PROVIDER" to "vertex", "SHIPFLOOR_PROVIDER_REGION" to "us-east5", "SHIPFLOOR_PROVIDER_PROJECT" to "my-proj"),
            mapOf("SHIPFLOOR_PROVIDER" to "proxy", "SHIPFLOOR_PROVIDER_URL" to "https://llm.corp.example/v1"),
        )

        configs.forEach { config ->
            val env = probeEnv(daemonProfile + config)

            assertThat(env).`as`(config.toString()).doesNotContainKey("AWS_PROFILE")
        }
    }

    @Test
    fun `223 AC-4 bedrock without a profile still drops the other provider variables of the daemon`() {
        val leaked = mapOf(
            "CLAUDE_CODE_USE_VERTEX" to "1", "ANTHROPIC_BASE_URL" to "https://stale.example", "CLOUD_ML_REGION" to "stale-region",
            "ANTHROPIC_VERTEX_PROJECT_ID" to "stale-project", "ANTHROPIC_BEDROCK_BASE_URL" to "https://stale-bedrock.example",
            "ANTHROPIC_VERTEX_BASE_URL" to "https://stale-vertex.example", "AWS_REGION" to "stale-aws-region",
            "AWS_PROFILE" to "daemon-profile",
        )

        val env = probeEnv(leaked + mapOf("SHIPFLOOR_PROVIDER" to "bedrock", "SHIPFLOOR_PROVIDER_REGION" to "sa-east-1"))

        listOf("CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_BASE_URL", "CLOUD_ML_REGION", "ANTHROPIC_VERTEX_PROJECT_ID",
            "ANTHROPIC_BEDROCK_BASE_URL", "ANTHROPIC_VERTEX_BASE_URL").forEach { assertThat(env).doesNotContainKey(it) }
        assertThat(env).containsEntry("AWS_REGION", "sa-east-1").containsEntry("AWS_PROFILE", "daemon-profile")
    }
}
