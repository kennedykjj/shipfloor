package shipfloor.core

import java.time.Instant

/** Thrown when an agent request is invalid or comes from a card outside the agent work columns. */
class AgentRequestRefusedException(message: String) : RuntimeException(message)

/** Pure construction of the events an agent's order tools produce. Never records file content. */
object AgentRequests {
    private const val MAX_SUMMARY_BYTES = 4096
    private val WORK_COLUMNS = setOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION)

    /** The conflict stage also runs while the autonomous delivery holds the card in Ready for Prod. */
    const val CONFLICT_ROLE = "conflict"
    private val CONFLICT_COLUMNS = WORK_COLUMNS + Column.READY_FOR_PROD

    fun reportCompletion(cardId: String, card: Card, role: String, summary: String, id: EventId, at: Instant): Event {
        requireWorkColumn(cardId, card, role)
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) throw AgentRequestRefusedException("empty summary refused for card $cardId")
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_SUMMARY_BYTES) {
            throw AgentRequestRefusedException("summary above $MAX_SUMMARY_BYTES bytes refused for card $cardId")
        }
        return Event(
            id, at, Actor.Agent(role), EventType.AGENT_REPORTED,
            mapOf("column" to card.column.name, "summary" to trimmed),
        )
    }

    fun requestWrite(cardId: String, card: Card, role: String, path: String, bytes: Int, id: EventId, at: Instant): Event {
        requireWorkColumn(cardId, card, role)
        if (path.isBlank() || path.startsWith("/") || path.any { it.isISOControl() }) {
            throw AgentRequestRefusedException("invalid write path refused for card $cardId")
        }
        if (bytes < 0) throw AgentRequestRefusedException("negative size refused for card $cardId")
        return Event(
            id, at, Actor.Agent(role), EventType.AGENT_WRITE_REQUESTED,
            mapOf("column" to card.column.name, "path" to path, "bytes" to bytes.toString()),
        )
    }

    private fun requireWorkColumn(cardId: String, card: Card, role: String) {
        val allowed = if (role == CONFLICT_ROLE) CONFLICT_COLUMNS else WORK_COLUMNS
        if (card.column !in allowed) {
            throw AgentRequestRefusedException("card $cardId is in ${card.column.name}, outside the agent work columns")
        }
    }
}
