package shipfloor.cli

import java.io.BufferedReader
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Outcome of one request to the daemon. */
sealed interface DaemonResult {
    data class Ok(val result: JsonElement, val ignored: JsonElement? = null) : DaemonResult
    data class DaemonError(val message: String) : DaemonResult
    data object Unavailable : DaemonResult
}

/** How an event stream ended. */
sealed interface StreamEnd {
    /** The consumer asked to stop. */
    data object Stopped : StreamEnd
    data class Failed(val message: String) : StreamEnd
    data object Closed : StreamEnd
    data object Unavailable : StreamEnd
}

/** Client of the daemon's line-per-message JSON protocol over a Unix socket. */
class DaemonClient(private val socket: Path) {
    /** Sends [request] on its own connection and returns the single reply. */
    fun request(request: JsonObject): DaemonResult =
        connect { reader, send ->
            send(request)
            reader.readLine()?.let(::parseReply) ?: DaemonResult.DaemonError("daemon closed the connection without a reply")
        } ?: DaemonResult.Unavailable

    /**
     * Subscribes to events; [onEvent] receives each event line and returns whether to keep reading.
     * The acknowledgement line is not passed on.
     */
    fun stream(request: JsonObject, onEvent: (String) -> Boolean): StreamResult =
        connect<StreamResult> { reader, send ->
            send(request)
            val ack = reader.readLine()?.let(::parseReply)
                ?: return@connect StreamResult.Ended(StreamEnd.Closed)
            if (ack is DaemonResult.DaemonError) return@connect StreamResult.Rejected(ack.message)
            var line = reader.readLine()
            while (line != null) {
                val error = errorOf(line)
                if (error != null) return@connect StreamResult.Ended(StreamEnd.Failed(error))
                if (!onEvent(line)) return@connect StreamResult.Ended(StreamEnd.Stopped)
                line = reader.readLine()
            }
            StreamResult.Ended(StreamEnd.Closed)
        } ?:StreamResult.Ended(StreamEnd.Unavailable)

    private fun <T> connect(block: (BufferedReader, (JsonObject) -> Unit) -> T): T? {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX).also {
                try {
                    it.connect(UnixDomainSocketAddress.of(socket))
                } catch (e: IOException) {
                    it.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            return null
        }
        return channel.use {
            val output = Channels.newOutputStream(it)
            val reader = Channels.newInputStream(it).bufferedReader()
            block(reader) { message ->
                output.write((message.toString() + "\n").toByteArray())
                output.flush()
            }
        }
    }

    private fun parseReply(line: String): DaemonResult {
        val obj = parseObject(line) ?: return DaemonResult.DaemonError("daemon sent an unreadable reply")
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == true) return DaemonResult.Ok(obj["result"] ?: obj["text"] ?: JsonPrimitive(null as String?), obj["ignored"])
        val message = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "daemon refused the request"
        return DaemonResult.DaemonError(message)
    }

    private fun errorOf(line: String): String? =
        parseObject(line)?.takeIf { "event" !in it }?.let { (it["error"] as? JsonPrimitive)?.contentOrNull ?: "unexpected stream message" }

    private fun parseObject(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
}

/** Result of opening a stream: refused up front, or ended after it was open. */
sealed interface StreamResult {
    data class Rejected(val message: String) : StreamResult
    data class Ended(val end: StreamEnd) : StreamResult
}
