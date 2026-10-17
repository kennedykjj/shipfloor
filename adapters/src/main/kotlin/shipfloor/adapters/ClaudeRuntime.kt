package shipfloor.adapters

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult

class RuntimeBinaryNotFoundException(binary: String, stage: String) :
    RuntimeException("claude binary not found: $binary (stage=$stage)")

/** Runs one session as `claude -p` in the worktree; returns facts (transcript, exit code), never interprets them. */
class ClaudeRuntime(
    private val binary: String = "claude",
    private val transcriptDir: Path,
    private val timeout: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val provider: RuntimeProvider = RuntimeProvider.Anthropic,
) : Runtime {

    override fun launch(request: SessionRequest): SessionResult {
        val providerEnvironment = provider.environment()
        require(STAGE_PATTERN.matches(request.stage)) {
            "stage must match [a-z][a-z0-9-]*: '${request.stage}'"
        }
        require(Files.isDirectory(request.worktree)) {
            "worktree does not exist: ${request.worktree}"
        }

        Files.createDirectories(transcriptDir)
        val instant = clock.instant().toString().replace(':', '-')
        val transcript = transcriptDir.resolve("${request.stage}-$instant.jsonl")

        val process = try {
            ProcessBuilder(command(request))
                .directory(request.worktree.toFile())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
                .also {
                    it.environment().keys.removeAll(PROVIDER_VARIABLES - provider.inheritedVariables())
                    it.environment().putAll(providerEnvironment)
                }
                .start()
        } catch (e: IOException) {
            Files.deleteIfExists(transcript)
            throw RuntimeBinaryNotFoundException(binary, request.stage)
        }

        process.outputStream.use { it.write((request.prompt.trimEnd() + "\n").toByteArray()) }

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor()
            return SessionResult.Hung(transcript)
        }
        return SessionResult.Exited(process.exitValue(), transcript)
    }

    private fun command(request: SessionRequest): List<String> = listOf(
        binary,
        "-p",
        "--permission-mode", "acceptEdits",
        "--tools", READ_ONLY_TOOLS,
        "--allowedTools", FACTORY_TOOLS,
        "--strict-mcp-config",
        "--mcp-config", request.mcpConfig.toString(),
        "--model", request.model,
        "--output-format", "stream-json",
        "--verbose",
    )

    private companion object {
        /** Built-in tools of a session: reading only; every write goes through the factory's MCP server. */
        const val READ_ONLY_TOOLS = "Read,Glob,Grep"

        /** Every tool of the MCP server the daemon names `shipfloor` in the session config. */
        const val FACTORY_TOOLS = "mcp__shipfloor"
        val STAGE_PATTERN = Regex("[a-z][a-z0-9-]*")
        val PROVIDER_VARIABLES = setOf(
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_BASE_URL", "AWS_REGION", "AWS_PROFILE",
            "CLOUD_ML_REGION", "ANTHROPIC_VERTEX_PROJECT_ID", "ANTHROPIC_BEDROCK_BASE_URL", "ANTHROPIC_VERTEX_BASE_URL",
        )
    }
}
