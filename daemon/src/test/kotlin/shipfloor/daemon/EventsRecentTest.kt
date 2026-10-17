package shipfloor.daemon

import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class EventsRecentTest {

    private val t0 = Instant.parse("2026-10-16T12:00:00Z")

    private fun reply(dir: Path, events: Int, request: String): JsonObject {
        Database.open(dir.resolve("recent.db")).use { db ->
            val store = CardEventStore(db)
            repeat(events) { i ->
                store.recordChange(
                    "c$i",
                    Card.create("t$i", Column.INBOX),
                    Event(EventId("e$i"), t0.plusSeconds(i.toLong()), Actor.Owner, EventType.CARD_CREATED, mapOf("n" to "$i")),
                )
            }
            val commands = CardCommands(store, Clock.fixed(t0, ZoneOffset.UTC), OwnerProof.create(dir.resolve("owner.token"), SecureRandom()))
            return Json.parseToJsonElement(render(commands.handle(parseRequest(request)))).jsonObject
        }
    }

    @Test
    fun `window smaller than the history returns the newest events in seq order and is truncated`(@TempDir dir: Path) {
        val result = reply(dir, 5, """{"cmd":"events.recent","limit":3}""")

        assertThat(result.getValue("ok").jsonPrimitive.boolean).isTrue()
        assertThat(result.getValue("truncated").jsonPrimitive.boolean).isTrue()
        assertThat(result.getValue("result").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            .containsExactly("e2", "e3", "e4")
    }

    @Test
    fun `window covering the whole history is not truncated`(@TempDir dir: Path) {
        val result = reply(dir, 3, """{"cmd":"events.recent","limit":3}""")

        assertThat(result.getValue("truncated").jsonPrimitive.boolean).isFalse()
        assertThat(result.getValue("result").jsonArray).hasSize(3)
    }

    @Test
    fun `limit must be a number from 1 to 2000`() {
        assertThat(parseRequest("""{"cmd":"events.recent","limit":2000}""")).isEqualTo(Request.EventsRecent(2000))
        assertThat(parseRequest("""{"cmd":"events.recent","limit":2001}""")).isInstanceOf(Request.Rejected::class.java)
        assertThat(parseRequest("""{"cmd":"events.recent","limit":0}""")).isInstanceOf(Request.Rejected::class.java)
        assertThat(parseRequest("""{"cmd":"events.recent","limit":"5"}""")).isInstanceOf(Request.Rejected::class.java)
        assertThat(parseRequest("""{"cmd":"events.recent"}""")).isInstanceOf(Request.Rejected::class.java)
    }
}
