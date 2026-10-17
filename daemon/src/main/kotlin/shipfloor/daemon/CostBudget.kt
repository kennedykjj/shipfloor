package shipfloor.daemon

import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore

/** Decides whether the dispatcher may start a run for a card. */
fun interface LaunchGuard {
    fun allows(cardId: String): Boolean

    /** True when no run may start today, whatever the card. */
    fun dayLimitReached(): Boolean = false

    companion object {
        val Open: LaunchGuard = LaunchGuard { true }
    }
}

/** Cost ceilings in USD; null means no limit. */
data class CostLimits(val perCardUsd: BigDecimal?, val perDayUsd: BigDecimal?) {
    companion object {
        val NONE = CostLimits(null, null)
    }
}

/** Blocks cards and stops the day when the recorded run costs reach the limits. Fails closed on unpriced models. */
class CostBudget(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val limits: CostLimits,
    private val clock: Clock,
    private val zone: ZoneId,
    private val log: DaemonLog = StderrDaemonLog,
) : LaunchGuard {

    fun onRunFinished(cardId: String) {
        val limit = limits.perCardUsd ?: return
        if (isBlocked(cardId)) return
        val card = store.card(cardId) ?: return
        val costs = store.events(cardId).filter { it.type == EventType.RUN_COST_RECORDED }
        val spent = sum(cardId, costs, logZero = true)
        val reason = when {
            spent.unpriced -> "unpriced_model"
            spent.reached(limit) -> "card_cost_limit"
            else -> return
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("cost"),
            type = EventType.CARD_BLOCKED,
            payload = mapOf(
                "reason" to reason,
                "spent_usd" to spent.usd.toPlainString(),
                "limit_usd" to limit.toPlainString(),
                "estimate" to "true",
            ),
        )
        recorder.record(cardId, card, event)
        log.log("card_blocked_by_cost", mapOf("card" to cardId, "reason" to reason))
    }

    fun isBlocked(cardId: String): Boolean {
        val lastBlock = store.lastSeq(cardId, EventType.CARD_BLOCKED) ?: return false
        val lastOwnerMove = store.lastSeq(cardId, EventType.CARD_MOVED, Actor.Owner) ?: 0L
        val lastOwnerUnblock = store.lastSeq(cardId, EventType.CARD_UNBLOCKED, Actor.Owner) ?: 0L
        return maxOf(lastOwnerMove, lastOwnerUnblock) < lastBlock
    }

    override fun dayLimitReached(): Boolean {
        val limit = limits.perDayUsd ?: return false
        val today = LocalDate.now(clock.withZone(zone))
        val from = today.atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        val byCard = store.costEventsBetween(from, to)
            .groupBy({ it.cardId }, { it.event })
        return CostSums.total(byCard.map { (cardId, events) -> sum(cardId, events, logZero = false) }).reached(limit)
    }

    override fun allows(cardId: String): Boolean = !isBlocked(cardId)

    private fun sum(cardId: String, costs: List<Event>, logZero: Boolean): Spent =
        CostSums.sum(costs) { event ->
            if (logZero) {
                log.log(
                    "run_cost_unknown_counted_zero",
                    mapOf("card" to cardId, "run" to (event.payload["run_id"] ?: "unknown")),
                )
            }
        }
}

/** What was spent: the known [usd] and whether any run used a model with no price (which fails closed). */
internal data class Spent(val usd: BigDecimal, val unpriced: Boolean) {
    /** True when [limit] exists and the spending reached it; an unpriced model counts as reached. */
    fun reached(limit: BigDecimal?): Boolean = limit != null && (unpriced || usd >= limit)
}

/** The single place that sums recorded run costs; shared by [CostBudget] and [CostSummaryQuery]. */
internal object CostSums {
    private const val UNPRICED_PREFIX = "no price for models"

    /** Sums the `cost_usd` of [costs]; [onUnknownZero] sees each unknown-cost run counted as zero. */
    fun sum(costs: List<Event>, onUnknownZero: (Event) -> Unit = {}): Spent {
        var total = BigDecimal.ZERO
        var unpriced = false
        for (event in costs) {
            val usd = event.payload["cost_usd"]?.let { runCatching { BigDecimal(it) }.getOrNull() }
            if (usd != null) {
                total += usd
            } else if (event.payload["status"] == "UNKNOWN") {
                if (event.payload["reason"]?.startsWith(UNPRICED_PREFIX) == true) unpriced = true else onUnknownZero(event)
            }
        }
        return Spent(total, unpriced)
    }

    fun total(spents: Collection<Spent>): Spent =
        Spent(spents.fold(BigDecimal.ZERO) { acc, s -> acc + s.usd }, spents.any { it.unpriced })
}
