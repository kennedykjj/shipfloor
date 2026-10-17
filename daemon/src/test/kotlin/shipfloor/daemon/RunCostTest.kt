package shipfloor.daemon

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.SessionResult
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.StoredCard

object RunCostFixtures {
    val pricesYaml = """
        models:
          model-a:
            input: 3.00
            output: 15.00
            cache_read: 0.30
            cache_write_5m: 3.75
            cache_write_1h: 6.00
          model-b:
            input: 2.00
            output: 8.00
            cache_read: 0.50
    """.trimIndent()

    fun prices(): PriceTable = PriceTable.parse(pricesYaml)

    fun claudeLine(id: String, model: String, input: Long, output: Long, cacheRead: Long, write5m: Long, write1h: Long): String =
        """{"type":"assistant","message":{"id":"$id","model":"$model","usage":{"input_tokens":$input,"output_tokens":$output,""" +
            """"cache_read_input_tokens":$cacheRead,"cache_creation":{"ephemeral_5m_input_tokens":$write5m,"ephemeral_1h_input_tokens":$write1h}}}}"""

    fun codexTurn(input: Long, cached: Long, output: Long): String =
        """{"type":"turn.completed","usage":{"input_tokens":$input,"cached_input_tokens":$cached,"output_tokens":$output}}"""
}

class RunCostTest {
    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: RunCostRecorder

