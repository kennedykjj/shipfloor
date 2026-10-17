package shipfloor.app

import java.time.Instant
import shipfloor.core.Column
import shipfloor.core.EventType
import shipfloor.core.OWNER_WAIT_COLUMNS

/** A card that waits for the owner's decision; [since] is when it started waiting. */
data class WaitingCard(val id: String, val column: Column, val since: Instant, val externalId: String?)

/** The cards waiting for the owner, kept in step with the snapshot and the event stream. Immutable. */
class WaitingCards private constructor(private val byId: Map<String, WaitingCard>) {
    val ordered: List<WaitingCard> = byId.values.sortedWith(compareBy<WaitingCard> { it.since }.thenBy { it.id })
    val count: Int = byId.size

    fun contains(id: String): Boolean = id in byId

    fun withSnapshot(cards: List<BoardCard>, now: Instant): WaitingCards {
        val next = cards.filter { it.column in OWNER_WAIT_COLUMNS }.associate { card ->
            val known = byId[card.id]
            card.id to WaitingCard(card.id, card.column, known?.since ?: now, known?.externalId)
        }
        return WaitingCards(next)
    }

    fun withEvent(event: BoardEvent, fetched: BoardCard?, now: Instant): WaitingCards =
        when (event.type) {
            EventType.CARD_MOVED, EventType.OWNER_APPROVED -> {
                val to = event.payload["to"]?.let { name -> Column.entries.firstOrNull { it.name == name } }
                if (to == null) this else place(event.cardId, to, now, null)
            }
            EventType.CARD_CREATED -> place(event.cardId, fetched?.column, now, event.payload["externalId"])
            else -> this
        }

    private fun place(id: String, column: Column?, now: Instant, externalId: String?): WaitingCards {
        if (column == null || column !in OWNER_WAIT_COLUMNS) {
            return if (id in byId) WaitingCards(byId - id) else this
        }
        val known = byId[id]
        val card = WaitingCard(id, column, known?.since ?: now, externalId ?: known?.externalId)
        return WaitingCards(byId + (id to card))
    }

    companion object {
        val EMPTY = WaitingCards(emptyMap())
    }
}

enum class NoticeLanguage { PT_BR, EN }

/** Notification and tooltip texts: only the state phrase and card references, never card text. */
object NoticeText {
    const val TITLE = "Shipfloor"
    private const val LISTED = 5
    private const val SHORT_ID = 8

    fun label(card: WaitingCard): String = card.externalId?.let { "#$it" } ?: card.id.take(SHORT_ID)

    fun body(cards: List<WaitingCard>, language: NoticeLanguage): String {
        val single = cards.singleOrNull()
        return when {
            cards.isEmpty() -> ""
            single != null -> when (language) {
                NoticeLanguage.PT_BR -> "Card ${label(single)} espera sua decisão: ${single.column.displayName}."
                NoticeLanguage.EN -> "Card ${label(single)} is waiting for your decision: ${single.column.displayName}."
            }
            else -> {
                val listed = cards.take(LISTED).joinToString(", ") { label(it) }
                val more = cards.size - LISTED
                when (language) {
                    NoticeLanguage.PT_BR ->
                        "${cards.size} cards esperam sua decisão: $listed${if (more > 0) " e mais $more" else ""}."
                    NoticeLanguage.EN ->
                        "${cards.size} cards are waiting for your decision: $listed${if (more > 0) " and $more more" else ""}."
                }
            }
        }
    }

    fun tooltip(count: Int, language: NoticeLanguage): String =
        when {
            count <= 0 -> TITLE
            language == NoticeLanguage.PT_BR -> "$TITLE · $count esperando você"
            else -> "$TITLE · $count waiting for you"
        }
}

sealed interface NoticeDecision {
    data object None : NoticeDecision

    /** Neutral icon, no notification. */
    data object Clear : NoticeDecision

    /** [cards] is everything that waits, ordered; [notify] false only refreshes icon and tooltip. */
    data class Show(val cards: List<WaitingCard>, val notify: Boolean) : NoticeDecision
}

/** Decides what to show, remembering what was already announced. */
class OwnerNoticePolicy {
    private var announced: Set<String> = emptySet()
    private var shown = false
    private var quietSeen = false

    fun decide(waiting: WaitingCards, quiet: Boolean): NoticeDecision {
        if (quiet) return decideQuiet()
        quietSeen = false
        val ids = waiting.ordered.map { it.id }.toSet()
        return when {
            ids.isEmpty() -> {
                val wasShown = shown
                shown = false
                announced = emptySet()
                if (wasShown) NoticeDecision.Clear else NoticeDecision.None
            }
            ids.any { it !in announced } -> {
                announced = ids
                shown = true
                NoticeDecision.Show(waiting.ordered, notify = true)
            }
            ids != announced -> {
                announced = ids
                shown = true
                NoticeDecision.Show(waiting.ordered, notify = false)
            }
            else -> NoticeDecision.None
        }
    }

    private fun decideQuiet(): NoticeDecision {
        announced = emptySet()
        shown = false
        if (quietSeen) return NoticeDecision.None
        quietSeen = true
        return NoticeDecision.Clear
    }
}

object NoticeClick {
    /** The first cited card that still waits; null (open the board) when none does. */
    fun target(cited: List<String>, waiting: WaitingCards): String? = cited.firstOrNull { waiting.contains(it) }
}
