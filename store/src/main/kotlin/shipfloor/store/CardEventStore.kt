package shipfloor.store

import java.time.Instant
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventJson
import shipfloor.core.EventType
import shipfloor.core.ExternalRef
import shipfloor.core.TextHash

/** A card together with its stored id. */
data class StoredCard(val id: String, val card: Card)

/** An event with its database-assigned sequence number and card id. */
data class StoredEvent(val seq: Long, val cardId: String, val event: Event)

/** Writes a card and its event in one transaction. The event log has no update or delete operation. */
class CardEventStore(private val database: Database) {
    private val queries = database.queries.schemaQueries

    /** Upserts the card and appends the event atomically: a failure in either write rolls back both. */
    fun recordChange(cardId: String, card: Card, event: Event): Long =
        database.queries.transactionWithResult {
            // Insert-then-update keeps the card's rowid, so cards() order stays stable across changes.
            queries.insertCardIfAbsent(id = cardId, title = card.title, column_name = card.column.name)
            queries.updateCard(title = card.title, column_name = card.column.name, id = cardId)
            // The card is the whole state: the stored references mirror its list, in order.
            queries.deleteReferences(cardId)
            card.references.forEachIndexed { index, ref ->
                queries.insertReference(
                    cardId = cardId,
                    position = index.toLong(),
                    provider = ref.provider,
                    externalId = ref.externalId,
                    url = ref.url,
                    importedAt = ref.importedAt.toString(),
                    textHash = ref.textHash.value,
                )
            }
            queries.insertEvent(
                id = event.id.value,
                cardId = cardId,
                at = event.at.toString(),
                actor = event.actor.kind(),
                type = event.type.name,
                payloadJson = EventJson.encode(event),
            )
            queries.eventSeqById(event.id.value).executeAsOne()
        }

    /** Links the card to the repo of its project; a later call replaces the link. */
    fun assignProject(cardId: String, repo: String) {
        queries.upsertProject(cardId = cardId, repo = repo)
    }

    /** The repo the card is linked to, or null when it has none (or the card does not exist). */
    fun projectOf(cardId: String): String? =
        queries.projectByCard(cardId).executeAsOneOrNull()

    /** Events with seq greater than [seq], in seq order. */
    fun eventsAfter(seq: Long): List<StoredEvent> =
        queries.eventsAfter(seq).executeAsList()
            .map { StoredEvent(it.seq, it.card_id, EventJson.decode(it.payload_json)) }

    /** The [limit] newest events, newest first; reads only those rows, never the whole history. */
    fun latestEvents(limit: Long): List<StoredEvent> =
        queries.latestEvents(limit).executeAsList()
            .map { StoredEvent(it.seq, it.card_id, EventJson.decode(it.payload_json)) }

    /** RUN_COST_RECORDED events with `from <= at < toExclusive`, in seq order; only these rows are decoded. */
    fun costEventsBetween(from: Instant, toExclusive: Instant): List<StoredEvent> =
        queries.costEventsBetween(fromAt = from.bound(), toAt = toExclusive.bound()).executeAsList()
            .map { StoredEvent(it.seq, it.card_id, EventJson.decode(it.payload_json)) }

    /** Highest seq of the card's events of [type] (and of [actor]'s kind when given), or null when none. */
    fun lastSeq(cardId: String, type: EventType, actor: Actor? = null): Long? =
        if (actor == null) {
            queries.lastSeqByType(cardId = cardId, type = type.name).executeAsOne().MAX
        } else {
            queries.lastSeqByTypeAndActor(cardId = cardId, type = type.name, actor = actor.kind()).executeAsOne().MAX
        }

    // Stored `at` is Instant.toString() ending in "Z"; without the "Z" a bound sorts just below every
    // stored text of the same instant (with or without fraction), so >= and < compare correctly as text.
    private fun Instant.bound(): String = toString().removeSuffix("Z")

    fun events(cardId: String): List<Event> =
        queries.eventPayloadsByCard(cardId).executeAsList().map { EventJson.decode(it) }

    fun card(cardId: String): Card? =
        queries.cardById(cardId).executeAsOneOrNull()
            ?.let { assemble(cardId, it.title, it.column_name) }

    /** All cards in insertion order. */
    fun cards(): List<StoredCard> =
        queries.allCards().executeAsList()
            .map { StoredCard(it.id, assemble(it.id, it.title, it.column_name)) }

    private fun assemble(cardId: String, title: String, columnName: String): Card =
        queries.referencesByCard(cardId).executeAsList().fold(Card.create(title, Column.valueOf(columnName))) { card, row ->
            card.withReference(
                ExternalRef(
                    provider = row.provider,
                    externalId = row.external_id,
                    url = row.url,
                    importedAt = Instant.parse(row.imported_at),
                    textHash = TextHash(row.text_hash),
                ),
            )
        }

    private fun Actor.kind(): String = when (this) {
        Actor.Owner -> "owner"
        is Actor.Agent -> "agent"
        is Actor.Adapter -> "adapter"
        is Actor.Factory -> "factory"
    }
}