    @BeforeEach
    fun setUp() {
        database = Database.open(dir.resolve("cost.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = RunCostRecorder(store, EventRecorder(store, stream), RunCostFixtures.prices(), clock)
        RunPoolFixtures.seed(store, "c1")
    }

    @AfterEach
    fun tearDown() {
        stream.close()
        database.close()
    }

    private fun transcript(vararg lines: String): Path {
        val file = Files.createTempFile(dir, "transcript", ".jsonl")
        Files.write(file, lines.toList())
        return file
    }

    /** Runs the recorder and returns the payload of the single cost event it wrote. */
    private fun recordCost(runtime: String, model: String, result: SessionResult): Map<String, String> {
        val baseline = store.eventsAfter(0).maxOf { it.seq }
        recorder.record("c1", "run-1", runtime, "develop", model, result)
        val written = store.eventsAfter(baseline)
        assertThat(written).hasSize(1)
        assertThat(written.single().event.type.name).isEqualTo("RUN_COST_RECORDED")
        return written.single().event.payload
    }

    private fun exited(path: Path): SessionResult = SessionResult.Exited(0, path)

    @Test
    fun `AC-1 claude transcript cost comes from its tokens and the price table, counting a repeated message once`() {
        val line = RunCostFixtures.claudeLine("msg-1", "model-a", 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000)
        val file = transcript(line, line)

        val payload = recordCost("claude", "model-a", exited(file))

        assertThat(payload["status"]).isEqualTo("KNOWN")
        assertThat(BigDecimal(requireNotNull(payload["cost_usd"]))).isEqualByComparingTo(BigDecimal("28.05"))
        assertThat(payload["tokens"]).isEqualTo("model-a:1000000/1000000/1000000/1000000/1000000")
        assertThat(payload["runtime"]).isEqualTo("claude")
    }

    @Test
    fun `AC-1 codex transcript cost comes from its tokens with cached input priced as cache read`() {
        val file = transcript(RunCostFixtures.codexTurn(1_000_000, 400_000, 500_000))

        val payload = recordCost("codex", "model-b", exited(file))

        assertThat(payload["status"]).isEqualTo("KNOWN")
        assertThat(BigDecimal(requireNotNull(payload["cost_usd"]))).isEqualByComparingTo(BigDecimal("5.4"))
        assertThat(payload["tokens"]).isEqualTo("model-b:600000/500000/400000/0/0")
    }

    @Test
    fun `provider AC-6 a transcript by provider is Known for a priced model and Partial or Unknown naming the model otherwise, never zero`() {
        val table = PriceTable.parse(
            RunCostFixtures.pricesYaml + "\n  anthropic.claude-bedrock-v1:0:\n    input: 3.00\n    output: 15.00\n",
        )
        val priced = recordWith(table, RunCostFixtures.claudeLine("m1", "anthropic.claude-bedrock-v1:0", 1_000_000, 0, 0, 0, 0))
        val partial = recordWith(
            table,
            RunCostFixtures.claudeLine("m1", "model-a", 1_000_000, 0, 0, 0, 0),
            RunCostFixtures.claudeLine("m2", "vertex-ai/claude-unlisted@20260101", 10, 10, 0, 0, 0),
        )
        val unknown = recordWith(table, RunCostFixtures.claudeLine("m1", "proxy/corp-model-x", 10, 10, 0, 0, 0))

        assertThat(priced["status"]).isEqualTo("KNOWN")
        assertThat(BigDecimal(requireNotNull(priced["cost_usd"]))).isEqualByComparingTo(BigDecimal("3"))
        assertThat(partial["status"]).isEqualTo("PARTIAL")
        assertThat(partial["unpriced_models"]).contains("vertex-ai/claude-unlisted@20260101")
        assertThat(unknown["status"]).isEqualTo("UNKNOWN")
        assertThat(unknown).doesNotContainKey("cost_usd")
        assertThat(unknown["reason"]).contains("proxy/corp-model-x")
    }

    private fun recordWith(table: PriceTable, vararg lines: String): Map<String, String> {
        val own = RunCostRecorder(store, EventRecorder(store, stream), table, clock)
        val baseline = store.eventsAfter(0).maxOf { it.seq }
        own.record("c1", "run-p", "claude", "develop", "model-a", exited(transcript(*lines)))
        return store.eventsAfter(baseline).single().event.payload
    }

    @Test
    fun `a price table in the V0 layout with model names at the top level is read`() {
        val v0 = """
            # US${'$'} per million tokens
            claude-opus-5-5:   {input: 4.00,  output: 20.00, cache_write_5m: 5.00,  cache_write_1h: 8.00,  cache_read: 0.20}
            gpt-5:             {input: 1.25, output: 10.00, cache_read: 0.125}
        """.trimIndent()

        val table = PriceTable.parse(v0)

        assertThat(table.priceOf("claude-opus-5-5")?.output).isEqualByComparingTo("20.00")
        assertThat(table.priceOf("gpt-5")?.cacheRead).isEqualByComparingTo("0.125")
        assertThat(table.priceOf("gpt-5")?.cacheWrite1h).isEqualByComparingTo("0")
        assertThat(table.priceOf("not-listed")).isNull()
    }

    @Test
    fun `AC-2 a model missing from the price table gives unknown, not zero, and names the model`() {
        val usage = mapOf("model-x" to TokenCounts(input = 1000, output = 1000))

        val cost = RunCostCalculator.cost(usage, RunCostFixtures.prices())

        assertThat(cost).isInstanceOf(RunCost.Unknown::class.java)
        assertThat((cost as RunCost.Unknown).reason).contains("model-x")
    }

    @Test
    fun `AC-2 the recorded event for an unpriced model is UNKNOWN with no cost value`() {
        val file = transcript(RunCostFixtures.claudeLine("msg-1", "model-x", 10, 10, 0, 0, 0))

        val payload = recordCost("claude", "model-x", exited(file))

        assertThat(payload["status"]).isEqualTo("UNKNOWN")
        assertThat(payload).doesNotContainKey("cost_usd")
        assertThat(payload["reason"]).contains("model-x")
    }

    @Test
    fun `AC-3 a transcript with only some models priced is partial and lists the unpriced ones`() {
        val usage = mapOf(
            "model-a" to TokenCounts(input = 1_000_000),
            "model-x" to TokenCounts(input = 1_000_000),
        )

        val cost = RunCostCalculator.cost(usage, RunCostFixtures.prices())

        assertThat(cost).isInstanceOf(RunCost.Partial::class.java)
        val partial = cost as RunCost.Partial
        assertThat(partial.usd).isEqualByComparingTo(BigDecimal("3"))
        assertThat(partial.unpricedModels).containsExactly("model-x")
    }

    @Test
    fun `AC-3 the recorded event for a partial transcript is PARTIAL with the subtotal and the unpriced models`() {
        val file = transcript(
            RunCostFixtures.claudeLine("msg-1", "model-a", 1_000_000, 0, 0, 0, 0),
            RunCostFixtures.claudeLine("msg-2", "model-x", 500, 500, 0, 0, 0),
        )

        val payload = recordCost("claude", "model-a", exited(file))

        assertThat(payload["status"]).isEqualTo("PARTIAL")
        assertThat(BigDecimal(requireNotNull(payload["cost_usd"]))).isEqualByComparingTo(BigDecimal("3"))
        assertThat(payload["unpriced_models"]).contains("model-x").doesNotContain("model-a")
    }

    @Test
    fun `AC-4 the cost event is recorded on the card with the cost actor, run id, stage and estimate label`() {
        val file = transcript(RunCostFixtures.claudeLine("msg-1", "model-a", 1000, 1000, 0, 0, 0))
        val baseline = store.eventsAfter(0).maxOf { it.seq }

        recorder.record("c1", "run-9", "claude", "develop", "model-a", exited(file))

        val stored = store.eventsAfter(baseline).single()
        assertThat(stored.cardId).isEqualTo("c1")
        assertThat(stored.event.type.name).isEqualTo("RUN_COST_RECORDED")
        assertThat(stored.event.actor).isEqualTo(Actor.Factory("cost"))
        assertThat(stored.event.at).isEqualTo(now)
        assertThat(stored.event.payload).containsEntry("run_id", "run-9").containsEntry("stage", "develop")
        assertThat(stored.event.payload).containsKey("tokens")
    }

    @Test
    fun `AC-5 known and partial costs are labelled as estimates`() {
        val known = recordCost("claude", "model-a", exited(transcript(RunCostFixtures.claudeLine("m1", "model-a", 10, 10, 0, 0, 0))))
        val partial = recordCost(
            "claude",
            "model-a",
            exited(
                transcript(
                    RunCostFixtures.claudeLine("m1", "model-a", 10, 10, 0, 0, 0),
                    RunCostFixtures.claudeLine("m2", "model-x", 10, 10, 0, 0, 0),
                ),
            ),
        )

        assertThat(known).containsEntry("estimate", "true")
        assertThat(partial).containsEntry("estimate", "true")
    }

    @Test
    fun `AC-6 a missing, empty or malformed transcript is UNKNOWN with a reason and never throws`() {
        val missing = dir.resolve("nope.jsonl")
        val empty = transcript()
        val malformed = transcript("this is not json", "{\"type\":\"assist")
        val hung = SessionResult.Hung(missing)

        listOf(exited(missing), exited(empty), exited(malformed), hung).forEach { result ->
            val payload = recordCost("claude", "model-a", result)

            assertThat(payload["status"]).isEqualTo("UNKNOWN")
            assertThat(payload["reason"]).isNotBlank()
            assertThat(payload).doesNotContainKey("cost_usd")
        }
        assertThat(recordCost("codex", "model-b", exited(malformed))["status"]).isEqualTo("UNKNOWN")
    }

    @Test
    fun `AC-7 cost claimed by the agent or the CLI is ignored and its text never reaches the event`() {
        val claimed = """{"type":"assistant","costUSD":999.99,"total_cost_usd":999.99,"message":{"id":"msg-1","model":"model-a",""" +
            """"content":[{"type":"text","text":"the total cost was SECRET-CLAIM-999 dollars"}],""" +
            """"usage":{"input_tokens":1000000,"output_tokens":0,"cache_read_input_tokens":0}}}"""

        val payload = recordCost("claude", "model-a", exited(transcript(claimed)))

        assertThat(BigDecimal(requireNotNull(payload["cost_usd"]))).isEqualByComparingTo(BigDecimal("3"))
        assertThat(payload.values.joinToString("|")).doesNotContain("999").doesNotContain("SECRET-CLAIM")

        val codexClaim = """{"type":"turn.completed","total_cost_usd":999.99,"usage":{"input_tokens":1000000,"cached_input_tokens":0,"output_tokens":0}}"""
        val codexPayload = recordCost("codex", "model-b", exited(transcript(codexClaim)))

        assertThat(BigDecimal(requireNotNull(codexPayload["cost_usd"]))).isEqualByComparingTo(BigDecimal("2"))
    }

    private val budgetLog = RunPoolFixtures.RecordingLog()

    private fun budget(limits: CostLimits): CostBudget =
        CostBudget(store, EventRecorder(store, stream), limits, clock, ZoneOffset.UTC, budgetLog)

    private fun costEvent(cardId: String, runId: String, usd: String, at: Instant = now) {
        val event = Event(
            EventId("cost-$runId"),
            at,
            Actor.Factory("cost"),
            EventType.RUN_COST_RECORDED,
            mapOf("run_id" to runId, "status" to "KNOWN", "cost_usd" to usd, "estimate" to "true"),
        )
        store.recordChange(cardId, Card.create("Seeded", Column.values().first()), event)
    }

    private fun move(cardId: String, actor: Actor, id: String) {
        val event = Event(EventId(id), now, actor, EventType.CARD_MOVED, mapOf("to" to Column.READY_FOR_DEV.name))
        store.recordChange(cardId, Card.create("Seeded", Column.READY_FOR_DEV), event)
    }

    private fun blockedEvents(cardId: String) =
        store.eventsAfter(0).filter { it.cardId == cardId && it.event.type.name == "CARD_BLOCKED" }

    @Test
    fun `AC-1 a card whose summed cost reaches the card limit is blocked with reason, amounts and cost actor`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("5"), perDayUsd = null))
        costEvent("c1", "run-1", "3.00")
        costEvent("c1", "run-2", "2.10")

