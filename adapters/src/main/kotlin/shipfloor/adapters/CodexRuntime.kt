package shipfloor.adapters

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult

/** Runs an external command and sends its stdout to a file. No shell is involved. */
interface ProcessRunner {
    fun run(
        command: List<String>,
        workingDir: Path,
        stdoutFile: Path,
        timeout: Duration,
        env: Map<String, String> = emptyMap(),
        envMode: EnvMode = EnvMode.INHERIT,
    ): ProcessOutcome
}

/** What the child inherits from the parent environment. */
enum class EnvMode {
    /** The whole parent environment (default). */
    INHERIT,

    /** Only PATH, HOME, LANG, LC_* and ACLI_* variables; names that look like credentials are dropped. */
    FILTERED,
}

sealed interface ProcessOutcome {
    data class Exited(val code: Int) : ProcessOutcome

    data object TimedOut : ProcessOutcome
}

class InvalidSessionRequestException(message: String) : RuntimeException(message)

@Serializable
private data class McpConfigFile(val mcpServers: Map<String, McpServerEntry> = emptyMap())

@Serializable
private data class McpServerEntry(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Runs one agent session through `codex exec`, applying the daemon's MCP servers as `-c` overrides. */
class CodexRuntime(
    private val processRunner: ProcessRunner,
    private val transcriptDir: Path,
    private val clock: Clock,
    private val timeout: Duration,
) : Runtime {
    override fun launch(request: SessionRequest): SessionResult {
        if (!STAGE_PATTERN.matches(request.stage)) {
            throw InvalidSessionRequestException("invalid stage '${request.stage}': must match ${STAGE_PATTERN.pattern}")
        }
        val servers = readServers(request.mcpConfig)
        servers.keys.forEach { name ->
            if (!SERVER_NAME_PATTERN.matches(name)) {
                throw InvalidSessionRequestException(
                    "invalid MCP server name '$name': must match ${SERVER_NAME_PATTERN.pattern}",
                )
            }
        }

        val env = mutableMapOf<String, String>()
        val envOwners = mutableMapOf<String, String>()
        val command = buildList {
            addAll(listOf("codex", "exec", "--json", "--cd", request.worktree.toString(), "--model", request.model))
            servers.forEach { (name, server) ->
                val url = server.url
                val serverCommand = server.command
                when {
                    url != null && serverCommand == null -> {
                        addAll(listOf("-c", "mcp_servers.$name.url=${tomlString(url)}"))
                        val token = bearerToken(name, server.headers)
                        if (token != null) {
                            requireLoopback(name, url)
                            val variable = "SHIPFLOOR_MCP_TOKEN_" + name.uppercase().replace('-', '_')
                            val other = envOwners.put(variable, name)
                            if (other != null) {
                                throw InvalidSessionRequestException(
                                    "MCP servers '$other' and '$name' map to the same token variable",
                                )
                            }
                            env[variable] = token
                            addAll(listOf("-c", "mcp_servers.$name.bearer_token_env_var=${tomlString(variable)}"))
                        }
                    }
                    serverCommand != null && url == null -> {
                        addAll(listOf("-c", "mcp_servers.$name.command=${tomlString(serverCommand)}"))
                        addAll(listOf("-c", "mcp_servers.$name.args=${tomlArray(server.args)}"))
                    }
                    else -> throw InvalidSessionRequestException(
                        "invalid mcpConfig ${request.mcpConfig}: field mcpServers.$name needs exactly one of command or url",
                    )
                }
            }
        }
        Files.createDirectories(transcriptDir)
        val transcript = transcriptDir.resolve("${request.stage}-${clock.millis()}.jsonl")

        return when (val outcome = processRunner.run(command, request.worktree, transcript, timeout, env)) {
            is ProcessOutcome.Exited -> SessionResult.Exited(outcome.code, transcript)
            ProcessOutcome.TimedOut -> SessionResult.Hung(transcript)
        }
    }

    private fun readServers(mcpConfig: Path): Map<String, McpServerEntry> =
        try {
            Json { ignoreUnknownKeys = true }.decodeFromString<McpConfigFile>(Files.readString(mcpConfig)).mcpServers
        } catch (_: IOException) {
            throw InvalidSessionRequestException("cannot read mcpConfig $mcpConfig")
        } catch (e: SerializationException) {
            throw InvalidSessionRequestException("invalid mcpConfig $mcpConfig: field ${fieldOf(e)}")
        }

    /** Dotted field path from the library's `at path: $.a['b'].c` hint; never the rest of the message. */
    private fun fieldOf(e: SerializationException): String {
        val rawPath = e.message?.let { PATH_PATTERN.find(it)?.groupValues?.get(1) } ?: return "document"
        val segments = SEGMENT_PATTERN.findAll(rawPath).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        return segments.joinToString(".").ifEmpty { "document" }
    }

    private fun bearerToken(name: String, headers: Map<String, String>): String? {
        val value = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
            ?: return null
        if (!value.startsWith("Bearer ") || value.length == "Bearer ".length) {
            throw InvalidSessionRequestException(
                "invalid Authorization header for MCP server '$name': field mcpServers.$name.headers",
            )
        }
        return value.removePrefix("Bearer ")
    }

    private fun requireLoopback(name: String, url: String) {
        val host = URL_PATTERN.matchEntire(url)?.groupValues?.get(1)?.lowercase()
        if (host == null || host !in LOOPBACK_HOSTS) {
            throw InvalidSessionRequestException(
                "MCP server '$name' sends a token: field mcpServers.$name.url must be an http(s) loopback address",
            )
        }
    }

    private fun tomlString(value: String): String {
        val escaped = buildString {
            value.forEach { c ->
                when {
                    c == '\\' -> append("\\\\")
                    c == '"' -> append("\\\"")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c.code < 0x20 || c.code == 0x7f -> append("\\u%04X".format(c.code))
                    else -> append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    private fun tomlArray(values: List<String>): String = values.joinToString(", ", "[", "]") { tomlString(it) }

    private companion object {
        val STAGE_PATTERN = Regex("[a-z][a-z0-9-]*")
        val SERVER_NAME_PATTERN = Regex("[A-Za-z0-9_-]+")
        val PATH_PATTERN = Regex("path: \\$([^\\r\\n]*)")
        val SEGMENT_PATTERN = Regex("\\.([A-Za-z0-9_-]+)|\\['([A-Za-z0-9_-]+)']")
        val URL_PATTERN = Regex("(?i)https?://(\\[[0-9a-f:]+]|[^/:?#@\\[\\]]+)(?::\\d+)?(?:[/?#].*)?", RegexOption.DOT_MATCHES_ALL)
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
    }
}
