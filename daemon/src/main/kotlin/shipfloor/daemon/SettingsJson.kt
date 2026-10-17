package shipfloor.daemon

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shipfloor.core.CostSetting
import shipfloor.core.FactorySettings
import shipfloor.core.NotificationSetting
import shipfloor.core.NotificationType
import shipfloor.core.WorkHoursSetting

/** The settings JSON is malformed; [field] names the offending field and the message never carries its value. */
class InvalidSettingsJsonException(val field: String, message: String) : RuntimeException(message)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Renders the settings as the JSON shared by the socket protocol and the settings file. Pure: no IO. */
fun settingsToJson(settings: FactorySettings): JsonObject = buildJsonObject {
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

/** Parses the settings JSON; throws [InvalidSettingsJsonException] naming the field. Pure: no IO. */
fun settingsFromJson(obj: JsonObject): FactorySettings {
    val poolSize = (obj["poolSize"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        ?: throw InvalidSettingsJsonException("poolSize", "must be an integer")
    return FactorySettings(
        poolSize = poolSize,
        workHours = workHoursFrom(obj["workHours"]),
        cost = costFrom(obj["cost"]),
        notifications = notificationsFrom(obj["notifications"]),
    )
}

private fun workHoursFrom(element: JsonElement?): WorkHoursSetting? {
    if (element == null || element is JsonNull) return null
    val obj = element as? JsonObject ?: throw InvalidSettingsJsonException("workHours", "must be an object or null")
    val days = (obj["days"] as? JsonArray ?: throw InvalidSettingsJsonException("workHours.days", "must be a list of day names"))
        .map { item ->
            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            DayOfWeek.entries.firstOrNull { it.name == name }
                ?: throw InvalidSettingsJsonException("workHours.days", "has an unknown day name")
        }
    if (days.toSet().size != days.size) throw InvalidSettingsJsonException("workHours.days", "has a repeated day")
    val zone = (obj["zone"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw InvalidSettingsJsonException("workHours.zone", "must be a zone name")
    return WorkHoursSetting(days.toSet(), timeFrom(obj["start"], "workHours.start"), timeFrom(obj["end"], "workHours.end"), zone)
}

private fun costFrom(element: JsonElement?): CostSetting {
    if (element == null || element is JsonNull) return CostSetting(null, null)
    val obj = element as? JsonObject ?: throw InvalidSettingsJsonException("cost", "must be an object")
    return CostSetting(amountFrom(obj["perCardUsd"], "cost.perCardUsd"), amountFrom(obj["perDayUsd"], "cost.perDayUsd"))
}

private fun amountFrom(element: JsonElement?, field: String): BigDecimal? {
    if (element == null || element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw InvalidSettingsJsonException(field, "must be a number")
    if (primitive.isString && primitive.content.isBlank()) return null
    return try {
        BigDecimal(primitive.content)
    } catch (e: NumberFormatException) {
        throw InvalidSettingsJsonException(field, "must be a number")
    }
}

private fun notificationsFrom(element: JsonElement?): NotificationSetting {
    if (element == null || element is JsonNull) return FactorySettings.DEFAULT.notifications
    val obj = element as? JsonObject ?: throw InvalidSettingsJsonException("notifications", "must be an object")
    val enabled = (obj["enabled"] as? JsonArray ?: throw InvalidSettingsJsonException("notifications.enabled", "must be a list"))
        .map { item ->
            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            NotificationType.entries.firstOrNull { it.name == name }
                ?: throw InvalidSettingsJsonException("notifications.enabled", "has an unknown type")
        }
    return NotificationSetting(
        enabled.toSet(),
        optionalTime(obj["quietStart"], "notifications.quietStart"),
        optionalTime(obj["quietEnd"], "notifications.quietEnd"),
    )
}

private fun optionalTime(element: JsonElement?, field: String): LocalTime? =
    if (element == null || element is JsonNull) null else timeFrom(element, field)

private fun timeFrom(element: JsonElement?, field: String): LocalTime {
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw InvalidSettingsJsonException(field, "must be HH:mm")
    return try {
        LocalTime.parse(text, TIME_FORMAT)
    } catch (e: DateTimeParseException) {
        throw InvalidSettingsJsonException(field, "must be HH:mm")
    }
}
