package shipfloor.app

import java.math.BigDecimal
import shipfloor.core.FactorySettings
import shipfloor.core.FactorySettingsRules
import shipfloor.core.SettingsIssue

/** Why the last save did not go through. */
enum class SaveError { UNREACHABLE, REFUSED }

/** The edited settings next to the saved ones; pure state, no IO except through the [SettingsClient] given to [save]. */
data class SettingsDraft(
    val saved: FactorySettings,
    val draft: FactorySettings = saved,
    val error: SaveError? = null,
    val issues: List<SettingsIssue> = emptyList(),
) {
    /** How many of the four setting groups (pool, work hours, cost, notifications) differ from the saved ones. */
    val changeCount: Int
        get() = listOf(
            draft.poolSize != saved.poolSize,
            draft.workHours != saved.workHours,
            draft.cost.perCardUsd.differsFrom(saved.cost.perCardUsd) || draft.cost.perDayUsd.differsFrom(saved.cost.perDayUsd),
            draft.notifications != saved.notifications,
        ).count { it }

    fun discard(): SettingsDraft = SettingsDraft(saved)

    /** Validates locally, then sends the draft; a failed save keeps the draft and says why. */
    fun save(client: SettingsClient): SettingsDraft {
        val local = FactorySettingsRules.validate(draft)
        if (local.isNotEmpty()) return copy(error = null, issues = local)
        return when (val result = client.save(draft)) {
            is SaveResult.Saved -> SettingsDraft(result.settings)
            SaveResult.Unreachable -> copy(error = SaveError.UNREACHABLE, issues = emptyList())
            is SaveResult.Refused -> copy(
                error = SaveError.REFUSED,
                issues = result.field?.let { listOf(SettingsIssue(it, result.reason)) } ?: emptyList(),
            )
        }
    }

    private fun BigDecimal?.differsFrom(other: BigDecimal?): Boolean =
        if (this == null || other == null) this != other else compareTo(other) != 0
}
