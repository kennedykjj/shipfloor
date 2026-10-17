package shipfloor.daemon

import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZoneOffset

/** A dispatcher setting is invalid; [field] names it. The message never carries the offending value. */
class InvalidDispatcherSettingsException(val field: String, message: String) : RuntimeException(message)

/**
 * What the dispatcher may do, resolved from the project's `factory.yml` and the daemon environment.
 * [unlimited] exists only in the environment: removing the cost ceiling must not be a commit on `main`.
 */
data class DispatcherSettings(
    val enabled: Boolean,
    val limits: CostLimits,
    val unlimited: Boolean,
    val schedule: WorkSchedule,
    val zone: ZoneId,
) {
    companion object {
        const val ENABLED_ENV = "SHIPFLOOR_DISPATCHER_ENABLED"
        const val UNLIMITED_ENV = "SHIPFLOOR_COST_UNLIMITED"
        private const val TRUE = "true"
        private const val FALSE = "false"

        fun resolve(factoryYaml: Map<String, Any?>?, env: Map<String, String>): DispatcherSettings {
            val schedule = workSchedule(factoryYaml)
            return DispatcherSettings(
                enabled = enabled(factoryYaml, env),
                limits = limits(factoryYaml),
                unlimited = env[UNLIMITED_ENV] == TRUE,
                schedule = schedule,
                zone = (schedule as? WorkHours)?.zone ?: ZoneOffset.UTC,
            )
        }

        private fun enabled(yaml: Map<String, Any?>?, env: Map<String, String>): Boolean {
            val field = "dispatcher.enabled"
            val fromEnv = env[ENABLED_ENV]
            if (fromEnv != null) {
                return when (fromEnv) {
                    TRUE -> true
                    FALSE -> false
                    else -> throw InvalidDispatcherSettingsException(field, "$ENABLED_ENV must be exactly true or false")
                }
            }
            val section = yaml?.get("dispatcher") ?: return false
            val map = section as? Map<*, *> ?: throw InvalidDispatcherSettingsException("dispatcher", "dispatcher must be a map")
            val raw = map["enabled"] ?: return false
            return raw as? Boolean ?: throw InvalidDispatcherSettingsException(field, "$field must be true or false")
        }

        private fun limits(yaml: Map<String, Any?>?): CostLimits {
            val section = yaml?.get("cost") ?: return CostLimits.NONE
            val map = section as? Map<*, *> ?: throw InvalidDispatcherSettingsException("cost", "cost must be a map")
            return CostLimits(limit(map, "per_card_usd"), limit(map, "per_day_usd"))
        }

        private fun limit(map: Map<*, *>, key: String): BigDecimal? {
            val field = "cost.$key"
            val text = when (val raw = map[key]) {
                null -> return null
                is Number -> raw.toString()
                is String -> raw
                else -> throw InvalidDispatcherSettingsException(field, "$field must be a positive number")
            }
            val number = try {
                BigDecimal(text)
            } catch (e: NumberFormatException) {
                throw InvalidDispatcherSettingsException(field, "$field must be a positive number")
            }
            if (number.signum() <= 0) throw InvalidDispatcherSettingsException(field, "$field must be a positive number")
            return number
        }

        private fun workSchedule(yaml: Map<String, Any?>?): WorkSchedule = try {
            WorkHours.from(yaml)
        } catch (e: InvalidWorkHoursException) {
            throw InvalidDispatcherSettingsException(e.field, "${e.field} is invalid")
        }
    }
}
