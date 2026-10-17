package shipfloor.app

import java.io.IOException
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import shipfloor.core.Column
import shipfloor.core.ColumnTime
import shipfloor.core.ColumnTimeKind

/** Opens the daemon's socket, sends one JSON line and hands over the channel. Replies are never logged. */
private fun connect(socket: Path, request: JsonObject): SocketChannel {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try {
        channel.connect(UnixDomainSocketAddress.of(socket))
        Channels.newOutputStream(channel).apply {
            write((request.toString() + "\n").toByteArray())
            flush()
        }
    } catch (e: IOException) {
        channel.close()
        throw e
    }
    return channel
}

private fun parseObject(line: String?): JsonObject {
    if (line == null) throw IOException("daemon closed the connection without a reply")
    val parsed = try {
        Json.parseToJsonElement(line)
    } catch (e: IllegalArgumentException) {
        throw IOException("daemon sent an invalid reply")
    }
    return parsed as? JsonObject ?: throw IOException("daemon sent an unexpected reply")
}

/** One request, one reply line; `ok:false` becomes an [IOException] with the daemon's error. */
internal fun call(socket: Path, request: JsonObject): JsonObject {
    val channel = connect(socket, request)
    channel.use {
        val reply = parseObject(Channels.newInputStream(it).bufferedReader().readLine())
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw IOException("daemon refused the request: ${(reply["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()}")
        }
        return reply
    }
}

internal fun text(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

private fun decimal(element: JsonElement?): BigDecimal? =
    (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.toBigDecimalOrNull()

internal fun parseFeedEvent(obj: JsonObject): FeedEvent? {
    val seq = (obj["seq"] as? JsonPrimitive)?.longOrNull ?: return null
    val at = text(obj, "at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val payload = (obj["payload"] as? JsonObject)?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }?.toMap().orEmpty()
    return FeedEvent(
        seq = seq,
        card = text(obj, "card") ?: return null,
        id = text(obj, "id") ?: return null,
        at = at,
        actor = text(obj, "actor") ?: return null,
        type = text(obj, "type") ?: return null,
        payload = payload,
    )
}

/** `events.recent` and `events.subscribe` over the daemon's socket. */
class SocketFeedTransport(private val socket: Path) : FeedTransport {
    override fun recent(limit: Int): RecentEvents {
        val reply = call(socket, buildJsonObject { put("cmd", "events.recent"); put("limit", limit) })
        val events = (reply["result"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseFeedEvent) }
        val truncated = (reply["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false
        return RecentEvents(events, truncated)
    }

    override fun subscribe(after: Long?, listener: FeedListener): AutoCloseable {
        val channel = connect(
            socket,
            buildJsonObject {
                put("cmd", "events.subscribe")
                after?.let { put("after", it) }
            },
        )
        val closedByCaller = AtomicBoolean(false)
        val reader = Thread({
            try {
                val lines = Channels.newInputStream(channel).bufferedReader()
                while (true) {
                    val line = lines.readLine() ?: break
                    val event = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject
                    ((event?.get("event")) as? JsonObject)?.let(::parseFeedEvent)?.let(listener::onEvent)
                }
            } catch (e: IOException) {
                // The stream ended; reported below.
            }
            if (!closedByCaller.get()) listener.onClosed()
        }, "feed-subscription")
        reader.isDaemon = true
        reader.start()
        return AutoCloseable {
            closedByCaller.set(true)
            channel.close()
        }
    }
}

/**
 * `cost.summary` over the daemon's socket, asking for days in [zone]. A missing or invalid `limits` means no limits,
 * and missing `stages`/`days` (an older daemon) mean empty lists, never an error.
 */
class SocketCostSummarySource(private val socket: Path, private val zone: ZoneId = ZoneOffset.UTC) : CostSummarySource {
    override fun summary(from: Instant, to: Instant): CostSummary {
        val reply = call(
            socket,
            buildJsonObject {
                put("cmd", "cost.summary")
                put("from", from.toString())
                put("to", to.toString())
                put("zone", zone.id)
            },
        )
        val result = reply["result"] as? JsonObject ?: throw IOException("daemon sent a cost summary without result")
        val limits = result["limits"] as? JsonObject
        val cards = (result["cards"] as? JsonArray).orEmpty().mapNotNull { element ->
            val card = element as? JsonObject ?: return@mapNotNull null
            CardCost(text(card, "card") ?: return@mapNotNull null, decimal(card["costUsd"]), statusOf(text(card, "status")))
        }
        val stages = (result["stages"] as? JsonArray).orEmpty().mapNotNull { element ->
            val stage = element as? JsonObject ?: return@mapNotNull null
            StageCost(text(stage, "stage") ?: return@mapNotNull null, decimal(stage["costUsd"]), statusOf(text(stage, "status")))
        }
        val days = (result["days"] as? JsonArray).orEmpty().mapNotNull { element ->
            val day = element as? JsonObject ?: return@mapNotNull null
            val date = text(day, "day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            DayCost(date, decimal(day["costUsd"]), statusOf(text(day, "status")))
        }
        val reached = (result["cardsReached"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        return CostSummary(
            totalUsd = decimal(result["totalUsd"]),
            status = statusOf(text(result, "status")),
            cards = cards,
            limits = CostLimitsInfo(limits?.let { decimal(it["perCardUsd"]) }, limits?.let { decimal(it["perDayUsd"]) }),
            dayReached = (result["dayReached"] as? JsonPrimitive)?.booleanOrNull ?: false,
            cardsReached = reached,
            stages = stages,
            days = days,
        )
    }

    private fun statusOf(name: String?): CostStatus = CostStatus.entries.firstOrNull { it.name == name } ?: CostStatus.UNKNOWN
}

/** `column.times` over the daemon's socket; rows with an unknown column or kind are skipped. */
class SocketColumnTimesSource(private val socket: Path) : ColumnTimesSource {
    override fun times(from: Instant, to: Instant): Map<Column, ColumnTime> {
        val reply = call(socket, buildJsonObject { put("cmd", "column.times"); put("from", from.toString()); put("to", to.toString()) })
        val result = reply["result"] as? JsonObject ?: throw IOException("daemon sent column times without result")
        return (result["columns"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val column = Column.entries.firstOrNull { it.name == text(row, "column") } ?: return@mapNotNull null
            val kind = ColumnTimeKind.entries.firstOrNull { it.name == text(row, "kind") } ?: return@mapNotNull null
            val seconds = (row["seconds"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val stays = (row["stays"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            column to ColumnTime(kind, seconds, stays)
        }.toMap()
    }
}
