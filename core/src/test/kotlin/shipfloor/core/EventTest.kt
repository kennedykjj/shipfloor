package shipfloor.core

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

object EventFixtures {
    val at: Instant = Instant.parse("2026-10-15T12:00:00Z")

    fun anEvent(
        id: String = "evt-1",
        actor: Actor = Actor.Owner,
        type: EventType = EventType.CARD_CREATED,
        payload: Map<String, String> = mapOf("card" to "9"),
    ): Event = Event(id = EventId(id), at = at, actor = actor, type = type, payload = payload)
}

class EventTest {
    @Test
    fun `AC-1 event carries id, instant, actor, type and payload`() {
        val event = EventFixtures.anEvent(
            id = "evt-42",
            actor = Actor.Agent("engineer"),
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to "todo", "to" to "doing"),
        )

        assertThat(event.id).isEqualTo(EventId("evt-42"))
        assertThat(event.at).isEqualTo(EventFixtures.at)
        assertThat(event.actor).isEqualTo(Actor.Agent("engineer"))
        assertThat(event.type).isEqualTo(EventType.CARD_MOVED)
        assertThat(event.payload).containsExactlyInAnyOrderEntriesOf(mapOf("from" to "todo", "to" to "doing"))
    }

    @Test
    fun `AC-2 every actor kind survives a JSON round trip`() {
        val actors = listOf(
            Actor.Owner,
            Actor.Agent("engineer"),
            Actor.Adapter("github"),
            Actor.Factory("scheduler"),
        )

        actors.forEach { actor ->
            val event = EventFixtures.anEvent(actor = actor, type = EventType.OWNER_APPROVED)

            val decoded = EventJson.decode(EventJson.encode(event))

            assertThat(decoded).isEqualTo(event)
            assertThat(decoded.actor).isEqualTo(actor)
        }
    }

    @Test
    fun `a lock released event survives a JSON round trip`() {
        val event = EventFixtures.anEvent(
            actor = Actor.Factory("scheduler"),
            type = EventType.LOCK_RELEASED,
            payload = mapOf("card" to "9"),
        )

        val decoded = EventJson.decode(EventJson.encode(event))

        assertThat(decoded).isEqualTo(event)
        assertThat(decoded.type).isEqualTo(EventType.LOCK_RELEASED)
    }

    @Test
    fun `AC-3 reading an unknown event type fails naming the type`() {
        val json = EventJson.encode(EventFixtures.anEvent(type = EventType.CARD_CREATED))
            .replace("CARD_CREATED", "TIME_TRAVEL")

        val error = assertThrows<UnknownEventTypeException> { EventJson.decode(json) }

        assertThat(error).hasMessageContaining("TIME_TRAVEL")
    }

    @Test
    fun `AC-3 reading an unknown event type names the event id`() {
        val json = EventJson.encode(EventFixtures.anEvent(id = "evt-7", type = EventType.CARD_CREATED))
            .replace("CARD_CREATED", "TIME_TRAVEL")

        val error = assertThrows<UnknownEventTypeException> { EventJson.decode(json) }

        assertThat(error).hasMessageContaining("evt-7")
    }

    @Test
    fun `AC-1 a gate result event round trips through JSON with its payload`() {
        val type = EventType.entries.firstOrNull { it.name == "GATE_FINISHED" }
        assertThat(type).isNotNull()
        val event = EventFixtures.anEvent(
            actor = Actor.Factory("gate"),
            type = requireNotNull(type),
            payload = mapOf("gate" to "lint", "verdict" to "FAIL", "reason" to "timeout", "summary" to "boom"),
        )

        val decoded = EventJson.decode(EventJson.encode(event))

        assertThat(decoded).isEqualTo(event)
    }

    @Test
    fun `AC-4 a run cost event round trips through JSON with its payload`() {
        val type = EventType.entries.firstOrNull { it.name == "RUN_COST_RECORDED" }
        assertThat(type).isNotNull()
        val event = EventFixtures.anEvent(
            actor = Actor.Factory("cost"),
            type = requireNotNull(type),
            payload = mapOf("run_id" to "run-1", "status" to "KNOWN", "cost_usd" to "1.25", "estimate" to "true"),
        )

        val decoded = EventJson.decode(EventJson.encode(event))

        assertThat(decoded).isEqualTo(event)
    }

    @Test
    fun `AC-1 a card blocked event round trips through JSON with its payload`() {
        val type = EventType.entries.firstOrNull { it.name == "CARD_BLOCKED" }
        assertThat(type).isNotNull()
        val event = EventFixtures.anEvent(
            actor = Actor.Factory("cost"),
            type = requireNotNull(type),
            payload = mapOf("reason" to "card_cost_limit", "spent_usd" to "5.10", "limit_usd" to "5", "estimate" to "true"),
        )

        val decoded = EventJson.decode(EventJson.encode(event))

        assertThat(decoded).isEqualTo(event)
    }

    @Test
    fun `reading an event without a field fails naming the field`() {
        val json = EventJson.encode(EventFixtures.anEvent()).replace("\"id\":\"evt-1\",", "")

        val error = assertThrows<InvalidEventJsonException> { EventJson.decode(json) }

        assertThat(error).hasMessageContaining("id")
    }

    @Test
    fun `reading an unknown actor kind fails without quoting the input`() {
        val json = EventJson.encode(EventFixtures.anEvent(payload = mapOf("secret" to "s3cr3t")))
            .replace("\"owner\"", "\"alien\"")

        val error = assertThrows<InvalidEventJsonException> { EventJson.decode(json) }

        assertThat(error.message).doesNotContain("s3cr3t")
    }
}
