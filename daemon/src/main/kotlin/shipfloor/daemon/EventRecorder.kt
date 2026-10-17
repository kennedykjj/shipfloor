package shipfloor.daemon

import shipfloor.core.Card
import shipfloor.core.Event
import shipfloor.store.CardEventStore
import shipfloor.store.StoredEvent

/**
 * The single writer of events in the daemon: stores a change and publishes it under one monitor, so
 * subscribers see every event in strict `seq` order. Takes no other lock, so it cannot deadlock.
 */
open class EventRecorder(
    private val store: CardEventStore,
    private val events: EventStream,
) {
    @Synchronized
    open fun record(cardId: String, card: Card, event: Event): StoredEvent {
        val stored = StoredEvent(store.recordChange(cardId, card, event), cardId, event)
        events.publish(stored)
        return stored
    }
}
