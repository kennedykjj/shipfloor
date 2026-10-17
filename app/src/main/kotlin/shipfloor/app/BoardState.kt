package shipfloor.app

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import shipfloor.core.Column
import shipfloor.core.ColumnTime
import shipfloor.core.ColumnTimeKind
import shipfloor.core.EventType
import shipfloor.core.OWNER_WAIT_COLUMNS

/** The total cost of a card as `card.list` reports it; [costUsd] is present only for KNOWN and PARTIAL. */
data class BoardCost(val status: CostStatus, val costUsd: BigDecimal? = null)

/** A card of the board. [enteredAt] and [cost] are null when the daemon does not send them (an older daemon). */
data class BoardCard(
    val id: String,
    val title: String,
    val column: Column,
    val blocked: Boolean = false,
    val enteredAt: Instant? = null,
    val cost: BoardCost? = null,
)

/** What a board card says about itself: texts built only from the column, numbers and ids, never from the title. */
data class CardFace(
    val stage: String,
    val age: String?,
    val cost: String,
    val costNote: String?,
    val blocked: Boolean,
    val blockedLabel: String?,
    val description: String,
) {
    companion object {
        fun of(card: BoardCard, clock: Clock): CardFace {
            val stage = card.column.displayName
            val age = card.enteredAt?.let { "${Texts.AGO} ${ColumnTimeLabel.duration(Duration.between(it, clock.instant()).seconds)}" }
            val usd = card.cost?.takeIf { it.status == CostStatus.KNOWN || it.status == CostStatus.PARTIAL }?.costUsd
            val cost = if (usd == null) Texts.COST_UNKNOWN else formatCost(usd)
            val costNote = if (usd != null && card.cost?.status == CostStatus.PARTIAL) Texts.PARTIAL else null
            val blockedLabel = if (card.blocked) Texts.BLOCKED else null
            val description = listOfNotNull(card.title, card.id, stage, age, cost, costNote, blockedLabel).joinToString(", ")
            return CardFace(stage, age, cost, costNote, card.blocked, blockedLabel, description)
        }
    }
}

enum class StageHue { REFINE, DEVELOP, VALIDATE, OK, ACCENT, NEUTRAL }

/** The hue of a column's stage (DESIGN.md, "Color by stage"); queues and Inbox stay neutral. */
fun stageHue(column: Column): StageHue =
    when (column) {
        Column.REFINEMENT -> StageHue.REFINE
        Column.CODING -> StageHue.DEVELOP
        Column.VALIDATION -> StageHue.VALIDATE
        Column.READY_FOR_PROD, Column.DONE -> StageHue.OK
        Column.SPEC_REVIEW, Column.STAGED -> StageHue.ACCENT
        Column.INBOX, Column.READY_FOR_REFINEMENT, Column.READY_FOR_DEV -> StageHue.NEUTRAL
    }

/** Columns whose cards wait for a decision of the owner: the owner-only moves in `MoveRules` start here (PRODUCT.md). */
val OWNER_DECISION_COLUMNS: Set<Column> = OWNER_WAIT_COLUMNS

fun isWaitingForOwner(column: Column): Boolean = column in OWNER_DECISION_COLUMNS

enum class PanelTab { SUMMARY, SPEC, EVENTS, RUNS }

/**
 * The side panel of the board: which card is open, the chosen tab and the width.
 * The tab and the width outlive the card, so they survive a swap or a close and reopen in the same session.
 */
data class SidePanelState(
    val cardId: String? = null,
    val tab: PanelTab = PanelTab.SUMMARY,
    val width: Int = DEFAULT_WIDTH,
    val focusReturnCardId: String? = null,
) {
    /** Opens [cardId], or swaps the card of an open panel; the tab is kept while the panel stays open. */
    fun open(cardId: String): SidePanelState =
        copy(cardId = cardId, tab = if (this.cardId == null) PanelTab.SUMMARY else tab, focusReturnCardId = null)

    fun close(): SidePanelState = copy(cardId = null, focusReturnCardId = null)

    fun selectTab(tab: PanelTab): SidePanelState = copy(tab = tab)

    fun resize(width: Int): SidePanelState = copy(width = width.coerceIn(MIN_WIDTH, MAX_WIDTH))

    /** Esc closes the panel and hands the focus back to its card, unless the palette or a text field owns the key. */
    fun escape(paletteOpen: Boolean, textFieldFocused: Boolean): SidePanelState =
        if (cardId == null || paletteOpen || textFieldFocused) this else copy(cardId = null, focusReturnCardId = cardId)

    companion object {
        const val MIN_WIDTH = 360
        const val MAX_WIDTH = 680
        const val DEFAULT_WIDTH = 480
    }
}

enum class PanelPlacement { SIDE_BY_SIDE, OVERLAY }

private const val PANEL_SIDE_BY_SIDE_MIN_WINDOW = 880

fun panelPlacement(windowWidth: Int): PanelPlacement =
    if (windowWidth < PANEL_SIDE_BY_SIDE_MIN_WINDOW) PanelPlacement.OVERLAY else PanelPlacement.SIDE_BY_SIDE

enum class PathStep { TRAVELED, CURRENT, NEXT }

data class PathMark(val column: Column, val name: String, val step: PathStep)

