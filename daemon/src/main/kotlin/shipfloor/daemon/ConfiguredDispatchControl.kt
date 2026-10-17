package shipfloor.daemon

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import shipfloor.store.CardEventStore

/**
 * Launch guard and work schedule driven by the `factory.yml` of the first registered project, re-read on every
 * call. Fails closed: with no project, a disabled dispatcher or any unreadable or invalid setting nothing launches.
 */
class ConfiguredDispatchControl(
    private val registry: ProjectRegistry,
    private val source: ProjectConfigSource,
    private val env: Map<String, String>,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val clock: Clock,
    private val log: DaemonLog,
    private val settings: SettingsStore? = null,
) : LaunchGuard, WorkSchedule {

    private sealed interface Control {
        data class Open(val settings: DispatcherSettings, val budget: CostBudget) : Control
        data object Closed : Control
    }

    private data class LoggedState(val state: String, val field: String?)

    private var lastLogged: LoggedState? = null
    private var lastOpen: Control.Open? = null

    override fun allows(cardId: String): Boolean = (control() as? Control.Open)?.budget?.allows(cardId) ?: false

    override fun dayLimitReached(): Boolean = (control() as? Control.Open)?.budget?.dayLimitReached() ?: true

    /** The cost limits of the configured project; none while the dispatcher is closed. */
    fun limits(): CostLimits = (control() as? Control.Open)?.settings?.limits ?: CostLimits.NONE

    override fun isWorkTime(now: Instant): Boolean = (control() as? Control.Open)?.settings?.schedule?.isWorkTime(now) ?: false

    /** Lets the cost budget block the card when its run pushed it over the per-card limit. */
    fun onRunFinished(cardId: String) {
        val open = (control() as? Control.Open) ?: synchronized(this) { lastOpen }
        open?.budget?.onRunFinished(cardId)
    }

    private fun control(): Control {
        val project = registry.list().firstOrNull() ?: return Control.Closed
        return try {
            val yaml = parse(requireNotNull(source.read(Path.of(project.repo))) { "factory.yml missing" })
            decide(yaml)
        } catch (e: InvalidDispatcherSettingsException) {
            note("invalid", e.field)
            Control.Closed
        } catch (e: RuntimeException) {
            note("invalid", FACTORY_YML)
            Control.Closed
        }
    }

    private fun decide(yaml: Map<String, Any?>): Control {
        val settings = withSaved(DispatcherSettings.resolve(yaml, env))
        if ((yaml["cost"] as? Map<*, *>)?.containsKey("unlimited") == true) {
            throw InvalidDispatcherSettingsException("cost.unlimited", "cost.unlimited is only accepted from the daemon environment")
        }
        if (!settings.enabled) {
            note("disabled", null)
            return Control.Closed
        }
        if (!settings.unlimited) {
            if (settings.limits.perCardUsd == null) throw InvalidDispatcherSettingsException("cost.per_card_usd", "cost.per_card_usd is required")
            if (settings.limits.perDayUsd == null) throw InvalidDispatcherSettingsException("cost.per_day_usd", "cost.per_day_usd is required")
        }
        note("enabled", null)
        val open = Control.Open(settings, CostBudget(store, recorder, settings.limits, clock, settings.zone, log))
        synchronized(this) { lastOpen = open }
        return open
    }

    /** Saved settings, when a settings file exists, replace the work hours and cost limits of factory.yml. */
    private fun withSaved(resolved: DispatcherSettings): DispatcherSettings {
        val saved = try {
            if (settings?.exists() == true) settings.load() else return resolved
        } catch (e: RuntimeException) {
            throw InvalidDispatcherSettingsException(SETTINGS_FILE, "$SETTINGS_FILE is invalid")
        }
        val hours = saved.workHours
        val schedule: WorkSchedule
        val zone: ZoneId
        if (hours == null) {
            schedule = AlwaysWorkTime
            zone = ZoneOffset.UTC
        } else {
            zone = ZoneId.of(hours.zone)
            schedule = try {
                WorkHours(hours.start, hours.end, hours.days, zone)
            } catch (e: InvalidWorkHoursException) {
                throw InvalidDispatcherSettingsException(SETTINGS_FILE, "$SETTINGS_FILE is invalid")
            }
        }
        return resolved.copy(limits = CostLimits(saved.cost.perCardUsd, saved.cost.perDayUsd), schedule = schedule, zone = zone)
    }

    private fun parse(text: String): Map<String, Any?> {
        val root = try {
            Load(LoadSettings.builder().setCodePointLimit(MAX_CHARS).setMaxAliasesForCollections(MAX_ALIASES).build())
                .loadFromString(text)
        } catch (e: YamlEngineException) {
            throw InvalidDispatcherSettingsException(FACTORY_YML, "$FACTORY_YML is not valid YAML")
        }
        val map = root as? Map<*, *> ?: throw InvalidDispatcherSettingsException(FACTORY_YML, "$FACTORY_YML must be a map")
        return map.entries.associate { (key, value) ->
            (key as? String ?: throw InvalidDispatcherSettingsException(FACTORY_YML, "$FACTORY_YML has a key that is not text")) to value
        }
    }

    @Synchronized
    private fun note(state: String, field: String?) {
        val current = LoggedState(state, field)
        if (current == lastLogged) return
        lastLogged = current
        log.log("dispatcher_state", listOfNotNull("state" to state, field?.let { "field" to it }).toMap())
    }

    private companion object {
        const val FACTORY_YML = "factory.yml"
        const val SETTINGS_FILE = "settings.json"
        const val MAX_CHARS = 64 * 1024
        const val MAX_ALIASES = 10
    }
}
