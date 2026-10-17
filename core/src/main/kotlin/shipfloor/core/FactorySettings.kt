package shipfloor.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId

enum class NotificationType { DECISION_WAITING, RUN_FAILED, FACTORY_STOPPED }

data class WorkHoursSetting(val days: Set<DayOfWeek>, val start: LocalTime, val end: LocalTime, val zone: String)

data class CostSetting(val perCardUsd: BigDecimal?, val perDayUsd: BigDecimal?)

data class NotificationSetting(val enabled: Set<NotificationType>, val quietStart: LocalTime?, val quietEnd: LocalTime?)

data class FactorySettings(
    val poolSize: Int,
    val workHours: WorkHoursSetting?,
    val cost: CostSetting,
    val notifications: NotificationSetting,
) {
    companion object {
        val DEFAULT = FactorySettings(
            poolSize = 2,
            workHours = null,
            cost = CostSetting(null, null),
            notifications = NotificationSetting(NotificationType.entries.toSet(), null, null),
        )
    }
}

data class SettingsIssue(val field: String, val reason: String)

object FactorySettingsRules {
    private const val MIN_POOL = 1
    private const val MAX_POOL = 64

    fun validate(settings: FactorySettings, costUnlimitedAllowed: Boolean = true): List<SettingsIssue> {
        val issues = mutableListOf<SettingsIssue>()
        if (settings.poolSize !in MIN_POOL..MAX_POOL) {
            issues += SettingsIssue("poolSize", "pool size must be between $MIN_POOL and $MAX_POOL")
        }
        settings.workHours?.let { wh ->
            if (!wh.end.isAfter(wh.start)) {
                issues += SettingsIssue("workHours", "work hours end must be after start")
            }
            try {
                ZoneId.of(wh.zone)
            } catch (e: DateTimeException) {
                issues += SettingsIssue("workHours.zone", "unknown time zone")
            }
            if (wh.days.isEmpty()) {
                issues += SettingsIssue("workHours.days", "at least one day is required")
            }
        }
        checkLimit("cost.perCardUsd", settings.cost.perCardUsd, costUnlimitedAllowed, issues)
        checkLimit("cost.perDayUsd", settings.cost.perDayUsd, costUnlimitedAllowed, issues)
        return issues
    }

    private fun checkLimit(field: String, value: BigDecimal?, unlimitedAllowed: Boolean, issues: MutableList<SettingsIssue>) {
        if (value == null) {
            if (!unlimitedAllowed) issues += SettingsIssue(field, "a cost limit is required")
        } else if (value.signum() <= 0) {
            issues += SettingsIssue(field, "cost limit must be greater than zero")
        }
    }
}
