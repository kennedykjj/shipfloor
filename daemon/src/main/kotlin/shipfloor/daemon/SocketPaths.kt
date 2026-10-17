package shipfloor.daemon

import java.nio.file.Path

/** The daemon cannot start, for instance because the runtime directory is not configured. */
class DaemonStartException(message: String) : RuntimeException(message)

private const val RUNTIME_DIR_VARIABLE = "XDG_RUNTIME_DIR"
private const val SOCKET_DIR_NAME = "shipfloor"
private const val SOCKET_FILE_NAME = "daemon.sock"

/** Resolves the daemon socket path under the user's runtime directory. */
fun resolveSocketPath(env: Map<String, String>): Path {
    val runtimeDir = env[RUNTIME_DIR_VARIABLE]?.takeIf { it.isNotBlank() }
        ?: throw DaemonStartException("$RUNTIME_DIR_VARIABLE is not set; the daemon needs it to place its socket")
    return Path.of(runtimeDir).resolve(SOCKET_DIR_NAME).resolve(SOCKET_FILE_NAME)
}
