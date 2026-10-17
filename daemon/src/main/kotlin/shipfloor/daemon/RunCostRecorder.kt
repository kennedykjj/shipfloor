package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.SessionResult
import shipfloor.store.CardEventStore

/** Computes the cost of a finished run from its transcript and records it as a RUN_COST_RECORDED event. */
class RunCostRecorder(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val prices: PriceTable,
    private val clock: Clock,
    private val log: DaemonLog = StderrDaemonLog,
) {

    /** Never throws on a bad transcript: it becomes an UNKNOWN cost with a reason. */
    fun record(cardId: String, runId: String, runtime: String, stage: String, model: String, result: SessionResult) {
        val card = store.card(cardId)
        if (card == null) {
            log.log("run_cost_skipped", mapOf("card" to cardId, "run" to runId, "reason" to "card_not_found"))
            return
        }
        val usage: Map<String, TokenCounts>
        val cost: RunCost
        try {
            usage = TranscriptParsers.parse(runtime, result.transcriptPath, model)
            cost = if (usage.isEmpty()) {
                RunCost.Unknown("transcript empty or malformed")
            } else {
                RunCostCalculator.cost(usage, prices)
            }
        } catch (e: TranscriptUnreadableException) {
            record(cardId, card, runId, runtime, stage, emptyMap(), RunCost.Unknown(e.message ?: "transcript unreadable"))
            return
        }
        record(cardId, card, runId, runtime, stage, usage, cost)
    }

    private fun record(
        cardId: String,
        card: Card,
        runId: String,
        runtime: String,
        stage: String,
        usage: Map<String, TokenCounts>,
        cost: RunCost,
    ) {
        val payload = buildMap {
            put("run_id", runId)
            put("stage", stage)
            put("runtime", runtime)
            put("estimate", "true")
            if (usage.isNotEmpty()) put("tokens", tokens(usage))
            when (cost) {
                is RunCost.Known -> {
                    put("status", "KNOWN")
                    put("cost_usd", cost.usd.stripTrailingZeros().toPlainString())
                }
                is RunCost.Partial -> {
                    put("status", "PARTIAL")
                    put("cost_usd", cost.usd.stripTrailingZeros().toPlainString())
                    put("unpriced_models", cost.unpricedModels.joinToString(","))
                }
                is RunCost.Unknown -> {
                    put("status", "UNKNOWN")
                    put("reason", cost.reason)
                }
            }
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("cost"),
            type = EventType.RUN_COST_RECORDED,
            payload = payload,
        )
        recorder.record(cardId, card, event)
        log.log("run_cost_recorded", mapOf("card" to cardId, "run" to runId, "status" to requireNotNull(payload["status"])))
    }

    private fun tokens(usage: Map<String, TokenCounts>): String =
        usage.entries.joinToString(";") { (model, t) ->
            "$model:${t.input}/${t.output}/${t.cacheRead}/${t.cacheWrite5m}/${t.cacheWrite1h}"
        }
}
