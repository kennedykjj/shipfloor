package shipfloor.daemon

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

class InvalidWorkHoursException(val field: String, message: String) : RuntimeException(message)

/**
 * Work window: [start, end) on the configured days, in the configured zone. A window whose end is
 * before its start crosses midnight and belongs to the day it starts.
 */
class WorkHours(
    val start: LocalTime,
    val end: LocalTime,
    val days: Set<DayOfWeek>,
    val zone: ZoneId,
) : WorkSchedule {
    init {
        if (start == end) {
            throw InvalidWorkHoursException("work_hours.start", "work_hours.start and work_hours.end must differ: $start")
        }
    }

    override fun isWorkTime(now: Instant): Boolean {
        val local = now.atZone(zone)
        val time = local.toLocalTime()
        val day = local.dayOfWeek
        return if (start.isBefore(end)) {
            day in days && !time.isBefore(start) && time.isBefore(end)
        } else {
            (day in days && !time.isBefore(start)) || (day.minus(1) in days && time.isBefore(end))
        }
    }

    companion object {
        private val DAY_NAMES = mapOf(
            "mon" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

        /** Reads the `work_hours` section of the factory.yml map; absent section means always work time. */
        fun from(raw: Map<String, Any?>?): WorkSchedule {
            val section = raw?.get("work_hours") ?: return AlwaysWorkTime
            val map = section as? Map<*, *>
                ?: throw InvalidWorkHoursException("work_hours", "work_hours must be a map")
            val start = time(map, "start")
            val end = time(map, "end")
            val days = days(map["days"])
            val zone = zone(map["timezone"])
            return WorkHours(start, end, days, zone)
        }

        private fun time(map: Map<*, *>, key: String): LocalTime {
            val field = "work_hours.$key"
            val text = map[key] as? String
                ?: throw InvalidWorkHoursException(field, "$field is required and must be HH:mm")
            return try {
                LocalTime.parse(text)
            } catch (e: DateTimeParseException) {
                throw InvalidWorkHoursException(field, "$field is not a valid HH:mm time")
            }
        }

        private fun days(value: Any?): Set<DayOfWeek> {
            val field = "work_hours.days"
            if (value == null) return DayOfWeek.values().toSet()
            val list = value as? List<*>
                ?: throw InvalidWorkHoursException(field, "$field must be a list of day names")
            val parsed = list.map { item ->
                val name = (item as? String)?.trim()?.lowercase()
                DAY_NAMES[name]
                    ?: throw InvalidWorkHoursException(field, "$field has an unknown day; use mon..sun")
            }.toSet()
            if (parsed.isEmpty()) throw InvalidWorkHoursException(field, "$field must not be empty")
            return parsed
        }

        private fun zone(value: Any?): ZoneId {
            val field = "work_hours.timezone"
            if (value == null) return ZoneOffset.UTC
            val text = value as? String
                ?: throw InvalidWorkHoursException(field, "$field must be a zone name")
            return try {
                ZoneId.of(text)
            } catch (e: DateTimeException) {
                throw InvalidWorkHoursException(field, "$field is not a valid time zone")
            }
        }
    }
}
