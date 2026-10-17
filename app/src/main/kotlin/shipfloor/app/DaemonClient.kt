package shipfloor.app

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sends `{"cmd":"card.detail","id":...}` to the daemon's socket and returns the reply line. */
class SocketCardDetailSource(private val socket: Path, private val timeoutMillis: Long = 5_000) : CardDetailSource {
    override fun fetch(id: String): String {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        val request = buildJsonObject {
            put("cmd", "card.detail")
            put("id", id)
        }.toString() + "\n"
        // A blocking read has no timeout of its own: closing the channel is what ends a daemon that never answers.
        val reply = CompletableFuture.supplyAsync {
            try {
                Channels.newOutputStream(channel).apply {
                    write(request.toByteArray())
                    flush()
                }
                Channels.newInputStream(channel).bufferedReader().readLine()
            } catch (e: IOException) {
                null
            }
        }
        try {
            return reply.get(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw IOException("daemon closed the connection")
        } catch (e: TimeoutException) {
            throw IOException("daemon did not answer card.detail in time", e)
        } catch (e: ExecutionException) {
            throw IOException("card.detail request failed", e)
        } finally {
            channel.close()
        }
    }
}