        budget.onRunFinished("c1")
        budget.onRunFinished("c1")

        val blocked = blockedEvents("c1")
        assertThat(blocked).hasSize(1)
        val event = blocked.single().event
        assertThat(event.actor).isEqualTo(Actor.Factory("cost"))
        assertThat(event.payload).containsEntry("reason", "card_cost_limit").containsEntry("estimate", "true")
        assertThat(BigDecimal(requireNotNull(event.payload["spent_usd"]))).isEqualByComparingTo("5.10")
        assertThat(BigDecimal(requireNotNull(event.payload["limit_usd"]))).isEqualByComparingTo("5")
        assertThat(budget.isBlocked("c1")).isTrue()
    }

    @Test
    fun `AC-5 costs under both limits write no event and block nothing`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("5"), perDayUsd = BigDecimal("10")))
        costEvent("c1", "run-1", "1.00")
        val baseline = store.eventsAfter(0).maxOf { it.seq }

        budget.onRunFinished("c1")

        assertThat(store.eventsAfter(baseline)).isEmpty()
        assertThat(budget.isBlocked("c1")).isFalse()
        assertThat(budget.dayLimitReached()).isFalse()
    }

    @Test
    fun `AC-8 a run with an unpriced model blocks the card as unpriced_model when a card limit is set`() {
        val file = transcript(RunCostFixtures.claudeLine("msg-1", "model-x", 10, 10, 0, 0, 0))
        recorder.record("c1", "run-1", "claude", "develop", "model-x", exited(file))
        val budget = budget(CostLimits(perCardUsd = BigDecimal("1000"), perDayUsd = null))

        budget.onRunFinished("c1")

        assertThat(blockedEvents("c1").single().event.payload).containsEntry("reason", "unpriced_model")
        assertThat(budget.isBlocked("c1")).isTrue()
    }

    @Test
    fun `AC-8 an unpriced run reaches the day limit, and a missing transcript counts zero and is logged with the run id`() {
        recorder.record("c1", "run-1", "claude", "develop", "model-x", exited(transcript(RunCostFixtures.claudeLine("m", "model-x", 10, 10, 0, 0, 0))))
        val dayBudget = budget(CostLimits(perCardUsd = null, perDayUsd = BigDecimal("1000")))
        assertThat(dayBudget.dayLimitReached()).isTrue()

        RunPoolFixtures.seed(store, "c2")
        recorder.record("c2", "run-2", "claude", "develop", "model-a", exited(dir.resolve("nope.jsonl")))
        val cardBudget = budget(CostLimits(perCardUsd = BigDecimal("1"), perDayUsd = null))
        cardBudget.onRunFinished("c2")

        assertThat(blockedEvents("c2")).isEmpty()
        assertThat(budgetLog.lines.flatMap { it.second.values + it.first }.any { it.contains("run-2") }).isTrue()
    }

    @Test
    fun `AC-8 without limits an unpriced run changes nothing`() {
        recorder.record("c1", "run-1", "claude", "develop", "model-x", exited(transcript(RunCostFixtures.claudeLine("m", "model-x", 10, 10, 0, 0, 0))))
        val budget = budget(CostLimits.NONE)

        budget.onRunFinished("c1")

        assertThat(blockedEvents("c1")).isEmpty()
        assertThat(budget.isBlocked("c1")).isFalse()
        assertThat(budget.dayLimitReached()).isFalse()
    }

    @Test
    fun `AC-9 only an owner move after the block unblocks the card`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("1"), perDayUsd = null))
        move("c1", Actor.Owner, "owner-before")
        costEvent("c1", "run-1", "2.00")
        budget.onRunFinished("c1")
        assertThat(budget.isBlocked("c1")).isTrue()

        move("c1", Actor.Factory("scheduler"), "factory-move")
        move("c1", Actor.Agent("engineer"), "agent-move")
        assertThat(budget.isBlocked("c1")).isTrue()

        move("c1", Actor.Owner, "owner-after")
        assertThat(budget.isBlocked("c1")).isFalse()
    }

    private fun unblock(cardId: String, actor: Actor, id: String) {
        val type = EventType.entries.firstOrNull { it.name == "CARD_UNBLOCKED" }
        assertThat(type).describedAs("EventType.CARD_UNBLOCKED exists").isNotNull()
        val event = Event(EventId(id), now, actor, requireNotNull(type) { "CARD_UNBLOCKED missing" }, emptyMap())
        store.recordChange(cardId, Card.create("Seeded", Column.CODING), event)
    }

    @Test
    fun `AC-4 an owner CARD_UNBLOCKED after a cost block lets the budget release the card again`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("1"), perDayUsd = null))
        costEvent("c1", "run-1", "2.00")
        budget.onRunFinished("c1")
        assertThat(budget.isBlocked("c1")).isTrue()

        unblock("c1", Actor.Factory("scheduler"), "factory-unblock")
        unblock("c1", Actor.Agent("engineer"), "agent-unblock")
        assertThat(budget.isBlocked("c1")).isTrue()

        unblock("c1", Actor.Owner, "owner-unblock")
        assertThat(budget.isBlocked("c1")).isFalse()
    }

    @Test
    fun `AC-4 a block after the owner CARD_UNBLOCKED blocks the card again`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("1"), perDayUsd = null))
        costEvent("c1", "run-1", "2.00")
        budget.onRunFinished("c1")
        unblock("c1", Actor.Owner, "owner-unblock")
        assertThat(budget.isBlocked("c1")).isFalse()

        budget.onRunFinished("c1")

        assertThat(budget.isBlocked("c1")).isTrue()
    }

    @Test
    fun `AC-4 the day limit counts only costs inside today in the budget zone, fractions and boundaries included`() {
        costEvent("c1", "run-yesterday", "100", Instant.parse("2026-10-15T23:59:59.9Z"))
        costEvent("c1", "run-start", "3.00", Instant.parse("2026-10-16T00:00:00Z"))
        costEvent("c1", "run-frac", "2.50", Instant.parse("2026-10-16T11:59:59.5Z"))
        costEvent("c1", "run-tomorrow", "100", Instant.parse("2026-10-17T00:00:00Z"))
        costEvent("c1", "run-tomorrow-frac", "100", Instant.parse("2026-10-17T00:00:00.5Z"))

        assertThat(budget(CostLimits(perCardUsd = null, perDayUsd = BigDecimal("5.4"))).dayLimitReached()).isTrue()
        assertThat(budget(CostLimits(perCardUsd = null, perDayUsd = BigDecimal("6"))).dayLimitReached()).isFalse()
    }

    @Test
    fun `AC-4 the database the budget reads from carries the indexes behind the day and block queries`() {
        val indexes = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("cost.db")}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

        assertThat(indexes).contains("event_type_at", "event_card_type_actor")
    }

    @Test
    fun `AC-4 a card blocked many times stays blocked until an owner move after the latest block`() {
        val budget = budget(CostLimits(perCardUsd = BigDecimal("1"), perDayUsd = null))
        costEvent("c1", "run-1", "2.00")
        budget.onRunFinished("c1")
        move("c1", Actor.Owner, "owner-1")
        assertThat(budget.isBlocked("c1")).isFalse()

        costEvent("c1", "run-2", "2.00")
        budget.onRunFinished("c1")
        assertThat(budget.isBlocked("c1")).isTrue()
        assertThat(budget.isBlocked("ghost")).isFalse()

        move("c1", Actor.Owner, "owner-2")
        assertThat(budget.isBlocked("c1")).isFalse()
    }

    @Test
    fun `AC-8 a line above 1 MiB is ignored while the rest of the transcript still counts`() {
        val oversized = RunCostFixtures.claudeLine("msg-big", "model-a", 1_000_000, 0, 0, 0, 0) + " ".repeat(1024 * 1024 + 10)
        val file = transcript(oversized, RunCostFixtures.claudeLine("msg-2", "model-a", 0, 1_000_000, 0, 0, 0))

        val payload = recordCost("claude", "model-a", exited(file))

        assertThat(payload["status"]).isEqualTo("KNOWN")
        assertThat(BigDecimal(requireNotNull(payload["cost_usd"]))).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `AC-8 a transcript above 64 MiB is UNKNOWN with a reason and does not break the end of the run`() {
        val file = dir.resolve("huge.jsonl")
        val line = RunCostFixtures.claudeLine("msg-1", "model-a", 10, 10, 0, 0, 0) + "\n"
        val chunk = line.repeat(8192)
        Files.newOutputStream(file).use { out ->
            val bytes = chunk.toByteArray()
            repeat((64L * 1024 * 1024 / bytes.size).toInt() + 2) { out.write(bytes) }
        }
        assertThat(Files.size(file)).isGreaterThan(64L * 1024 * 1024)

        val payload = recordCost("claude", "model-a", exited(file))

        assertThat(payload["status"]).isEqualTo("UNKNOWN")
        assertThat(payload["reason"]).isNotBlank()
        assertThat(payload).doesNotContainKey("cost_usd")
    }

    private fun composedStart(prices: String?): ComposedDaemon.SyncLog =
        ComposedDaemon(Files.createDirectories(dir.resolve("composed-${System.nanoTime()}")), now).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
            daemon.prices(prices)
            daemon.card("c1")
            daemon.start()
            daemon.log
        }

    @Test
    fun `AC-3 a valid prices yml in the data dir lets the dispatcher start`() {
        val log = composedStart(RunCostFixtures.pricesYaml)

        assertThat(log.events("dispatcher_not_started")).isEmpty()
    }

    @Test
    fun `AC-3 a missing prices yml leaves the dispatcher stopped and names prices yml, while the daemon stays up`() {
        val log = composedStart(null)

        val stopped = log.events("dispatcher_not_started").single().second
        assertThat(requireNotNull(stopped["field"])).startsWith("prices.yml")
    }

    @Test
    fun `AC-3 an invalid prices yml leaves the dispatcher stopped, names the field and never logs its content`() {
        listOf(
            "models:\n  model-a:\n    input: NOT-A-PRICE-9931\n",
            "models:\n  model-a:\n    input: -3\n",
            "- just\n- a list 7718\n",
            "models: [unterminated 5521\n",
        ).forEach { yaml ->
            val log = composedStart(yaml)

            val stopped = log.events("dispatcher_not_started").single().second
            assertThat(requireNotNull(stopped["field"])).`as`(yaml).startsWith("prices.yml")
            assertThat(log.everything()).doesNotContain("NOT-A-PRICE-9931").doesNotContain("7718").doesNotContain("5521")
        }
    }

    // --- per-card report (#61) ---

    private fun at(minutes: Long, seconds: Long = 0): Instant = now.plusSeconds(minutes * 60 + seconds)

    private fun created(t: Instant, title: String = "Report card") =
        Event(EventId("rc-created-$t"), t, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to title))

    private fun moved(t: Instant, from: Column, to: Column) =
        Event(EventId("rc-moved-$t"), t, Actor.Factory("scheduler"), EventType.CARD_MOVED, mapOf("from" to from.name, "to" to to.name))

    private fun runCost(id: String, status: String, usd: String?) =
        Event(
            EventId("rc-cost-$id"),
            now,
            Actor.Factory("cost"),
            EventType.RUN_COST_RECORDED,
            buildMap {
                put("run_id", id)
                put("status", status)
                if (usd != null) put("cost_usd", usd)
                put("estimate", "true")
            },
        )

    private fun storedCard(id: String, column: Column, title: String = "Report card") = StoredCard(id, Card.create(title, column))

    private fun reportOf(column: Column, events: List<Event>, title: String = "Report card"): List<CardReportRow> =
        CardReport.build(listOf(storedCard("r1", column, title))) { events }

    private val doneJourney = listOf(
        created(at(0)),
        moved(at(1), Column.INBOX, Column.READY_FOR_REFINEMENT),
        moved(at(11), Column.READY_FOR_REFINEMENT, Column.CODING),
        moved(at(21), Column.CODING, Column.VALIDATION),
        moved(at(23), Column.VALIDATION, Column.CODING),
        moved(at(33), Column.CODING, Column.DONE),
    )

    /** Cells of the data row of a one-card CSV, keyed by header name. Titles in these tests carry no comma. */
    private fun csvRow(csv: String): Map<String, String> {
        val lines = csv.trim().lines()
        assertThat(lines).hasSize(2)
        val header = lines[0].split(",")
        val cells = lines[1].split(",")
        assertThat(cells).hasSameSizeAs(header)
        return header.zip(cells).toMap()
    }

    @Test
    fun `AC-1 report gives seconds per stage between consecutive events, summing repeated visits, and skips cards outside Done`() {
        val rows = CardReport.build(
            listOf(storedCard("r1", Column.DONE), storedCard("r2", Column.CODING)),
        ) { id -> if (id == "r1") doneJourney else listOf(created(at(0)), moved(at(5), Column.INBOX, Column.CODING)) }

        assertThat(rows.map { it.cardId }).containsExactly("r1")
        assertThat(rows.single().stageSeconds).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                Column.INBOX to 60L,
                Column.READY_FOR_REFINEMENT to 600L,
                Column.CODING to 1200L,
                Column.VALIDATION to 120L,
            ),
        )
    }

    @Test
    fun `AC-2 report sums the cost of the runs and labels the CSV column as an estimate`() {
        val events = doneJourney + runCost("a", "KNOWN", "1.50") + runCost("b", "KNOWN", "2.25")

        val rows = reportOf(Column.DONE, events)

        assertThat(rows.single().costUsd).isEqualByComparingTo(BigDecimal("3.75"))
        assertThat(rows.single().costStatus).isEqualTo(CostStatus.KNOWN)
        val csv = CardReport.toCsv(rows)
        val row = csvRow(csv)
        assertThat(BigDecimal(requireNotNull(row["cost_usd_estimate"]))).isEqualByComparingTo(BigDecimal("3.75"))
        assertThat(row["cost_status"]).isEqualTo("KNOWN")
        assertThat(row["card_id"]).isEqualTo("r1")
        assertThat(row["inbox"]).isEqualTo("60")
        assertThat(row["coding"]).isEqualTo("1200")
    }

    @Test
    fun `AC-3 report never shows zero for an unknown or missing cost, and flags a partial one`() {
        val unknown = reportOf(Column.DONE, doneJourney + runCost("a", "KNOWN", "1.00") + runCost("b", "UNKNOWN", null)).single()
        val none = reportOf(Column.DONE, doneJourney).single()
        val partial = reportOf(Column.DONE, doneJourney + runCost("a", "KNOWN", "1.00") + runCost("b", "PARTIAL", "0.50")).single()

        assertThat(unknown.costStatus).isEqualTo(CostStatus.UNKNOWN)
        assertThat(unknown.costUsd).isNull()
        assertThat(none.costStatus).isEqualTo(CostStatus.NONE)
        assertThat(none.costUsd).isNull()
        assertThat(partial.costStatus).isEqualTo(CostStatus.PARTIAL)
        assertThat(partial.costUsd).isEqualByComparingTo(BigDecimal("1.50"))

        val unknownCsv = csvRow(CardReport.toCsv(listOf(unknown)))
        assertThat(unknownCsv["cost_usd_estimate"]).isEmpty()
        assertThat(unknownCsv["cost_status"]).isEqualTo("UNKNOWN")
        assertThat(csvRow(CardReport.toCsv(listOf(none)))["cost_usd_estimate"]).isEmpty()
        assertThat(csvRow(CardReport.toCsv(listOf(partial)))["cost_status"]).isEqualTo("PARTIAL")
    }

    @Test
    fun `AC-4 csv header has one snake_case column per stage and quotes, escapes and neutralizes titles`() {
        val header = CardReport.toCsv(emptyList()).trim().lines().first()

        assertThat(header).isEqualTo(
            "card_id,title," + Column.entries.joinToString(",") { it.name.lowercase() } + ",cost_usd_estimate,cost_status",
        )

        val quoted = CardReport.toCsv(reportOf(Column.DONE, doneJourney, "say \"hi\"")).trim().lines()[1]
        val formula = CardReport.toCsv(reportOf(Column.DONE, doneJourney, "=SUM(A1)")).trim().lines()[1]
        assertThat(quoted).startsWith("r1,\"say \"\"hi\"\"\",")
        assertThat(formula).startsWith("r1,\"'=SUM(A1)\",")
    }
}
