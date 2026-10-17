package shipfloor.core

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON form of an [Event]: {"id", "at", "actor": {"kind", ...}, "type", "payload"}. Pure: strings only, no IO. */
object EventJson {
    private val json = Json { classDiscriminator = "kind" }

    fun encode(event: Event): String = json.encodeToString(EventRecord.serializer(), EventRecord.of(event))

    fun decode(text: String): Event {
        val record = try {
            json.decodeFromString(EventRecord.serializer(), text)
        } catch (e: MissingFieldException) {
            throw InvalidEventJsonException("event JSON misses fields ${e.missingFields}")
        } catch (e: SerializationException) {
            // The library's message quotes the input, which may carry payload: keep only the error kind.
            throw InvalidEventJsonException("event JSON is invalid: ${e.javaClass.simpleName}")
        }
        return record.toEvent()
    }

    /** Wire shape of an event; the type travels as a string so an unknown one fails with its name. */
    @Serializable
    private data class EventRecord(
        val id: String,
        val at: String,
        val actor: Actor,
        val type: String,
        val payload: Map<String, String>,
    ) {
        fun toEvent(): Event = Event(
            id = EventId(id),
            at = parseInstant(),
            actor = actor,
            type = EventType.entries.firstOrNull { it.name == type } ?: throw UnknownEventTypeException(type, id),
            payload = payload,
        )

        private fun parseInstant(): Instant = try {
            Instant.parse(at)
        } catch (e: DateTimeParseException) {
            throw InvalidEventJsonException("event $id has an invalid instant")
        }

        companion object {
            fun of(event: Event) = EventRecord(
                id = event.id.value,
                at = event.at.toString(),
                actor = event.actor,
                type = event.type.name,
                payload = event.payload,
            )
        }
    }
}
