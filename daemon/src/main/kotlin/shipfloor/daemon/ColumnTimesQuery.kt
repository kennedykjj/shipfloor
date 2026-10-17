package shipfloor.daemon

import shipfloor.core.ColumnTimes
import shipfloor.store.CardEventStore

/** Read-only `column.times`: the time per column of the stays that ended in [from, to), computed by [ColumnTimes]. */
class ColumnTimesQuery(private val store: CardEventStore) {

    fun handle(request: Request): Response {
        if (request !is Request.ColumnTimes) return Response.Error("not a column times request")
        val eventsByCard = store.cards().map { store.events(it.id) }
        return Response.ColumnTimesResult(ColumnTimes.of(eventsByCard, request.from, request.to))
    }
}