/** The ten columns in board order, each marked as traveled, current or next relative to [column]. */
fun cardPath(column: Column): List<PathMark> =
    Column.entries.map { mark ->
        val step = when {
            mark == column -> PathStep.CURRENT
            mark.ordinal < column.ordinal -> PathStep.TRAVELED
            else -> PathStep.NEXT
        }
        PathMark(mark, mark.displayName, step)
    }

fun cardPathDescription(column: Column): String = "etapa ${column.ordinal + 1} de ${Column.entries.size}: ${column.displayName}"

sealed interface PanelFooter {
    data object None : PanelFooter

    data class Decision(val approveTo: Column) : PanelFooter
}

/** Only the owner columns show a decision, and it names where the approval sends the card. */
fun panelFooter(column: Column): PanelFooter =
    when (column) {
        Column.SPEC_REVIEW -> PanelFooter.Decision(Column.READY_FOR_DEV)
        Column.STAGED -> PanelFooter.Decision(Column.READY_FOR_PROD)
        else -> PanelFooter.None
    }

/** The column subtitle: the statistic and its duration, e.g. "mediana 6 min" or "espera média 1 h 05 min". */
object ColumnTimeLabel {
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L

    fun of(time: ColumnTime): String {
        val name = when (time.kind) {
            ColumnTimeKind.MEDIAN -> Texts.MEDIAN
            ColumnTimeKind.MEAN_WAIT -> Texts.MEAN_WAIT
        }
        return "$name ${duration(time.seconds)}"
    }

    fun duration(seconds: Long): String {
        val minutes = seconds / SECONDS_PER_MINUTE
        return when {
            minutes < 1 -> "< 1 min"
            minutes < MINUTES_PER_HOUR -> "$minutes min"
            else -> "${minutes / MINUTES_PER_HOUR} h ${(minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')} min"
        }
    }
}

private val FLOW_ORDER = listOf(Column.REFINEMENT, Column.SPEC_REVIEW, Column.CODING, Column.VALIDATION, Column.STAGED)

// Internal on purpose: the Compose compiler adds a `$stable` field to public classes, and these carry only their own fields.

/** One stage of the flow bar: the column and the seconds its statistic reports. Numbers only, never card text. */
internal data class TimeSegment(val column: Column, val seconds: Long)

/** The flow bar ("Onde o tempo vai"): a segment per column with statistics and the share of owner wait. */
internal data class TimeSplit(val segments: List<TimeSegment>, val waitPercent: Int) {
    companion object {
        /** Null when no column has statistics or the total is zero: the bar is then absent, never "0%". */
        fun of(times: Map<Column, ColumnTime>): TimeSplit? {
            val segments = FLOW_ORDER.mapNotNull { column -> times[column]?.let { TimeSegment(column, it.seconds) } }
            val total = segments.sumOf { it.seconds }
            if (total <= 0L) return null
            val wait = segments.filter { isWaitingForOwner(it.column) }.sumOf { it.seconds }
            val percent = (200 * wait + total) / (2 * total)
            return TimeSplit(segments, percent.toInt())
        }
    }
}

/**
 * The cards of the board in insertion order. Events are absolute (`to`), so applying one twice is harmless;
 * an event whose `seq` is not above [lastSeq] was already seen and is dropped.
 */
data class Board(val cards: List<BoardCard>, val lastSeq: Long?) {
    fun applySnapshot(cards: List<BoardCard>): Board = copy(cards = cards)

    /** [fetched] is the card as the daemon reports it, used by `CARD_CREATED` only (never assumed to be in Inbox). */
    fun applyEvent(event: BoardEvent, fetched: BoardCard? = null): Board {
        if (lastSeq != null && event.seq <= lastSeq) return this
        val advanced = copy(lastSeq = event.seq)
        return when (event.type) {
            EventType.CARD_MOVED, EventType.OWNER_APPROVED -> advanced.moveTo(event)
            EventType.CARD_CREATED -> fetched?.let { advanced.upsert(it) } ?: advanced
            else -> advanced
        }
    }

    fun cardsIn(column: Column): List<BoardCard> = cards.filter { it.column == column }

    /** Shows [cardId] in [to] before the daemon confirms; the daemon's own event that follows is absolute. */
    fun withOptimisticMove(cardId: String, to: Column): Board = relocate(cardId, to)

    /** Undoes an optimistic move, unless the card is no longer in [optimistic] (a daemon event moved it on). */
    fun revertMove(cardId: String, from: Column, optimistic: Column): Board =
        if (cards.any { it.id == cardId && it.column == optimistic }) relocate(cardId, from) else this

    private fun relocate(cardId: String, to: Column): Board {
        val card = cards.firstOrNull { it.id == cardId } ?: return this
        return copy(cards = cards.filter { it.id != cardId } + card.copy(column = to))
    }

    private fun moveTo(event: BoardEvent): Board {
        val target = event.payload["to"]?.let { name -> Column.entries.firstOrNull { it.name == name } } ?: return this
        val card = cards.firstOrNull { it.id == event.cardId } ?: return this
        return copy(cards = cards.filter { it.id != card.id } + card.copy(column = target))
    }

    private fun upsert(card: BoardCard): Board =
        if (cards.any { it.id == card.id }) copy(cards = cards.map { if (it.id == card.id) card else it })
        else copy(cards = cards + card)
}
