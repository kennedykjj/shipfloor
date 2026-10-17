package shipfloor.daemon

import java.math.BigDecimal
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

class ProtocolTest {

    @Test
    fun `valid ping is parsed`() {
        assertThat(parseRequest("""{"cmd":"ping"}""")).isEqualTo(Request.Ping)
    }

    @Test
    fun `card commands parse actors and columns and reject bad ones`() {
        assertThat(parseRequest("""{"cmd":"card.create","actor":"factory:gate","title":"T"}"""))
            .isEqualTo(Request.CardCreate(Actor.Factory("gate"), "T"))
        assertThat(parseRequest("""{"cmd":"card.move","actor":"agent:dev","id":"x","to":"CODING"}"""))
            .isEqualTo(Request.CardMove(Actor.Agent("dev"), "x", Column.CODING))
        assertThat(parseRequest("""{"cmd":"card.move","actor":"owner","id":"x","to":"NOWHERE"}"""))
            .isEqualTo(Request.Rejected("unknown column"))
        assertThat(parseRequest("""{"cmd":"card.create","actor":"root","title":"T"}"""))
            .isEqualTo(Request.Rejected("missing or invalid actor"))
        assertThat(parseRequest("""{"cmd":"card.create","title":"T"}"""))
            .isEqualTo(Request.Rejected("missing or invalid actor"))
    }

    @Test
    fun `subscribe parses an optional non-negative numeric after`() {
        assertThat(parseRequest("""{"cmd":"events.subscribe"}""")).isEqualTo(Request.Subscribe(null))
        assertThat(parseRequest("""{"cmd":"events.subscribe","after":7}""")).isEqualTo(Request.Subscribe(7))
        assertThat(parseRequest("""{"cmd":"events.subscribe","after":"7"}""")).isEqualTo(Request.Rejected("invalid after"))
        assertThat(parseRequest("""{"cmd":"events.subscribe","after":-1}""")).isEqualTo(Request.Rejected("invalid after"))
        assertThat(parseRequest("""{"cmd":"events.subscribe","after":1.5}""")).isEqualTo(Request.Rejected("invalid after"))
    }

    @Test
    fun `stream error is a line with only the error`() {
        assertThat(renderStreamError("subscriber too slow")).isEqualTo("""{"error":"subscriber too slow"}""")
    }

    @Test
    fun `AC-1 proof is parsed on create and move and never printed by toString`() {
        val create = parseRequest("""{"cmd":"card.create","actor":"owner","title":"T","proof":"s3cr3tvalue"}""")
        val move = parseRequest("""{"cmd":"card.move","actor":"owner","id":"x","to":"CODING","proof":"s3cr3tvalue"}""")

        assertThat((create as Request.CardCreate).proof).isEqualTo("s3cr3tvalue")
        assertThat((move as Request.CardMove).proof).isEqualTo("s3cr3tvalue")
        assertThat(create.toString()).doesNotContain("s3cr3tvalue")
        assertThat(move.toString()).doesNotContain("s3cr3tvalue")
        assertThat((parseRequest("""{"cmd":"card.create","actor":"owner","title":"T"}""") as Request.CardCreate).proof)
            .isNull()
    }

    @Test
    fun `invalid json is rejected without echoing the input`() {
        val request = parseRequest("secret-not-json")

        assertThat(request).isEqualTo(Request.Rejected("invalid JSON"))
    }

    @Test
    fun `non object json is rejected`() {
        assertThat(parseRequest("[1,2]")).isEqualTo(Request.Rejected("message must be a JSON object"))
    }

    @Test
    fun `missing cmd is rejected`() {
        assertThat(parseRequest("""{"foo":1}""")).isEqualTo(Request.Rejected("missing cmd"))
    }

    @Test
    fun `non text cmd is rejected`() {
        assertThat(parseRequest("""{"cmd":5}""")).isEqualTo(Request.Rejected("missing cmd"))
    }

