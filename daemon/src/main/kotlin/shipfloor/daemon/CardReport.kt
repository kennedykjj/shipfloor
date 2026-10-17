package shipfloor.daemon

import java.math.BigDecimal
import java.time.Duration
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventType
import shipfloor.store.StoredCard

/** How trustworthy a card's summed cost is. The cost is always an estimate. */
enum class CostStatus { KNOWN, PARTIAL, UNKNOWN, NONE }

/** One Done card: seconds spent per column (summed over visits) and its estimated cost. */
data class CardReportRow(
    val cardId: String,
    val title: String,
    val stageSeconds: Map<Column, Long>,
    val costUsd: BigDecimal?,
    val costStatus: CostStatus,
)

/** Lead time per stage and estimated cost per Done card. Pure: no IO, no clock. */
object CardReport {

    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')

    /** Rows for the cards currently in Done; [eventsOf] returns a card's events in seq order. */
    fun build(cards: List<StoredCard>, eventsOf: (String) -> List<Event>): List<CardReportRow> =
        cards.filter { it.card.column == Column.DONE }.map { stored ->
            val events = eventsOf(stored.id)
            val (costUsd, status) = cost(events)
            CardReportRow(stored.id, stored.card.title, stageSeconds(events), costUsd, status)
        }

    fun toCsv(rows: List<CardReportRow>): String {
        val header = listOf("card_id", "title") + Column.entries.map { it.name.lowercase() } + listOf("cost_usd_estimate", "cost_status")
        val lines = rows.map { row ->
            val cells = listOf(row.cardId, quote(row.title)) +
                Column.entries.map { (row.stageSeconds[it] ?: 0L).toString() } +
                listOf(row.costUsd?.toPlainString().orEmpty(), row.costStatus.name)
            cells.joinToString(",")
        }
        return (listOf(header.joinToString(",")) + lines).joinToString("\n", postfix = "\n")
    }

    private fun stageSeconds(events: List<Event>): Map<Column, Long> {
        val steps = events.filter { it.type == EventType.CARD_CREATED || it.type == EventType.CARD_MOVED }
        val seconds = mutableMapOf<Column, Long>()
        var column = Column.INBOX
        var since = steps.firstOrNull { it.type == EventType.CARD_CREATED }?.at ?: return seconds
        for (move in steps.filter { it.type == EventType.CARD_MOVED }) {
            seconds.merge(column, Duration.between(since, move.at).seconds, Long::plus)
            column = Column.entries.firstOrNull { it.name == move.payload["to"] } ?: continue
            since = move.at
        }
        return seconds
    }

    private fun cost(events: List<Event>): Pair<BigDecimal?, CostStatus> {
        val costs = events.filter { it.type == EventType.RUN_COST_RECORDED }
        if (costs.isEmpty()) return null to CostStatus.NONE
        val statuses = costs.map { it.payload["status"] }
        val status = when {
            "UNKNOWN" in statuses -> CostStatus.UNKNOWN
            "PARTIAL" in statuses -> CostStatus.PARTIAL
            else -> CostStatus.KNOWN
        }
        if (status == CostStatus.UNKNOWN) return null to status
        val total = costs
            .filter { it.payload["status"] == "KNOWN" || it.payload["status"] == "PARTIAL" }
            .mapNotNull { it.payload["cost_usd"]?.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        return total to status
    }

    private fun quote(title: String): String {
        val safe = if (title.firstOrNull() in FORMULA_PREFIXES) "'$title" else title
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
