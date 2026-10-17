package shipfloor.daemon

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import shipfloor.core.AssistantCard
import shipfloor.core.AssistantState
import shipfloor.core.AssistantStateRules
import shipfloor.core.AssistantStates
import shipfloor.core.Column
import shipfloor.core.ColumnTimes
import shipfloor.core.Event
import shipfloor.core.EventType
import shipfloor.core.QueueForecasts
import shipfloor.store.CardEventStore
import shipfloor.store.StoredCard

private val ISSUE_NUMBER = Regex("^[0-9]{1,10}$")

private const val LABEL_HEX_LENGTH = 8

private val FORECAST_WINDOW = Duration.ofDays(7)

/**
 * Read-only `factory.pulse`: the assistant state, its phrase, the pool capacity and the cards with an active run.
 * Writes no event. Cards are cited by label only (`#<issue number>` or 8 hex characters), never by title.
 */
class FactoryPulse(
    private val store: CardEventStore,
    private val pool: RunPool,
    private val poolCapacity: () -> Int,
    private val clock: Clock,
    private val rules: AssistantStateRules,
    private val speaker: AssistantSpeaker,
) {
    private val waitingColumns = setOf(Column.SPEC_REVIEW, Column.STAGED)

    fun handle(request: Request): Response {
        if (request !is Request.FactoryPulse) return Response.Error("not a factory pulse request")
        val now = clock.instant()
        val stored = store.cards().filter { it.id != SettingsCommands.SETTINGS_CARD_ID }.sortedBy { it.id }
        val events = stored.associate { it.id to store.events(it.id) }
        val state = AssistantStates.derive(stored.map { AssistantCard(it.card.column, events.getValue(it.id)) }, now, rules)
        // Same derive with night and tiredness switched off, so a single card answers only "is it blocked".
        val blockOnly = rules.copy(nightStart = LocalTime.MIDNIGHT, nightEnd = LocalTime.MIDNIGHT, tiredFailures = Int.MAX_VALUE)
        val byId = stored.associateBy { it.id }
        val active = pool.active().map { labelOf(byId[it.cardId], it.cardId) }
        val facts = AssistantFacts(
            waitingOwner = stored.filter { it.card.column in waitingColumns }.map { labelOf(it, it.id) },
            blocked = stored
                .filter { derivesBlocked(it, events.getValue(it.id), now, blockOnly) }
                .map { labelOf(it, it.id) },
            working = active,
            recentFailures = stored.sumOf { card -> events.getValue(card.id).count { isRecentFailure(it, now) } },
        )
        val capacity = poolCapacity()
        val medians = ColumnTimes.of(events.values.toList(), now.minus(FORECAST_WINDOW), now.plusSeconds(1))
        val forecast = QueueForecasts.of(stored.map { it.card.column }, medians, capacity, now)
        return Response.PulseResult(state, speaker.say(state, facts), capacity, active, forecast)
    }

    private fun derivesBlocked(card: StoredCard, events: List<Event>, now: Instant, blockOnly: AssistantStateRules): Boolean =
        AssistantStates.derive(listOf(AssistantCard(card.card.column, events)), now, blockOnly) == AssistantState.BLOCKED

    private fun isRecentFailure(event: Event, now: Instant): Boolean {
        if (event.at <= now.minus(rules.tiredWindow) || event.at > now) return false
        return event.type == EventType.CARD_BLOCKED ||
            (event.type == EventType.GATE_FINISHED && event.payload["verdict"] == "FAIL")
    }

    private fun labelOf(card: StoredCard?, id: String): String {
        val issue = card?.card?.references?.firstOrNull { ISSUE_NUMBER.matches(it.externalId) }
        return if (issue != null) "#${issue.externalId}" else id.take(LABEL_HEX_LENGTH).lowercase()
    }
}
