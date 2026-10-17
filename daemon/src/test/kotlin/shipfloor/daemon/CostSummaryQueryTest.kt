package shipfloor.daemon

import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class CostSummaryQueryTest {

    private val from = Instant.parse("2026-10-16T00:00:00Z")
    private val to = Instant.parse("2026-10-17T00:00:00Z")
    private val at = Instant.parse("2026-10-16T10:00:00Z")

    private fun summary(dir: Path, limits: CostLimits, record: (CardEventStore) -> Unit): Response.CostSummaryResult {
        Database.open(dir.resolve("q.db")).use { db ->
            val store = CardEventStore(db)
            record(store)
            return CostSummaryQuery(store, limits).handle(Request.CostSummary(from, to)) as Response.CostSummaryResult
        }
    }

    private fun CardEventStore.cost(card: String, id: String, payload: Map<String, String>) = costAt(card, id, at, payload)

    private fun CardEventStore.costAt(card: String, id: String, moment: Instant, payload: Map<String, String>) =
        recordChange(
            card,
            Card.create(card, Column.CODING),
            Event(EventId(id), moment, Actor.Factory("cost"), EventType.RUN_COST_RECORDED, payload + ("run_id" to id)),
        )

    private fun CardEventStore.known(card: String, id: String, moment: Instant, stage: String?, usd: String) =
        costAt(card, id, moment, listOfNotNull("status" to "KNOWN", "cost_usd" to usd, stage?.let { "stage" to it }).toMap())

    private fun CardEventStore.unknown(card: String, id: String, moment: Instant, stage: String?, reason: String) =
        costAt(card, id, moment, listOfNotNull("status" to "UNKNOWN", "reason" to reason, stage?.let { "stage" to it }).toMap())

    /** Goes through the wire: the request line is parsed and the reply rendered, as the daemon does. */
    private fun wire(dir: Path, zone: String?, record: (CardEventStore) -> Unit): JsonObject {
        Database.open(dir.resolve("w.db")).use { db ->
            val store = CardEventStore(db)
            record(store)
            val zoneField = zone?.let { ""","zone":"$it"""" }.orEmpty()
            val request = parseRequest("""{"cmd":"cost.summary","from":"2026-10-15T00:00:00Z","to":"2026-10-18T00:00:00Z"$zoneField}""")
            val reply = Json.parseToJsonElement(render(CostSummaryQuery(store, CostLimits.NONE).handle(request))).jsonObject
            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            return reply.getValue("result").jsonObject
        }
    }

    private data class Row(val key: String, val usd: BigDecimal, val status: String)

    private fun JsonObject.rows(field: String, keyName: String): List<Row> =
        (this[field] as? JsonArray).orEmpty().map {
            val row = it.jsonObject
            Row(
                row.getValue(keyName).jsonPrimitive.content,
                BigDecimal(row.getValue("costUsd").jsonPrimitive.content),
                row.getValue("status").jsonPrimitive.content,
            )
        }

    private fun JsonObject.stages() = rows("stages", "stage")

    private fun JsonObject.days() = rows("days", "day")

    private fun seedAcrossMidnight(store: CardEventStore) {
        // 23:30 of Oct 15 and 22:00 of Oct 16 in UTC-3, but Oct 16 and Oct 17 in UTC.
        store.known("a", "e1", Instant.parse("2026-10-16T02:30:00Z"), "refine", "1.00")
        store.known("a", "e2", Instant.parse("2026-10-16T12:00:00Z"), "develop", "2.00")
        store.known("b", "e3", Instant.parse("2026-10-17T01:00:00Z"), "develop", "4.00")
    }

    @Test
    fun `no recorded cost is NONE with a zero total and nothing reached`(@TempDir dir: Path) {
        val result = summary(dir, CostLimits(BigDecimal("1"), BigDecimal("1"))) {}

        assertThat(result.status).isEqualTo(CostStatus.NONE)
        assertThat(result.totalUsd).isEqualByComparingTo("0")
        assertThat(result.cards).isEmpty()
        assertThat(result.dayReached).isFalse()
        assertThat(result.cardsReached).isEmpty()
    }

    @Test
    fun `known and unknown runs in one period make it PARTIAL`(@TempDir dir: Path) {
        val result = summary(dir, CostLimits.NONE) {
            it.cost("a", "e1", mapOf("status" to "KNOWN", "cost_usd" to "1.50"))
            it.cost("b", "e2", mapOf("status" to "UNKNOWN", "reason" to "transcript unreadable"))
        }

        assertThat(result.status).isEqualTo(CostStatus.PARTIAL)
        assertThat(result.totalUsd).isEqualByComparingTo("1.50")
        assertThat(result.cards.associate { it.card to it.status })
            .containsEntry("a", CostStatus.KNOWN).containsEntry("b", CostStatus.UNKNOWN)
    }

    @Test
    fun `a model with no price is UNKNOWN and reaches both limits`(@TempDir dir: Path) {
        val result = summary(dir, CostLimits(BigDecimal("100"), BigDecimal("100"))) {
            it.cost("a", "e1", mapOf("status" to "UNKNOWN", "reason" to "no price for models: x"))
        }

        assertThat(result.status).isEqualTo(CostStatus.UNKNOWN)
        assertThat(result.totalUsd).isEqualByComparingTo("0")
        assertThat(result.dayReached).isTrue()
        assertThat(result.cardsReached).containsExactly("a")
    }

    @Test
    fun `AC-1 totals per stage are sorted by name and an event without stage counts as unknown`(@TempDir dir: Path) {
        val result = wire(dir, zone = null) {
            it.known("a", "e1", at, "develop", "1.00")
            it.known("b", "e2", at, "refine", "2.00")
            it.known("a", "e3", at, "develop", "0.50")
            it.known("c", "e4", at, null, "0.25")
        }

        val stages = result.stages()
        assertThat(stages.map { it.key }).containsExactly("develop", "refine", "unknown")
        assertThat(stages.map { it.usd }).satisfiesExactly(
            { assertThat(it).isEqualByComparingTo("1.50") },
            { assertThat(it).isEqualByComparingTo("2.00") },
            { assertThat(it).isEqualByComparingTo("0.25") },
        )
        assertThat(stages.map { it.status }).containsOnly("KNOWN")
    }

    @Test
    fun `AC-2 days are decided by the requested zone, in chronological order`(@TempDir dir: Path) {
        val result = wire(dir, zone = "America/Sao_Paulo", record = ::seedAcrossMidnight)

        val days = result.days()
        assertThat(days.map { it.key }).containsExactly("2026-10-15", "2026-10-16")
        assertThat(days.map { it.usd }).satisfiesExactly(
            { assertThat(it).isEqualByComparingTo("1.00") },
            { assertThat(it).isEqualByComparingTo("6.00") },
        )
    }

    @Test
    fun `AC-3 without zone the days are computed in UTC`(@TempDir dir: Path) {
        val result = wire(dir, zone = null, record = ::seedAcrossMidnight)

        val days = result.days()
        assertThat(days.map { it.key }).containsExactly("2026-10-16", "2026-10-17")
        assertThat(days.map { it.usd }).satisfiesExactly(
            { assertThat(it).isEqualByComparingTo("3.00") },
            { assertThat(it).isEqualByComparingTo("4.00") },
        )
    }

    @Test
    fun `AC-3 an invalid zone is rejected with a message that says why`() {
        val request = parseRequest("""{"cmd":"cost.summary","from":"2026-10-16T00:00:00Z","to":"2026-10-17T00:00:00Z","zone":"Mars/Base"}""")

        assertThat(request).isEqualTo(Request.Rejected("invalid zone"))
    }

    @Test
    fun `AC-4 stage and day totals use the same sums and statuses as the total`(@TempDir dir: Path) {
        val result = wire(dir, zone = null) {
            it.known("a", "e1", Instant.parse("2026-10-16T10:00:00Z"), "refine", "1.00")
            it.unknown("a", "e2", Instant.parse("2026-10-16T11:00:00Z"), "refine", "transcript unreadable")
            it.known("b", "e3", Instant.parse("2026-10-17T10:00:00Z"), "develop", "2.00")
            it.unknown("b", "e4", Instant.parse("2026-10-17T11:00:00Z"), "validate", "no price for models: x")
        }

        val total = BigDecimal(result.getValue("totalUsd").jsonPrimitive.content)
        assertThat(result.getValue("status").jsonPrimitive.content).isEqualTo("PARTIAL")
        val stages = result.stages().associateBy { it.key }
        assertThat(stages.getValue("develop").status).isEqualTo("KNOWN")
        assertThat(stages.getValue("refine").status).isEqualTo("PARTIAL")
        assertThat(stages.getValue("refine").usd).isEqualByComparingTo("1.00")
        assertThat(stages.getValue("validate").status).isEqualTo("UNKNOWN")
        val days = result.days().associateBy { it.key }
        assertThat(days.getValue("2026-10-16").status).isEqualTo("PARTIAL")
        assertThat(days.getValue("2026-10-17").status).isEqualTo("PARTIAL")
        assertThat(result.stages().fold(BigDecimal.ZERO) { sum, row -> sum + row.usd }).isEqualByComparingTo(total)
        assertThat(result.days().fold(BigDecimal.ZERO) { sum, row -> sum + row.usd }).isEqualByComparingTo(total)
        assertThat(total).isEqualByComparingTo("3.00")
    }

    @Test
    fun `AC-4 with nothing recorded there are no stages and no days`(@TempDir dir: Path) {
        val result = wire(dir, zone = null) {}

        assertThat(result.getValue("stages").jsonArray).isEmpty()
        assertThat(result.getValue("days").jsonArray).isEmpty()
    }
}
