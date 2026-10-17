package shipfloor.daemon

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.StoredEvent

class ModelStepUpTest {
    @TempDir
    lateinit var dir: Path

    private val ladder = listOf("model-1", "model-2", "model-3")
    private val now = Instant.parse("2026-10-16T10:00:00Z")
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private lateinit var stepUp: ModelStepUp

    @BeforeEach
    fun setUp() {
        database = Database.open(dir.resolve("stepup.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        stepUp = ModelStepUp(ladder, store)
        stream.addListener(stepUp::onEvent)
    }

    @AfterEach
    fun tearDown() {
        // The worker thread reads the store: stop and join it before the database and the temp directory go away.
        stepUp.close()
        stream.close()
        database.close()
    }

    private fun seed(id: String, column: Column) {
        val event = Event(EventId("seed-$id"), now, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Seeded"))
        store.recordChange(id, Card.create("Seeded", column), event)
    }

    private fun gate(cardId: String, column: Column, reason: String, verdict: String = "FAIL"): StoredEvent {
        val payload = buildMap {
            put("gate", "develop")
            put("verdict", verdict)
            if (verdict == "FAIL") put("reason", reason)
            put("summary", "s")
            put("output_file", "/tmp/x")
        }
        val event = Event(EventId(UUID.randomUUID().toString()), now, Actor.Factory("gate"), EventType.GATE_FINISHED, payload)
        return recorder.record(cardId, Card.create("Seeded", column), event)
    }

    private fun move(cardId: String, from: Column, to: Column): StoredEvent {
        val event = Event(
            EventId(UUID.randomUUID().toString()), now, Actor.Owner, EventType.CARD_MOVED,
            mapOf("from" to from.name, "to" to to.name),
        )
        return recorder.record(cardId, Card.create("Seeded", to), event)
    }

    /** Waits for the asynchronous listener: fails if [card] never reaches [model]. */
    private fun awaitModel(card: String, model: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (stepUp.modelFor(card) != model && System.nanoTime() < deadline) LockSupport.parkNanos(2_000_000L)
        assertThat(stepUp.modelFor(card)).isEqualTo(model)
    }

    /**
     * Events are handled in order, so once a sentinel card (always in Coding) has stepped up,
     * everything published before it has been handled too.
     */
    private fun drain() {
        val sentinel = "sentinel-" + UUID.randomUUID()
        seed(sentinel, Column.CODING)
        gate(sentinel, Column.CODING, "exit_code")
        awaitModel(sentinel, "model-2")
    }

    @Test
    fun `AC-5 a capacity failure in Coding moves the next run to the next model`() {
        seed("c1", Column.CODING)
        assertThat(stepUp.modelFor("c1")).isEqualTo("model-1")

        gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-2")

        gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-3")
    }

    @Test
    fun `AC-6 the last model of the ladder stays without error`() {
        seed("c1", Column.CODING)
        gate("c1", Column.CODING, "exit_code")
        gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-3")

        gate("c1", Column.CODING, "exit_code")
        drain()

        assertThat(stepUp.modelFor("c1")).isEqualTo("model-3")
    }

    @Test
    fun `AC-7 environment failures and gate failures outside Coding do not step up`() {
        seed("c1", Column.CODING)
        seed("c2", Column.VALIDATION)

        gate("c1", Column.CODING, "timeout")
        gate("c1", Column.CODING, "start_error")
        gate("c2", Column.VALIDATION, "exit_code")
        gate("c1", Column.CODING, "", verdict = "PASS")
        drain()

        assertThat(stepUp.modelFor("c1")).isEqualTo("model-1")
        assertThat(stepUp.modelFor("c2")).isEqualTo("model-1")
    }

    @Test
    fun `AC-8 the step belongs to one card and resets when the card leaves Coding`() {
        seed("c1", Column.CODING)
        seed("c2", Column.CODING)

        gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-2")
        assertThat(stepUp.modelFor("c2")).isEqualTo("model-1")

        move("c1", Column.CODING, Column.VALIDATION)
        awaitModel("c1", "model-1")
    }

    @Test
    fun `AC-8 a move that does not leave Coding keeps the step`() {
        seed("c1", Column.CODING)
        gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-2")

        move("c1", Column.READY_FOR_DEV, Column.CODING)
        drain()

        assertThat(stepUp.modelFor("c1")).isEqualTo("model-2")
    }

    @Test
    fun `AC-9 steps up only for exit_code and tests_failed`() {
        seed("up-tests", Column.CODING)
        gate("up-tests", Column.CODING, "tests_failed")
        awaitModel("up-tests", "model-2")

        val noStep = listOf(
            "assertion_edited", "setup_error", "no_assertion_failure", "no_test_ran",
            "tests_passed_before_impl", "timeout", "start_error",
        )
        noStep.forEach { reason ->
            seed("no-$reason", Column.CODING)
            gate("no-$reason", Column.CODING, reason)
        }
        drain()

        noStep.forEach { reason ->
            assertThat(stepUp.modelFor("no-$reason")).describedAs(reason).isEqualTo("model-1")
        }
    }

    @Test
    fun `AC-10 onEvent returns without querying the database and the card is resolved afterwards`() {
        seed("c1", Column.CODING)
        val failed = gate("c1", Column.CODING, "exit_code")
        awaitModel("c1", "model-2")
        val moved = move("c1", Column.CODING, Column.VALIDATION)
        awaitModel("c1", "model-1")

        // With the database closed, any query on the caller thread would throw.
        database.close()
        stepUp.onEvent(failed)
        stepUp.onEvent(moved)
    }

    @Test
    fun `an empty ladder is refused`() {
        assertThrows<IllegalArgumentException> { ModelStepUp(emptyList(), store) }
    }
}
