package shipfloor.daemon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Opt-in loopback TCP listener for systems without unix sockets. Every connection must open with an
 * `auth` line carrying the transport token; the token authenticates the connection and is never an owner proof.
 */
class TcpTransport private constructor(
    private val server: ServerSocketChannel,
    private val portFile: Path,
    private val token: OwnerProof,
) : AutoCloseable {

    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread.ofPlatform().daemon().name("shipfloor-daemon-tcp-deadline").unstarted(task)
    }

    /** Where the listener is bound; always the IPv4 loopback address. */
    val localAddress: InetSocketAddress
        get() = server.localAddress as InetSocketAddress

    internal fun accept(): SocketChannel = server.accept()

    /**
     * Reads the first line of [client] and checks the token. Any failure (wrong or missing token, another
     * command, a line over [MAX_AUTH_LINE_BYTES], no line within [AUTH_DEADLINE_SECONDS]) sends one fixed
     * error and returns false; the caller closes the connection. Nothing from the line is echoed.
     */
    internal fun authenticate(client: SocketChannel): Boolean {
        val input = Channels.newInputStream(client)
        val output = Channels.newOutputStream(client)
        // Channels.newInputStream may ignore a socket timeout, so the deadline closes the channel instead.
        val deadline = deadlines.schedule({ runCatching { client.close() } }, AUTH_DEADLINE_SECONDS, TimeUnit.SECONDS)
        val accepted = try {
            val line = readFirstLine(input)
            line != null && token.matches(tokenOf(line))
        } catch (e: IOException) {
            false
        } finally {
            deadline.cancel(false)
        }
        try {
            output.write((if (accepted) AUTHENTICATED else REJECTED).toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: IOException) {
            return false
        }
        return accepted
    }

    /** The first line, or null when the stream ends or the line is too long. Unbuffered so later bytes stay for the protocol loop. */
    private fun readFirstLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8)
            if (bytes.size() >= MAX_AUTH_LINE_BYTES) return null
            bytes.write(b)
        }
    }

    /** The token of a well-formed `auth` line; null for anything else. */
    private fun tokenOf(line: String): String? {
        val request = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            return null
        }
        val command = (request["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (command != "auth") return null
        return (request["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    override fun toString(): String = "TcpTransport"

    /** Closes the listener and removes the port file; the token file stays until the next start replaces it. */
    override fun close() {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(portFile) }
        deadlines.shutdownNow()
    }

    companion object {
        const val PORT_FILE_NAME = "tcp.port"
        const val TOKEN_FILE_NAME = "tcp.token"
        private const val MAX_AUTH_LINE_BYTES = 1024
        private const val AUTH_DEADLINE_SECONDS = 5L
        private const val AUTHENTICATED = """{"ok":true,"result":"authenticated"}""" + "\n"
        private const val REJECTED = """{"ok":false,"error":"authentication failed"}""" + "\n"

        /**
         * Binds 127.0.0.1:[port] (0 picks a free port), writes a fresh token and the effective port
         * into [directory].
         */
        fun open(directory: Path, port: Int, random: SecureRandom): TcpTransport {
            val server = try {
                ServerSocketChannel.open().also { it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
            } catch (e: IOException) {
                throw DaemonStartException("cannot listen on 127.0.0.1:$port for SHIPFLOOR_TCP_PORT: ${e.javaClass.simpleName}; choose a free port or 0")
            }
            val portFile = directory.resolve(PORT_FILE_NAME)
            try {
                val token = OwnerProof.create(directory.resolve(TOKEN_FILE_NAME), random)
                val effective = (server.localAddress as InetSocketAddress).port
                Files.writeString(portFile, effective.toString())
                return TcpTransport(server, portFile, token)
            } catch (e: IOException) {
                runCatching { server.close() }
                throw DaemonStartException("cannot write the TCP transport files in $directory: ${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                runCatching { server.close() }
                throw e
            }
        }
    }
}
