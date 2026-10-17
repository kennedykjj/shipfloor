package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
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
import shipfloor.ports.FakeRuntime
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class ConflictResolutionTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val project = WorktreeFixtures.aProject()
    private val log = WorktreeFixtures.RecordingLog()
    private val sessions = McpSessions(clock)
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private lateinit var configDir: Path
    private lateinit var worktree: Path
    private var sequence = 0

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("conflict.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        configDir = Files.createDirectories(dir.resolve("mcp-config-dir"))
        worktree = Files.createDirectories(dir.resolve("wt"))
        val seed = Event(EventId("seed"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Conflict"))
        store.recordChange("c1", Card.create("Conflict", Column.VALIDATION), seed)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    /** Answers the attempts in order (the last repeats) and the conclusions in order (the last repeats). */
    private class ScriptedSync(
        private val attempts: List<ConflictAttempt>,
        private val conclusions: List<Boolean>,
    ) : BranchSync {
        val calls = mutableListOf<String>()

        override fun mergeMain(worktree: Path): MergeResult = MergeResult.Conflict

        override fun mergeMainKeepingConflicts(worktree: Path): ConflictAttempt {
            val index = calls.count { it == "keep" }
            calls.add("keep")
            return attempts[minOf(index, attempts.size - 1)]
        }

        override fun abortMerge(worktree: Path) {
            calls.add("abort")
        }

        override fun concludeMerge(worktree: Path): Boolean {
            val index = calls.count { it == "conclude" }
            calls.add("conclude")
            return conclusions[minOf(index, conclusions.size - 1)]
        }
    }

    private fun resolution(sync: BranchSync, runtime: FakeRuntime, head: String = "head-2", limits: ConflictLimits = ConflictLimits()) =
        ConflictResolution(
            project = project,
            store = store,
            recorder = recorder,
            sessions = sessions,
            mcpPort = 4317,
            runtime = runtime,
            costRecorder = RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
            branchSync = sync,
            head = WorktreeHead { head },
            configDir = configDir,
            clock = clock,
            model = "model-a",
            limits = limits,
            log = log,
        )

    private fun runtime(sessions: Int = 5) = FakeRuntime(List(sessions) { SessionScript.Succeed })

    private fun priorRound(round: Int) {
        val event = Event(
            EventId("round-${sequence++}"), clock.instant(), Actor.Factory("conflict"), EventType.CARD_MOVED,
            mapOf("from" to "VALIDATION", "to" to "VALIDATION", "reason" to "conflict_round", "round" to round.toString()),
        )
        recorder.record("c1", Card.create("Conflict", Column.VALIDATION), event)
    }

    private fun rounds() = store.events("c1").filter { it.type == EventType.CARD_MOVED && it.payload["reason"] == "conflict_round" }

    @Test
    fun `AC-1 a conflict within the limits is resolved by a conflict run in the card worktree`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 2, hunks = 5)), listOf(true))

        val outcome = resolution(sync, runtime).resolve("c1", worktree)

        assertThat(outcome).isEqualTo(ConflictOutcome.Resolved("head-2"))
        assertThat(runtime.launched.single().stage).isEqualTo("conflict")
        assertThat(runtime.launched.single().worktree).isEqualTo(worktree)
        assertThat(sync.calls).containsSubsequence("keep", "conclude").doesNotContain("abort")
        assertThat(configDir.toFile().list()).isEmpty()
    }

    @Test
    fun `AC-1 a conflict exactly at the default limits is still resolved`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 5, hunks = 20)), listOf(true))

        assertThat(resolution(sync, runtime).resolve("c1", worktree)).isInstanceOf(ConflictOutcome.Resolved::class.java)
        assertThat(runtime.launched).hasSize(1)
    }

    @Test
    fun `AC-1 the default limits are 5 files, 20 hunks and 2 rounds`() {
        assertThat(ConflictLimits()).isEqualTo(ConflictLimits(maxFiles = 5, maxHunks = 20, maxRounds = 2))
    }

    @Test
    fun `AC-1 a merge that is not in conflict needs no run`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Merged), listOf(true))

        val outcome = resolution(sync, runtime).resolve("c1", worktree)

        assertThat(outcome).isEqualTo(ConflictOutcome.Resolved(head = "head-2", cleanFrom = "head-2"))
        assertThat(runtime.launched).isEmpty()
    }

    @Test
    fun `AC-2 more files or more hunks than the limits start no run and leave no round behind`() {
        listOf(ConflictAttempt.Conflicted(files = 6, hunks = 1), ConflictAttempt.Conflicted(files = 1, hunks = 21)).forEach { attempt ->
            val runtime = runtime()
            val sync = ScriptedSync(listOf(attempt), listOf(true))

            val outcome = resolution(sync, runtime).resolve("c1", worktree)

            assertThat(outcome).describedAs(attempt.toString()).isEqualTo(ConflictOutcome.OverLimit)
            assertThat(runtime.launched).isEmpty()
            assertThat(sync.calls).contains("abort").doesNotContain("conclude")
        }
        assertThat(rounds()).isEmpty()
    }

    @Test
    fun `AC-2 the limits come from ConflictLimits`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 2, hunks = 1)), listOf(true))

        val outcome = resolution(sync, runtime, limits = ConflictLimits(maxFiles = 1, maxHunks = 20, maxRounds = 2)).resolve("c1", worktree)

        assertThat(outcome).isEqualTo(ConflictOutcome.OverLimit)
        assertThat(runtime.launched).isEmpty()
    }

    @Test
    fun `AC-3 a conflict that survives the last round exhausts the rounds and no further round starts`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 1, hunks = 1)), listOf(false))
        val resolution = resolution(sync, runtime, limits = ConflictLimits(maxFiles = 5, maxHunks = 20, maxRounds = 2))

        val first = resolution.resolve("c1", worktree)
        val second = resolution.resolve("c1", worktree)
        val third = resolution.resolve("c1", worktree)

        assertThat(first).isNotInstanceOf(ConflictOutcome.Resolved::class.java).isNotEqualTo(ConflictOutcome.RoundsExhausted)
        assertThat(second).isEqualTo(ConflictOutcome.RoundsExhausted)
        assertThat(third).isEqualTo(ConflictOutcome.RoundsExhausted)
        assertThat(runtime.launched).hasSize(2)
        assertThat(rounds().map { it.payload["round"] }).containsExactly("1", "2")
    }

    @Test
    fun `AC-3 rounds already recorded on the card survive a restart and count against the maximum`() {
        priorRound(1)
        priorRound(2)
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 1, hunks = 1)), listOf(true))

        val outcome = resolution(sync, runtime).resolve("c1", worktree)

        assertThat(outcome).isEqualTo(ConflictOutcome.RoundsExhausted)
        assertThat(runtime.launched).isEmpty()
        assertThat(rounds()).hasSize(2)
    }

    @Test
    fun `AC-5 each round is an event with the factory actor and the round number, and its cost is counted on the card`() {
        val runtime = runtime()
        val sync = ScriptedSync(listOf(ConflictAttempt.Conflicted(files = 1, hunks = 1)), listOf(true))
        val resolution = resolution(sync, runtime, limits = ConflictLimits(maxFiles = 5, maxHunks = 20, maxRounds = 3))

        resolution.resolve("c1", worktree)
        assertThat(rounds().single().payload).containsEntry("round", "1")
        assertThat(rounds().single().actor).isInstanceOf(Actor.Factory::class.java)
        assertThat(store.events("c1").filter { it.type == EventType.RUN_COST_RECORDED }).hasSize(1)

        resolution.resolve("c1", worktree)
        assertThat(rounds().map { it.payload["round"] }).containsExactly("1", "2")
        assertThat(store.events("c1").filter { it.type == EventType.RUN_COST_RECORDED }).hasSize(2)
        assertThat(store.events("c1").filter { it.type == EventType.RUN_COST_RECORDED }.map { it.payload["stage"] }).containsOnly("conflict")
    }
}
