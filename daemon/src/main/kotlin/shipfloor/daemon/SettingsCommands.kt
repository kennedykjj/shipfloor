package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.FactorySettings
import shipfloor.core.FactorySettingsRules
import shipfloor.store.CardEventStore

/**
 * Reads and saves the factory settings. Only the owner, with a valid proof, saves; the file is written first and
 * the SETTINGS_SAVED event second, and a failed event puts the previous file back.
 */
class SettingsCommands(
    private val store: SettingsStore,
    private val recorder: EventRecorder,
    private val cards: CardEventStore,
    private val ownerProof: OwnerProof,
    private val env: Map<String, String>,
    private val clock: Clock,
    private val log: DaemonLog = StderrDaemonLog,
) {

    fun handle(request: Request): Response = when (request) {
        Request.SettingsGet -> get()
        is Request.SettingsSave -> save(request)
        else -> Response.Error("not a settings command")
    }

    private fun get(): Response = try {
        Response.Settings(store.load())
    } catch (e: SettingsFileException) {
        log.log("settings_command_failed", mapOf("cmd" to "settings.get"))
        Response.Error("settings: ${e.message}")
    }

    @Synchronized
    private fun save(request: Request.SettingsSave): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("settings_command_refused", mapOf("cmd" to "settings.save"))
            return Response.Error("only the owner, with a valid proof, saves settings")
        }
        val unlimitedAllowed = env[UNLIMITED_ENV] == "true"
        FactorySettingsRules.validate(request.settings, unlimitedAllowed).firstOrNull()?.let { issue ->
            log.log("settings_command_refused", mapOf("cmd" to "settings.save", "field" to issue.field))
            return Response.Error("${issue.field}: ${issue.reason}")
        }
        val previous = try {
            store.snapshot().also { store.save(request.settings) }
        } catch (e: SettingsFileException) {
            log.log("settings_command_failed", mapOf("cmd" to "settings.save"))
            return Response.Error("settings: ${e.message}")
        }
        try {
            recorder.record(SETTINGS_CARD_ID, cards.card(SETTINGS_CARD_ID) ?: Card.create(CARD_TITLE, Column.INBOX), savedEvent(request.settings))
        } catch (e: RuntimeException) {
            log.log("settings_command_failed", mapOf("cmd" to "settings.save", "card" to SETTINGS_CARD_ID, "error" to e.javaClass.simpleName))
            runCatching { store.restore(previous) }
            return Response.Error("settings: the change could not be recorded, nothing was saved")
        }
        log.log("settings_command", mapOf("cmd" to "settings.save", "card" to SETTINGS_CARD_ID))
        return Response.Settings(request.settings)
    }

    /** The payload names what is configured, never the cost values. */
    private fun savedEvent(settings: FactorySettings) = Event(
        id = EventId(UUID.randomUUID().toString()),
        at = clock.instant(),
        actor = Actor.Owner,
        type = EventType.SETTINGS_SAVED,
        payload = mapOf(
            "poolSize" to settings.poolSize.toString(),
            "workHours" to if (settings.workHours == null) "off" else "set",
        ),
    )

    companion object {
        /** Reserved card that carries the settings events; hidden from card.list and the report. */
        const val SETTINGS_CARD_ID = "factory-settings"
        private const val CARD_TITLE = "Factory settings"
        private const val UNLIMITED_ENV = "SHIPFLOOR_COST_UNLIMITED"
    }
}
