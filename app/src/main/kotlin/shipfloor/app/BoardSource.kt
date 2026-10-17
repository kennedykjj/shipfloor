package shipfloor.app

import java.io.BufferedReader
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import shipfloor.core.Column
import shipfloor.core.EventType

data class BoardEvent(val seq: Long, val cardId: String, val type: EventType, val payload: Map<String, String>)

/** What the board needs from the daemon. Read only. */
interface BoardSource {
    @Throws(IOException::class)
    fun snapshot(): List<BoardCard>

    @Throws(IOException::class)
    fun fetchCard(id: String): BoardCard?

    /** Blocks delivering events until the connection drops, then throws [IOException]. */
    @Throws(IOException::class)
    fun subscribe(after: Long?, onEvent: (BoardEvent) -> Unit)
}

/** Talks to the daemon's Unix socket; one connection per call, like [SocketDaemonProbe]. */
class SocketBoardSource(private val socket: Path) : BoardSource {
    override fun snapshot(): List<BoardCard> =
        request("""{"cmd":"card.list"}""") { reader ->
            val reply = parseReply(reader.readLine())
            val rows = reply["result"] as? JsonArray ?: throw IOException("card.list: unexpected reply")
            rows.mapNotNull { parseCard(it) }
        }

    override fun fetchCard(id: String): BoardCard? =
        request("""{"cmd":"card.show","id":${JsonPrimitive(id)}}""") { reader ->
            val reply = parseReply(reader.readLine())
            if (reply["ok"]?.jsonPrimitiveOrNull()?.contentOrNull != "true") null else parseCard(reply["result"])
        }

    override fun subscribe(after: Long?, onEvent: (BoardEvent) -> Unit) {
        val command = if (after == null) """{"cmd":"events.subscribe"}""" else """{"cmd":"events.subscribe","after":$after}"""
        request(command) { reader ->
            val ack = parseReply(reader.readLine())
            if (ack["ok"]?.jsonPrimitiveOrNull()?.contentOrNull != "true") throw IOException("events.subscribe refused")
            while (true) {
                val line = reader.readLine() ?: throw IOException("event stream closed")
                val obj = parseReply(line)
                if (obj["error"] != null) throw IOException("event stream ended")
                parseEvent(obj["event"])?.let(onEvent)
            }
        }
    }

    private fun <T> request(command: String, read: (BufferedReader) -> T): T {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
            Channels.newOutputStream(channel).apply {
                write((command + "\n").toByteArray())
                flush()
            }
            return read(Channels.newInputStream(channel).bufferedReader())
        } finally {
            channel.close()
        }
    }

    private fun parseReply(line: String?): JsonObject {
        if (line == null) throw IOException("connection closed")
        return try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("unreadable reply")
        }
    }

    private fun parseCard(element: JsonElement?): BoardCard? = parseBoardCard(element)

    private fun parseEvent(element: JsonElement?): BoardEvent? {
        val obj = element as? JsonObject ?: return null
        val seq = obj["seq"]?.jsonPrimitiveOrNull()?.longOrNull ?: return null
        val card = obj["card"]?.jsonPrimitiveOrNull()?.contentOrNull ?: return null
        val type = obj["type"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { name -> EventType.entries.firstOrNull { it.name == name } }
            ?: return null
        val payload = (obj["payload"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> value.jsonPrimitiveOrNull()?.contentOrNull?.let { key to it } }
            .toMap()
        return BoardEvent(seq, card, type, payload)
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}

private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/** Reads one `card.list` row, tolerating a daemon that does not send the newer fields or sends unreadable ones. */
fun parseBoardCard(element: JsonElement?): BoardCard? {
    val obj = element as? JsonObject ?: return null
    val id = obj["id"]?.primitiveOrNull()?.contentOrNull ?: return null
    val title = obj["title"]?.primitiveOrNull()?.contentOrNull ?: return null
    val column = obj["column"]?.primitiveOrNull()?.contentOrNull?.let { name -> Column.entries.firstOrNull { it.name == name } }
        ?: return null
    val blocked = obj["blocked"]?.primitiveOrNull()?.booleanOrNull ?: false
    val enteredAt = obj["enteredAt"]?.primitiveOrNull()?.contentOrNull?.let { text ->
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }
    return BoardCard(id, title, column, blocked, enteredAt, parseCardCost(obj["cost"]))
}

private fun parseCardCost(element: JsonElement?): BoardCost? {
    val obj = element as? JsonObject ?: return null
    val status = obj["status"]?.primitiveOrNull()?.contentOrNull?.let { name -> CostStatus.entries.firstOrNull { it.name == name } }
        ?: return null
    val usd = obj["costUsd"]?.primitiveOrNull()?.contentOrNull?.toBigDecimalOrNull()
    return BoardCost(status, usd)
}
