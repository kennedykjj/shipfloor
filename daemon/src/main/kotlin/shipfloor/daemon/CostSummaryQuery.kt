package shipfloor.daemon

import java.time.ZoneId
import shipfloor.core.Event
import shipfloor.store.CardEventStore

/**
 * Read-only `cost.summary`: sums the recorded run costs of a period with the same calculation as [CostBudget]
 * and says which totals reached the limits. [limitsOf] is read on every request, as the limits can change.
 */
class CostSummaryQuery(private val store: CardEventStore, private val limitsOf: () -> CostLimits) {

    constructor(store: CardEventStore, limits: CostLimits) : this(store, { limits })

    fun handle(request: Request): Response {
        if (request !is Request.CostSummary) return Response.Error("not a cost summary request")
        val limits = limitsOf()
        val events = store.costEventsBetween(request.from, request.to)
        val byCard = events
            .groupBy({ it.cardId }, { it.event })
            .toSortedMap()
        val spents = byCard.mapValues { (_, cardEvents) -> CostSums.sum(cardEvents) }
        val total = CostSums.total(spents.values)
        return Response.CostSummaryResult(
            totalUsd = total.usd,
            status = statusOf(byCard.values.flatten()),
            cards = byCard.map { (card, cardEvents) -> CardCost(card, spents.getValue(card).usd, statusOf(cardEvents)) },
            limits = limits,
            dayReached = total.reached(limits.perDayUsd),
            cardsReached = spents.filterValues { it.reached(limits.perCardUsd) }.keys.toList(),
            stages = events.map { it.event }
                .groupBy { it.payload["stage"] ?: UNKNOWN_STAGE }
                .toSortedMap()
                .map { (stage, stageEvents) -> StageCost(stage, CostSums.sum(stageEvents).usd, statusOf(stageEvents)) },
            days = events.map { it.event }
                .groupBy { dayOf(it, request.zone) }
                .toSortedMap()
                .map { (day, dayEvents) -> DayCost(day, CostSums.sum(dayEvents).usd, statusOf(dayEvents)) },
        )
    }

    private fun dayOf(event: Event, zone: ZoneId): String = event.at.atZone(zone).toLocalDate().toString()

    private fun statusOf(events: List<Event>): CostStatus {
        val statuses = events.map { event -> CostStatus.entries.firstOrNull { it.name == event.payload["status"] } ?: CostStatus.UNKNOWN }.toSet()
        return when {
            statuses.isEmpty() -> CostStatus.NONE
            statuses.size == 1 -> statuses.single()
            else -> CostStatus.PARTIAL
        }
    }

    private companion object {
        const val UNKNOWN_STAGE = "unknown"
    }
}
