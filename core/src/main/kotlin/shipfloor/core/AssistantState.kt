package shipfloor.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What the assistant shows; derived from the event log, never stored. */
enum class AssistantState { IDLE, WORKING, WAITING_OWNER, BLOCKED, NIGHT, TIRED }

/** Night window and tiredness thresholds. The window may cross midnight; start inclusive, end exclusive. */
data class AssistantStateRules(
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val nightEnd: LocalTime = LocalTime.of(7, 0),
    val zone: ZoneId,
    val tiredFailures: Int = 3,
    val tiredWindow: Duration = Duration.ofMinutes(60),
)

/** A card's stored column and its events (the event does not carry the card id). */
data class AssistantCard(val column: Column, val events: List<Event>)

object AssistantStates {
    private val workColumns = setOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION, Column.READY_FOR_PROD)

    /** Precedence: NIGHT > TIRED > BLOCKED > WAITING_OWNER > WORKING > IDLE. */
    fun derive(cards: List<AssistantCard>, now: Instant, rules: AssistantStateRules): AssistantState {
        val sorted = cards.map { it.copy(events = it.events.sortedBy { event -> event.at }) }
        return when {
            isNight(now, rules) -> AssistantState.NIGHT
            failuresInWindow(sorted, now, rules) >= rules.tiredFailures -> AssistantState.TIRED
            sorted.any { hasOpenBlock(it.events) } -> AssistantState.BLOCKED
            sorted.any { it.column in OWNER_WAIT_COLUMNS } -> AssistantState.WAITING_OWNER
            sorted.any { it.column in workColumns } -> AssistantState.WORKING
            else -> AssistantState.IDLE
        }
    }

    private fun isNight(now: Instant, rules: AssistantStateRules): Boolean {
        val time = now.atZone(rules.zone).toLocalTime()
        return if (rules.nightStart > rules.nightEnd) {
            time >= rules.nightStart || time < rules.nightEnd
        } else {
            time >= rules.nightStart && time < rules.nightEnd
        }
    }

    private fun failuresInWindow(cards: List<AssistantCard>, now: Instant, rules: AssistantStateRules): Int {
        val since = now.minus(rules.tiredWindow)
        return cards.sumOf { card ->
            card.events.count { it.at > since && it.at <= now && isFailure(it) }
        }
    }

    private fun isFailure(event: Event): Boolean = when (event.type) {
        EventType.CARD_BLOCKED -> true
        EventType.GATE_FINISHED -> event.payload["verdict"] == "FAIL"
        else -> false
    }

    /** Same owner-unblock rule as the daemon's budget: only an owner move, approval or unblock after the block clears it. */
    fun hasOpenBlock(events: List<Event>): Boolean {
        val lastBlock = events.indexOfLast { it.type == EventType.CARD_BLOCKED }
        if (lastBlock < 0) return false
        return events.drop(lastBlock + 1).none { isOwnerUnblock(it) }
    }

    private fun isOwnerUnblock(event: Event): Boolean =
        ((event.type == EventType.CARD_MOVED || event.type == EventType.CARD_UNBLOCKED) && event.actor == Actor.Owner) ||
            event.type == EventType.OWNER_APPROVED
}
