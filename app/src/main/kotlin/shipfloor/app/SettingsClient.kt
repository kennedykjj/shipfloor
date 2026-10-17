package shipfloor.app

import java.io.IOException
import java.math.BigDecimal
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shipfloor.core.CostSetting
import shipfloor.core.FactorySettings
import shipfloor.core.NotificationSetting
import shipfloor.core.NotificationType
import shipfloor.core.WorkHoursSetting

sealed interface LoadResult {
    data class Loaded(val settings: FactorySettings) : LoadResult

    data object Unreachable : LoadResult
}

sealed interface SaveResult {
    data class Saved(val settings: FactorySettings) : SaveResult

    data object Unreachable : SaveResult

    /** The daemon answered and said no; [field] is the settings field it named, when it named one. */
    data class Refused(val field: String?, val reason: String) : SaveResult
}

interface SettingsClient {
    fun load(): LoadResult

    fun save(settings: FactorySettings): SaveResult
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** JSON lines on the daemon socket: `settings.get` and `settings.save` (with the owner token as proof). */
class SocketSettingsClient(
    private val socket: Path,
    private val tokenFile: Path,
    private val timeoutMillis: Long = 3_000,
) : SettingsClient {
    override fun load(): LoadResult {
        val reply = exchange(buildJsonObject { put("cmd", "settings.get") }.toString()) ?: return LoadResult.Unreachable
        val settings = (reply["result"] as? JsonObject)?.let { parseSettings(it) } ?: return LoadResult.Unreachable
        return LoadResult.Loaded(settings)
    }

    override fun save(settings: FactorySettings): SaveResult {
        val proof = readToken() ?: return SaveResult.Refused(null, "owner token missing")
        val request = buildJsonObject {
            put("cmd", "settings.save")
            put("actor", "owner")
            put("proof", proof)
            put("settings", settingsToJson(settings))
        }
        val reply = exchange(request.toString()) ?: return SaveResult.Unreachable
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val message = (reply["error"] as? JsonPrimitive)?.content.orEmpty()
            val field = message.substringBefore(": ", "").takeIf { it.isNotEmpty() && ' ' !in it }
            return SaveResult.Refused(field, message.substringAfter(": "))
        }
        val saved = (reply["result"] as? JsonObject)?.let { parseSettings(it) } ?: return SaveResult.Unreachable
        return SaveResult.Saved(saved)
    }

    private fun readToken(): String? =
        try {
            Files.readString(tokenFile).trim().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        }

    private fun parseSettings(obj: JsonObject): FactorySettings? =
        try {
            settingsFromJson(obj)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: DateTimeParseException) {
            null
        } catch (e: ClassCastException) {
            null
        }

    /** Sends one line and returns the parsed reply object, or null when nothing usable came back in time. */
    private fun exchange(line: String): JsonObject? {
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
        val reply = CompletableFuture.supplyAsync {
            try {
                Channels.newOutputStream(channel).apply {
                    write((line + "\n").toByteArray())
                    flush()
                }
                Channels.newInputStream(channel).bufferedReader().readLine()
            } catch (e: IOException) {
                null
            }
        }
        val text = try {
            reply.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            null
        } finally {
            channel.close()
        }
        return try {
            text?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (e: SerializationException) {
            null
        }
    }
}

/** The settings wire format of the daemon (`SettingsJson.kt` there); the app does not depend on the daemon module. */
internal fun settingsToJson(settings: FactorySettings): JsonObject = buildJsonObject {
    put("poolSize", settings.poolSize)
    put(
        "workHours",
        settings.workHours?.let { wh ->
            buildJsonObject {
                put("days", JsonArray(DayOfWeek.entries.filter { it in wh.days }.map { JsonPrimitive(it.name) }))
                put("start", wh.start.format(TIME_FORMAT))
                put("end", wh.end.format(TIME_FORMAT))
                put("zone", wh.zone)
            }
        } ?: JsonNull,
    )
    put(
        "cost",
        buildJsonObject {
            put("perCardUsd", settings.cost.perCardUsd?.let { JsonPrimitive(it.toPlainString()) } ?: JsonNull)
            put("perDayUsd", settings.cost.perDayUsd?.let { JsonPrimitive(it.toPlainString()) } ?: JsonNull)
        },
    )
    put(
        "notifications",
        buildJsonObject {
            put(
                "enabled",
                JsonArray(NotificationType.entries.filter { it in settings.notifications.enabled }.map { JsonPrimitive(it.name) }),
            )
            put("quietStart", settings.notifications.quietStart?.let { JsonPrimitive(it.format(TIME_FORMAT)) } ?: JsonNull)
            put("quietEnd", settings.notifications.quietEnd?.let { JsonPrimitive(it.format(TIME_FORMAT)) } ?: JsonNull)
        },
    )
}

/** Parses the daemon's settings JSON; throws [IllegalArgumentException] when a field is malformed. */
internal fun settingsFromJson(obj: JsonObject): FactorySettings {
    val poolSize = (obj["poolSize"] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: throw IllegalArgumentException("poolSize must be an integer")
    return FactorySettings(
        poolSize = poolSize,
        workHours = workHoursFrom(obj["workHours"]),
        cost = costFrom(obj["cost"]),
        notifications = notificationsFrom(obj["notifications"]),
    )
}

private fun workHoursFrom(element: JsonElement?): WorkHoursSetting? {
    val obj = element as? JsonObject ?: return null
    val days = (obj["days"] as? JsonArray).orEmpty().map { DayOfWeek.valueOf((it as JsonPrimitive).content) }
    val zone = (obj["zone"] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("workHours.zone must be a zone name")
    return WorkHoursSetting(days.toSet(), timeFrom(obj["start"]), timeFrom(obj["end"]), zone)
}

private fun costFrom(element: JsonElement?): CostSetting {
    val obj = element as? JsonObject ?: return CostSetting(null, null)
    return CostSetting(amountFrom(obj["perCardUsd"]), amountFrom(obj["perDayUsd"]))
}

private fun amountFrom(element: JsonElement?): BigDecimal? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.content.isBlank()) return null
    return BigDecimal(primitive.content)
}

private fun notificationsFrom(element: JsonElement?): NotificationSetting {
    val obj = element as? JsonObject ?: return FactorySettings.DEFAULT.notifications
    val enabled = (obj["enabled"] as? JsonArray).orEmpty().map { NotificationType.valueOf((it as JsonPrimitive).content) }
    return NotificationSetting(enabled.toSet(), optionalTime(obj["quietStart"]), optionalTime(obj["quietEnd"]))
}

private fun optionalTime(element: JsonElement?): LocalTime? =
    if (element == null || element is JsonNull) null else timeFrom(element)

private fun timeFrom(element: JsonElement?): LocalTime =
    LocalTime.parse((element as? JsonPrimitive)?.content ?: throw IllegalArgumentException("time must be HH:mm"), TIME_FORMAT)