    @Test
    fun `unknown cmd is rejected`() {
        assertThat(parseRequest("""{"cmd":"explode"}""")).isEqualTo(Request.Rejected("unknown cmd"))
    }

    @Test
    fun `AC-2 factory pulse is recognized without any field and takes no parameter that matters`() {
        val request = parseRequest("""{"cmd":"factory.pulse"}""")

        assertThat(request).isNotInstanceOf(Request.Rejected::class.java)
        assertThat(request.javaClass.simpleName).isEqualTo("FactoryPulse")
        assertThat(parseRequest("""{"cmd":"factory.pulse","path":"/etc/passwd","id":"x"}""")).isEqualTo(request)
        assertThat(parseRequest("""["factory.pulse"]""")).isEqualTo(Request.Rejected("message must be a JSON object"))
        assertThat(parseRequest("""{"pulse":true}""")).isEqualTo(Request.Rejected("missing cmd"))
    }

    @Test
    fun `pong renders as a single json line`() {
        assertThat(render(Response.Pong)).isEqualTo("""{"ok":true,"result":"pong"}""")
    }

    @Test
    fun `AC-4 report csv request returns one csv line per Done card and nothing for the others`(@TempDir dir: Path) {
        Database.open(dir.resolve("report.db")).use { db ->
            val store = CardEventStore(db)
            val t0 = Instant.parse("2026-10-16T10:00:00Z")
            fun record(id: String, title: String, column: Column, vararg events: Event) =
                events.forEach { store.recordChange(id, Card.create(title, column), it) }
            record(
                "done-1", "Shipped", Column.DONE,
                Event(EventId("e1"), t0, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Shipped")),
                Event(EventId("e2"), t0.plusSeconds(90), Actor.Owner, EventType.CARD_MOVED, mapOf("from" to "INBOX", "to" to "DONE")),
            )
            record(
                "open-1", "Still coding", Column.CODING,
                Event(EventId("e3"), t0, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Still coding")),
            )
            val commands = CardCommands(store, Clock.fixed(t0, ZoneOffset.UTC), OwnerProof.create(dir.resolve("owner.token"), SecureRandom()))

            val reply = Json.parseToJsonElement(render(commands.handle(parseRequest("""{"cmd":"report.csv"}""")))).jsonObject
            commands.events.close()

            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            val lines = reply.getValue("csv").jsonPrimitive.content.trim().lines()
            assertThat(lines).hasSize(2)
            assertThat(lines[0]).startsWith("card_id,title,").endsWith("cost_usd_estimate,cost_status")
            assertThat(lines[1]).startsWith("done-1,\"Shipped\",90,")
            assertThat(lines[1].split(",")).hasSameSizeAs(lines[0].split(","))
        }
    }

    private val auditColumns = listOf("event_id", "at", "type", "actor", "from", "to", "cost_usd_estimate", "cost_status")

    private fun auditEvents(t0: Instant) = listOf(
        Event(EventId("e1"), t0, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Secret issue text")),
        Event(EventId("e2"), t0.plusSeconds(60), Actor.Agent("dev"), EventType.RUN_COST_RECORDED, mapOf("cost_usd" to "0.0421", "status" to "KNOWN", "note" to "free text")),
        Event(EventId("e3"), t0.plusSeconds(120), Actor.Factory("gate"), EventType.RUN_COST_RECORDED, mapOf("status" to "UNKNOWN")),
        Event(EventId("e4"), t0.plusSeconds(180), Actor.Owner, EventType.OWNER_APPROVED, mapOf("from" to "SPEC_REVIEW", "to" to "READY_FOR_DEV")),
        Event(EventId("e5"), t0.plusSeconds(240), Actor.Owner, EventType.CARD_MOVED, mapOf("from" to "READY_FOR_DEV", "to" to "free text, not a column")),
    )

    private fun withAudit(dir: Path, events: List<Event>, block: (CardEventStore, CardCommands, (String, String) -> JsonObject) -> Unit) {
        Database.open(dir.resolve("audit.db")).use { db ->
            val store = CardEventStore(db)
            events.forEach { store.recordChange("c1", Card.create("T", Column.CODING), it) }
            val t0 = Instant.parse("2026-10-16T10:00:00Z")
            val commands = CardCommands(store, Clock.fixed(t0, ZoneOffset.UTC), OwnerProof.create(dir.resolve("owner.token"), SecureRandom()))
            val export = { id: String, format: String ->
                Json.parseToJsonElement(render(commands.handle(parseRequest("""{"cmd":"audit.export","id":"$id","format":"$format"}""")))).jsonObject
            }
            try {
                block(store, commands, export)
            } finally {
                commands.events.close()
            }
        }
    }

    private fun cell(row: JsonObject, key: String): String =
        row[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()

    @Test
    fun `AC-1 AC-3 AC-4 json export has one chronological record per event with the closed columns`(@TempDir dir: Path) {
        withAudit(dir, auditEvents(Instant.parse("2026-10-16T10:00:00Z"))) { _, _, export ->
            val reply = export("c1", "json")

            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            val doc = Json.parseToJsonElement(reply.getValue("text").jsonPrimitive.content).jsonObject
            assertThat(doc.getValue("card").jsonPrimitive.content).isEqualTo("c1")
            val rows = doc.getValue("events").jsonArray.map { it.jsonObject }
            assertThat(rows.map { cell(it, "event_id") }).containsExactly("e1", "e2", "e3", "e4", "e5")
            assertThat(rows.map { cell(it, "actor") }).containsExactly("owner", "agent:dev", "factory:gate", "owner", "owner")
            assertThat(rows.map { cell(it, "type") }.first()).isEqualTo("CARD_CREATED")
            assertThat(rows.all { it.keys.toList() == auditColumns }).isTrue()
            assertThat(cell(rows[0], "at")).isEqualTo("2026-10-16T10:00:00Z")
            assertThat(rows.map { cell(it, "at") }).isSorted()

            // AC-3: known cost carries value and status; UNKNOWN is empty, never zero; non-cost events carry none.
            assertThat(cell(rows[1], "cost_usd_estimate")).isEqualTo("0.0421")
            assertThat(cell(rows[1], "cost_status")).isEqualTo("KNOWN")
            assertThat(cell(rows[2], "cost_usd_estimate")).isEmpty()
            assertThat(cell(rows[2], "cost_status")).isEqualTo("UNKNOWN")
            assertThat(cell(rows[0], "cost_status")).isEmpty()
            assertThat(cell(rows[3], "cost_usd_estimate")).isEmpty()

            // AC-4: the owner approval has actor, moment and what was approved.
            assertThat(cell(rows[3], "type")).isEqualTo("OWNER_APPROVED")
            assertThat(cell(rows[3], "at")).isEqualTo("2026-10-16T10:03:00Z")
            assertThat(cell(rows[3], "from")).isEqualTo("SPEC_REVIEW")
            assertThat(cell(rows[3], "to")).isEqualTo("READY_FOR_DEV")

            // Closed field list: a destination that is not a board column and the rest of the payload do not leak.
            assertThat(cell(rows[4], "to")).isEmpty()
            assertThat(reply.getValue("text").jsonPrimitive.content).doesNotContain("Secret issue text").doesNotContain("free text")
        }
    }

    @Test
    fun `AC-2 AC-3 csv export has a header and the same rows and columns as the json`(@TempDir dir: Path) {
        withAudit(dir, auditEvents(Instant.parse("2026-10-16T10:00:00Z"))) { _, _, export ->
            val json = Json.parseToJsonElement(export("c1", "json").getValue("text").jsonPrimitive.content).jsonObject
                .getValue("events").jsonArray.map { it.jsonObject }
            val reply = export("c1", "csv")

            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            val lines = reply.getValue("text").jsonPrimitive.content.trim().lines()
            assertThat(lines[0]).isEqualTo(auditColumns.joinToString(","))
            assertThat(lines).hasSize(json.size + 1)
            lines.drop(1).zip(json).forEach { (line, row) ->
                assertThat(line.split(",", limit = auditColumns.size).map { it.removeSurrounding("\"") })
                    .isEqualTo(auditColumns.map { cell(row, it) })
            }
            assertThat(lines[3].split(",")[6]).isEmpty()
            assertThat(lines[3].split(",")[7]).isEqualTo("UNKNOWN")
        }
    }

    @Test
    fun `AC-5 csv cells starting with a formula character are neutralized`(@TempDir dir: Path) {
        val t0 = Instant.parse("2026-10-16T10:00:00Z")
        val events = listOf(
            Event(EventId("=SUM(1)"), t0, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "T")),
            Event(EventId("@evil"), t0.plusSeconds(1), Actor.Owner, EventType.AGENT_REPORTED, emptyMap()),
            Event(EventId("-1"), t0.plusSeconds(2), Actor.Owner, EventType.AGENT_REPORTED, emptyMap()),
            Event(EventId("+1"), t0.plusSeconds(3), Actor.Owner, EventType.AGENT_REPORTED, emptyMap()),
        )
        withAudit(dir, events) { _, _, export ->
            val lines = export("c1", "csv").getValue("text").jsonPrimitive.content.trim().lines()

            val cells = lines.drop(1).flatMap { line -> line.split(",").map { it.removeSurrounding("\"") } }
            assertThat(cells).noneMatch { it.startsWith("=") || it.startsWith("@") || it.startsWith("+") || it.startsWith("-") }
            val firstCells = lines.drop(1).map { it.substringBefore(",").removeSurrounding("\"") }
            assertThat(firstCells).containsExactly("'=SUM(1)", "'@evil", "'-1", "'+1")
        }
    }

    private fun recordCost(store: CardEventStore, card: String, id: String, at: Instant, usd: String) =
        store.recordChange(
            card,
            Card.create(card, Column.CODING),
            Event(
                EventId(id), at, Actor.Factory("cost"), EventType.RUN_COST_RECORDED,
                mapOf("run_id" to id, "status" to "KNOWN", "cost_usd" to usd, "estimate" to "true"),
            ),
        )

    private fun costSummary(dir: Path, limits: CostLimits): JsonObject {
        Database.open(dir.resolve("cost.db")).use { db ->
            val store = CardEventStore(db)
            recordCost(store, "a", "e1", Instant.parse("2026-10-16T12:00:00Z"), "6.00")
            recordCost(store, "b", "e2", Instant.parse("2026-10-16T13:00:00Z"), "2.00")
            // Outside the requested day: must not count.
            recordCost(store, "b", "e3", Instant.parse("2026-10-17T03:00:00Z"), "50.00")
            val request = parseRequest("""{"cmd":"cost.summary","from":"2026-10-16T03:00:00Z","to":"2026-10-17T03:00:00Z"}""")
            val reply = Json.parseToJsonElement(render(CostSummaryQuery(store, limits).handle(request))).jsonObject
            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            return reply.getValue("result").jsonObject
        }
    }

    @Test
    fun `AC-6 AC-7 unknown card and invalid format are errors with the id, and export leaves the card untouched`(@TempDir dir: Path) {
        withAudit(dir, auditEvents(Instant.parse("2026-10-16T10:00:00Z"))) { store, _, export ->
            val cardBefore = store.card("c1")
            val eventsBefore = store.events("c1")
            val seqBefore = store.eventsAfter(0).size

            val unknown = export("nope-42", "json")
            val badFormat = export("c1", "xml")
            export("c1", "json")
            export("c1", "csv")

            assertThat(unknown.getValue("ok").jsonPrimitive.boolean).isFalse()
            assertThat(unknown.getValue("error").jsonPrimitive.content).contains("nope-42")
            assertThat(unknown).doesNotContainKey("text")
            assertThat(badFormat.getValue("ok").jsonPrimitive.boolean).isFalse()
            assertThat(badFormat).doesNotContainKey("text")
            assertThat(store.card("c1")).isEqualTo(cardBefore)
            assertThat(store.events("c1")).isEqualTo(eventsBefore)
            assertThat(store.eventsAfter(0)).hasSize(seqBefore)
        }
    }

    @Test
    fun `AC-6 cost summary delivers the limits and which totals reached them`(@TempDir dir: Path) {
        val result = costSummary(dir, CostLimits(perCardUsd = BigDecimal("5.00"), perDayUsd = BigDecimal("8.00")))

        assertThat(BigDecimal(result.getValue("totalUsd").jsonPrimitive.content)).isEqualByComparingTo("8.00")
        assertThat(result.getValue("status").jsonPrimitive.content).isEqualTo("KNOWN")
        val limits = result.getValue("limits").jsonObject
        assertThat(BigDecimal(limits.getValue("perCardUsd").jsonPrimitive.content)).isEqualByComparingTo("5.00")
        assertThat(BigDecimal(limits.getValue("perDayUsd").jsonPrimitive.content)).isEqualByComparingTo("8.00")
        // The day total equals its limit: reached, as CostBudget decides.
        assertThat(result.getValue("dayReached").jsonPrimitive.boolean).isTrue()
        assertThat(result.getValue("cardsReached").jsonArray.map { it.jsonPrimitive.content }).containsExactly("a")
        val perCard = result.getValue("cards").jsonArray.associate {
            it.jsonObject.getValue("card").jsonPrimitive.content to BigDecimal(it.jsonObject.getValue("costUsd").jsonPrimitive.content)
        }
        assertThat(perCard.getValue("a")).isEqualByComparingTo("6.00")
        assertThat(perCard.getValue("b")).isEqualByComparingTo("2.00")
    }

    @Test
    fun `AC-6 cost summary without limits reports null limits and nothing reached`(@TempDir dir: Path) {
        val result = costSummary(dir, CostLimits.NONE)

        val limits = result.getValue("limits").jsonObject
        assertThat(limits.getValue("perCardUsd")).isEqualTo(JsonNull)
        assertThat(limits.getValue("perDayUsd")).isEqualTo(JsonNull)
        assertThat(result.getValue("dayReached").jsonPrimitive.boolean).isFalse()
        assertThat(result.getValue("cardsReached").jsonArray).isEmpty()
        assertThat(BigDecimal(result.getValue("totalUsd").jsonPrimitive.content)).isEqualByComparingTo("8.00")
    }

    @Test
    fun `AC-6 cost summary request with an invalid instant is rejected`() {
        assertThat(parseRequest("""{"cmd":"cost.summary","from":"yesterday","to":"2026-10-17T03:00:00Z"}"""))
            .isInstanceOf(Request.Rejected::class.java)
        assertThat(parseRequest("""{"cmd":"cost.summary","from":"2026-10-16T03:00:00Z"}"""))
            .isInstanceOf(Request.Rejected::class.java)
    }

    private fun recordStay(store: CardEventStore, card: String, at: Instant, vararg moves: Pair<Column, Long>) {
        var n = 0
        fun record(type: EventType, time: Instant, payload: Map<String, String>) =
            store.recordChange(card, Card.create(card, Column.CODING), Event(EventId("$card-${n++}"), time, Actor.Owner, type, payload))
        record(EventType.CARD_CREATED, at, mapOf("title" to "T"))
        moves.forEach { (column, offset) -> record(EventType.CARD_MOVED, at.plusSeconds(offset), mapOf("to" to column.name)) }
    }

    private fun columnTimes(dir: Path, block: (CardEventStore, JsonObject) -> Unit) {
        Database.open(dir.resolve("times.db")).use { db ->
            val store = CardEventStore(db)
            val day = Instant.parse("2026-10-16T10:00:00Z")
            // c1: Refinement 600s, Spec Review 600s, Coding 600s, Validation 600s, then Done.
            recordStay(
                store, "c1", day,
                Column.REFINEMENT to 0L, Column.SPEC_REVIEW to 600L, Column.CODING to 1_200L,
                Column.VALIDATION to 1_800L, Column.DONE to 2_400L,
            )
            // c2: Coding 1200s, then Done.
            recordStay(store, "c2", day.plusSeconds(3_600), Column.CODING to 0L, Column.DONE to 1_200L)
            // c3: Coding stay that ends after the requested day: must not count. c4: still open in Staged.
            recordStay(store, "c3", Instant.parse("2026-10-17T04:00:00Z"), Column.CODING to 0L, Column.DONE to 3_600L)
            recordStay(store, "c4", day, Column.STAGED to 0L)
            val request = parseRequest("""{"cmd":"column.times","from":"2026-10-16T03:00:00Z","to":"2026-10-17T03:00:00Z"}""")
            val reply = Json.parseToJsonElement(render(ColumnTimesQuery(store).handle(request))).jsonObject
            block(store, reply)
        }
    }

    @Test
    fun `AC-5 column times request carries from and to and needs no actor or proof`() {
        val request = parseRequest("""{"cmd":"column.times","from":"2026-10-16T03:00:00Z","to":"2026-10-17T03:00:00Z"}""")

        assertThat(request).isNotInstanceOf(Request.Rejected::class.java)
        assertThat(request.toString()).contains("2026-10-16T03:00:00Z").contains("2026-10-17T03:00:00Z")
    }

    @Test
    fun `AC-5 column times request with a missing or invalid from or to is rejected`() {
        assertThat(parseRequest("""{"cmd":"column.times","to":"2026-10-17T03:00:00Z"}"""))
            .isEqualTo(Request.Rejected("missing or invalid from"))
        assertThat(parseRequest("""{"cmd":"column.times","from":"2026-10-16T03:00:00Z"}"""))
            .isEqualTo(Request.Rejected("missing or invalid to"))
        assertThat(parseRequest("""{"cmd":"column.times","from":"yesterday","to":"2026-10-17T03:00:00Z"}"""))
            .isEqualTo(Request.Rejected("missing or invalid from"))
        assertThat(parseRequest("""{"cmd":"column.times","from":"2026-10-16T03:00:00Z","to":5}"""))
            .isEqualTo(Request.Rejected("missing or invalid to"))
    }

    @Test
    fun `AC-5 column times reply lists kind, seconds and stays per column in board order`(@TempDir dir: Path) {
        columnTimes(dir) { _, reply ->
            assertThat(reply.getValue("ok").jsonPrimitive.boolean).isTrue()
            val columns = reply.getValue("result").jsonObject.getValue("columns").jsonArray.map { it.jsonObject }
            assertThat(columns.map { it.getValue("column").jsonPrimitive.content })
                .containsExactly("REFINEMENT", "SPEC_REVIEW", "CODING", "VALIDATION")
            assertThat(columns.map { it.getValue("kind").jsonPrimitive.content })
                .containsExactly("MEDIAN", "MEAN_WAIT", "MEDIAN", "MEDIAN")
            assertThat(columns.map { it.getValue("seconds").jsonPrimitive.long }).containsExactly(600L, 600L, 900L, 600L)
            assertThat(columns.map { it.getValue("stays").jsonPrimitive.int }).containsExactly(1, 1, 2, 1)
        }
    }

    @Test
    fun `AC-5 column times reads without writing anything`(@TempDir dir: Path) {
        columnTimes(dir) { store, _ ->
            val before = store.eventsAfter(0)

            val request = parseRequest("""{"cmd":"column.times","from":"2026-10-16T03:00:00Z","to":"2026-10-17T03:00:00Z"}""")
            ColumnTimesQuery(store).handle(request)

            assertThat(before).isNotEmpty()
            assertThat(store.eventsAfter(0)).isEqualTo(before)
        }
    }

    @Test
    fun `error message with quotes is escaped`() {
        val rendered = render(Response.Error("bad \"thing\""))

        assertThat(rendered).isEqualTo("""{"ok":false,"error":"bad \"thing\""}""")
    }
}
