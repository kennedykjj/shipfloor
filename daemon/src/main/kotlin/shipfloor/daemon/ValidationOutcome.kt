package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.MoveDecision
import shipfloor.core.MoveRules
import shipfloor.store.CardEventStore

/** State of the gates the daemon ran on the card; the agent's verdict never replaces it. */
enum class GatesState { GREEN, RED }

sealed interface ValidationDecision {
    /** The card went back to Coding; [layer] names the engineer to call and [reason] is untrusted text. */
    data class Returned(val layer: String, val reason: String) : ValidationDecision

    data object Staged : ValidationDecision

    /** The agent approved and the gates are green, but the CI gave no verdict ([reason]: `ci_timeout`, `forge_failed`). */
    data class CiUnresolved(val reason: String) : ValidationDecision

    /** Approved by the agent but the gates are red: the card stays in Validation. */
    data object Held : ValidationDecision

    /** The report does not follow the verdict format: nothing is written. */
    data object Malformed : ValidationDecision
}

/** Turns the validate agent's report into a card move. Only the first line of the report decides. */
class ValidationOutcome(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val layers: Set<String>,
    private val clock: Clock,
) {

    /**
     * [ci] runs only for an approval with green gates. [head] is the commit the CI approved and [redLayer] the layer a
     * red CI returns the card to.
     */
    fun apply(
        cardId: String,
        report: String,
        gates: GatesState,
        ci: () -> CiResult,
        head: () -> String? = { null },
        redLayer: () -> String = { layers.first() },
    ): ValidationDecision {
        val card = store.card(cardId) ?: return ValidationDecision.Malformed
        val verdict = report.lineSequence().first().trim()
        return when {
            verdict == APPROVED -> approve(cardId, card, gates, ci, head, redLayer)
            verdict.startsWith(BLOCKED_PREFIX) -> block(cardId, card, verdict.removePrefix(BLOCKED_PREFIX).trim(), reason(report))
            else -> ValidationDecision.Malformed
        }
    }

    private fun approve(
        cardId: String,
        card: Card,
        gates: GatesState,
        ci: () -> CiResult,
        head: () -> String?,
        redLayer: () -> String,
    ): ValidationDecision {
        if (gates == GatesState.RED) return ValidationDecision.Held
        if (card.column != Column.VALIDATION) return ValidationDecision.Malformed
        return when (val result = ci()) {
            is CiResult.Green -> {
                val approved = mapOf("ci" to "green", "pr" to result.pr.toString()) +(head()?.let { mapOf("head" to it) } ?: emptyMap())
                if (move(cardId, card, Column.STAGED, approved)) ValidationDecision.Staged else ValidationDecision.Malformed
            }
            is CiResult.Red -> {
                val layer = redLayer()
                val reason = result.summary.ifBlank { "CI failed" }
                if (move(cardId, card, Column.CODING, mapOf("layer" to layer, "reason" to reason, "ci" to "red"))) {
                    ValidationDecision.Returned(layer, reason)
                } else {
                    ValidationDecision.Malformed
                }
            }
            CiResult.TimedOut -> ValidationDecision.CiUnresolved("ci_timeout")
            CiResult.ForgeFailed -> ValidationDecision.CiUnresolved("forge_failed")
        }
    }

    private fun block(cardId: String, card: Card, layer: String, reason: String): ValidationDecision {
        if (layer !in layers || reason.isEmpty() || reason.toByteArray(Charsets.UTF_8).size > MAX_REASON_BYTES) {
            return ValidationDecision.Malformed
        }
        if (!move(cardId, card, Column.CODING, mapOf("layer" to layer, "reason" to reason))) return ValidationDecision.Malformed
        return ValidationDecision.Returned(layer, reason)
    }

    /** Sends the card back to Coding because a build or test gate failed; false when the move is not allowed or [reason] is too long. */
    fun returnForGates(cardId: String, layer: String, reason: String): Boolean {
        val card = store.card(cardId) ?: return false
        if (layer !in layers || reason.isEmpty() || reason.toByteArray(Charsets.UTF_8).size > MAX_REASON_BYTES) return false
        return move(cardId, card, Column.CODING, mapOf("layer" to layer, "reason" to reason, "gates" to "red"))
    }

    private fun reason(report: String): String = report.lines().drop(1).joinToString("\n").trim()

    private fun move(cardId: String, card: Card, to: Column, extra: Map<String, String>): Boolean {
        if (card.column != Column.VALIDATION) return false
        val actor = Actor.Factory(STAGE)
        if (MoveRules.check(card.column, to, actor) is MoveDecision.Refused) return false
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = actor,
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to card.column.name, "to" to to.name) + extra,
        )
        recorder.record(cardId, card.movedTo(to), event)
        return true
    }

    private companion object {
        const val STAGE = "validate"
        const val APPROVED = "VERDICT: APPROVED"
        const val BLOCKED_PREFIX = "VERDICT: BLOCKED layer="
        const val MAX_REASON_BYTES = 2048
    }
}
