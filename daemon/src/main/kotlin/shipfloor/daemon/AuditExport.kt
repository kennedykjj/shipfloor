package shipfloor.daemon

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shipfloor.core.Actor
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventType

/** Output format of an audit export. */
enum class AuditFormat {
    JSON,
    CSV;

    companion object {
        fun parse(text: String): AuditFormat? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

/** The audit trail of one card as JSON or CSV. Pure: no IO. The column list is closed; the rest of a payload never leaves. */
object AuditExport {

    val COLUMNS = listOf("event_id", "at", "type", "actor", "from", "to", "cost_usd_estimate", "cost_status")

    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')
    private val COST_STATUSES = setOf("KNOWN", "PARTIAL", "UNKNOWN")
    private val MOVE_TYPES = setOf(EventType.CARD_MOVED, EventType.OWNER_APPROVED)

    /** Renders [events] (already in chronological order) of card [cardId]. */
    fun render(cardId: String, events: List<Event>, format: AuditFormat): String {
        val rows = events.map { row(it) }
        return when (format) {
            AuditFormat.JSON -> buildJsonObject {
                put("card", cardId)
                put(
                    "events",
                    JsonArray(
                        rows.map { cells ->
                            buildJsonObject { COLUMNS.zip(cells).forEach { (name, value) -> put(name, value) } }
                        },
                    ),
                )
            }.toString()
            AuditFormat.CSV ->
                (listOf(COLUMNS.joinToString(",")) + rows.map { cells -> cells.joinToString(",") { csvCell(it) } })
                    .joinToString("\n", postfix = "\n")
        }
    }

    private fun row(event: Event): List<String> {
        val moves = event.type in MOVE_TYPES
        val isCost = event.type == EventType.RUN_COST_RECORDED
        val status = if (isCost) event.payload["status"]?.takeIf { it in COST_STATUSES }.orEmpty() else ""
        val cost = if (isCost && status != "UNKNOWN") {
            event.payload["cost_usd"]?.toBigDecimalOrNull()?.let { usd(it) }.orEmpty()
        } else {
            ""
        }
        return listOf(
            event.id.value,
            event.at.toString(),
            event.type.name,
            actorText(event.actor),
            if (moves) column(event.payload["from"]) else "",
            if (moves) column(event.payload["to"]) else "",
            cost,
            status,
        )
    }

    private fun usd(value: BigDecimal): String = value.toPlainString()

    private fun column(value: String?): String = Column.entries.firstOrNull { it.name == value }?.name.orEmpty()

    private fun csvCell(value: String): String {
        val safe = if (value.firstOrNull() in FORMULA_PREFIXES) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
