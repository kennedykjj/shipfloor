package shipfloor.cli

import java.nio.file.Path

private const val RUNTIME_DIR_VARIABLE = "XDG_RUNTIME_DIR"
private const val SOCKET_DIR_NAME = "shipfloor"
private const val SOCKET_FILE_NAME = "daemon.sock"
private const val TOKEN_FILE_NAME = "owner.token"

/** Where the daemon socket and the owner token live; same convention as the daemon. */
data class SocketLocation(val socket: Path, val token: Path)

/** Resolves the socket and token paths from [env], or null when the runtime directory is not configured. */
fun resolveSocketLocation(env: Map<String, String>): SocketLocation? {
    val runtimeDir = env[RUNTIME_DIR_VARIABLE]?.takeIf { it.isNotBlank() } ?: return null
    val dir = Path.of(runtimeDir).resolve(SOCKET_DIR_NAME)
    return SocketLocation(dir.resolve(SOCKET_FILE_NAME), dir.resolve(TOKEN_FILE_NAME))
}
