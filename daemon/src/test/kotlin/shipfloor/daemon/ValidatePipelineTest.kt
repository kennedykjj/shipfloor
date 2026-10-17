package shipfloor.daemon

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import shipfloor.core.ExternalRef
import shipfloor.core.ProjectPolicies
import shipfloor.core.TextHash
import shipfloor.ports.ChecksState
import shipfloor.ports.FailedCheck
import shipfloor.ports.FakeForge
import shipfloor.ports.FakeRuntime
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class ValidatePipelineTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val project = WorktreeFixtures.aProject()
    private val log = WorktreeFixtures.RecordingLog()
    private val sessions = McpSessions(clock)
    private val order = CopyOnWriteArrayList<String>()
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private lateinit var worktrees: WorktreeManager
    private lateinit var configDir: Path
    private lateinit var worktreeRoot: Path

    private val green = listOf(GateResult("build-daemon", true, null), GateResult("test-daemon", true, null), GateResult("scope", true, null))
    private val red = listOf(GateResult("build-daemon", true, null), GateResult("test-daemon", false, "2 failed"), GateResult("scope", false, "outside"))
    private val buildRed = listOf(GateResult("build-daemon", false, "e: Unresolved reference 'Pong'"), GateResult("test-daemon", false, "compilation failed"))
    private val approved = "VERDICT: APPROVED"

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("validate.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        worktreeRoot = Files.createDirectories(dir.resolve("worktrees"))
        configDir = Files.createDirectories(dir.resolve("mcp-config-dir"))
        worktrees = WorktreeManager(worktreeRoot, WorktreeFixtures.FakeWorktreeGit(), log)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private fun card(id: String, ticket: String? = null) {
        val event = Event(EventId("seed-$id"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Validate $id"))
        val created = Card.create("Validate $id", Column.VALIDATION)
        val withTicket = ticket?.let {
            created.withReference(ExternalRef("jira", it, "https://tracker.invalid/$it", clock.instant(), TextHash.of(it)))
        } ?: created
        store.recordChange(id, withTicket, event)
    }

    private fun report(cardId: String, summary: String, at: Instant) {
        val current = requireNotNull(store.card(cardId))
        val event = Event(
            EventId(UUID.randomUUID().toString()), at, Actor.Agent("validate"), EventType.AGENT_REPORTED,
            mapOf("column" to current.column.name, "summary" to summary),
        )
        recorder.record(cardId, current, event)
    }

    /** Merges answer by the script (the last repeats) and note the call in [order]. */
    private fun sync(vararg results: MergeResult) = BranchSync {
        order.add("merge")
        results[minOf(order.count { it == "merge" } - 1, results.size - 1)]
    }

    private fun gates(vararg rounds: List<GateResult>) = ValidateGates { _, _ ->
        order.add("gates")
        ValidateGateResults(rounds[minOf(order.count { it == "gates" } - 1, rounds.size - 1)])
    }

    /** One session per entry; the entry is what the agent reports during it (null: it reports nothing). */
    private fun reviewing(vararg reports: String?): ReviewingRuntime {
        var index = 0
        return ReviewingRuntime(FakeRuntime(List(reports.size) { SessionScript.Succeed })) {
            order.add("review")
            reports[index++]?.let { report("c1", it, clock.instant().plusSeconds(1)) }
        }
    }

    private class ReviewingRuntime(private val inner: FakeRuntime, private val onLaunch: () -> Unit) : Runtime {
        fun launched(): List<SessionRequest> = inner.launched

        override fun launch(request: SessionRequest) = inner.launch(request).also { onLaunch() }
    }

    private fun pipeline(
        runtime: Runtime,
        sync: BranchSync,
        gates: ValidateGates,
        maxRounds: Int = 3,
        delivery: PullRequestDelivery = greenDelivery(),
        conflicts: ConflictResolver? = null,
    ) = ValidatePipeline(
        project = project,
        store = store,
        recorder = recorder,
        worktrees = worktrees,
        sessions = sessions,
        mcpPort = 4317,
        runtime = runtime,
        costRecorder = RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
        branchSync = sync,
        gates = gates,
        configDir = configDir,
        clock = clock,
        model = "model-a",
        maxRounds = maxRounds,
        log = log,
        delivery = delivery,
        conflicts = conflicts,
    )

    private fun column(id: String): Column = requireNotNull(store.card(id)).column

    private fun events(type: EventType) = store.events("c1").filter { it.type == type }

    @Test
    fun `AC-1 a card in Validation syncs the branch with main, reruns the gates and then runs the review, events with actor`() {
        card("c1")
        val runtime = reviewing(approved)

        pipeline(runtime, sync(MergeResult.Merged), gates(green)).run("c1")

        assertThat(order).containsExactly("merge", "gates", "review")
        assertThat(runtime.launched().single().stage).isEqualTo("validate")
        assertThat(runtime.launched().single().worktree).isEqualTo(worktreeRoot.resolve("alpha").resolve("c1"))
        assertThat(events(EventType.CARD_MOVED).map { it.actor }).containsOnly(Actor.Factory("validate"))
        assertThat(events(EventType.RUN_COST_RECORDED)).hasSize(1)
    }

    @Test
    fun `AC-2 green gates and an APPROVED review move the card to Staged`() {
        card("c1")

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green)).run("c1")

        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
    }

    @Test
    fun `AC-3 a failed build or test gate sends the card back to Coding with the gate output instead of blocking it`() {
        card("c1")

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(buildRed)).run("c1")

        assertThat(column("c1")).isEqualTo(Column.CODING)
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
        val back = events(EventType.CARD_MOVED).last { it.payload["to"] == Column.CODING.name }
        assertThat(back.actor).isEqualTo(Actor.Factory("validate"))
        assertThat(back.payload["reason"]).contains("build-daemon: e: Unresolved reference 'Pong'").contains("test-daemon")
    }

    @Test
    fun `AC-3 a card the validation already returned three times is blocked on the next failed build instead`() {
        card("c1")
        repeat(3) {
            recorder.record(
                "c1", requireNotNull(store.card("c1")),
                Event(EventId("back-$it"), clock.instant(), Actor.Factory("validate"), EventType.CARD_MOVED, mapOf("from" to "VALIDATION", "to" to "CODING")),
            )
            recorder.record(
                "c1", requireNotNull(store.card("c1")),
                Event(EventId("again-$it"), clock.instant(), Actor.Factory("develop"), EventType.CARD_MOVED, mapOf("from" to "CODING", "to" to "VALIDATION")),
            )
        }

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(buildRed)).run("c1")

        assertThat(events(EventType.CARD_BLOCKED).single().payload["reason"]).isEqualTo("gates_failed: build-daemon,test-daemon")
    }

    @Test
    fun `AC-3 an APPROVED review does not win over a red gate, the card is blocked listing the failed gates`() {
        card("c1")
        val runtime = reviewing(approved)

        pipeline(runtime, sync(MergeResult.Merged), gates(red)).run("c1")

        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        assertThat(store.events("c1").flatMap { it.payload.values }).doesNotContain(Column.STAGED.name)
        val blocked = events(EventType.CARD_BLOCKED).single()
        assertThat(blocked.actor).isEqualTo(Actor.Factory("validate"))
        assertThat(requireNotNull(blocked.payload["reason"])).contains("test-daemon").contains("scope").doesNotContain("build-daemon")
        assertThat(runtime.launched()).hasSize(1)
    }

    @Test
    fun `AC-4 a blocking finding returns the card to Coding with the layer and reason, ignoring an older report`() {
        card("c1")
        report("c1", approved, clock.instant().minusSeconds(3600))

        pipeline(reviewing("VERDICT: BLOCKED layer=daemon\nAC-2 has no test for the edge case"), sync(MergeResult.Merged), gates(green)).run("c1")

        assertThat(column("c1")).isEqualTo(Column.CODING)
        val move = events(EventType.CARD_MOVED).single()
        assertThat(move.payload["layer"]).isEqualTo("daemon")
        assertThat(move.payload["reason"]).isEqualTo("AC-2 has no test for the edge case")
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
    }

    @Test
    fun `AC-5 a conflict with main blocks the card with a readable reason and runs no review`() {
        card("c1")
        val runtime = reviewing(approved)

        pipeline(runtime, sync(MergeResult.Conflict), gates(green)).run("c1")

        assertThat(runtime.launched()).isEmpty()
        assertThat(order).containsExactly("merge")
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        val blocked = events(EventType.CARD_BLOCKED).single()
        assertThat(blocked.actor).isEqualTo(Actor.Factory("validate"))
        assertThat(requireNotNull(blocked.payload["reason"])).contains("merge_conflict")
    }

    /** A main that conflicts on every merge: the keeping merge opens a conflict of the given size and the agent's run resolves it. */
    private class ConflictingSync(private val conflict: ConflictAttempt = ConflictAttempt.Conflicted(files = 1, hunks = 1)) : BranchSync {
        val calls = mutableListOf<String>()

        override fun mergeMain(worktree: Path): MergeResult {
            calls.add("merge")
            return MergeResult.Conflict
        }

        override fun mergeMainKeepingConflicts(worktree: Path): ConflictAttempt {
            calls.add("keep")
            return conflict
        }

        override fun abortMerge(worktree: Path) {
            calls.add("abort")
        }

        override fun concludeMerge(worktree: Path): Boolean {
            calls.add("conclude")
            return true
        }
    }

    private fun conflictRound(round: Int) {
        val current = requireNotNull(store.card("c1"))
        val event = Event(
            EventId("round-$round"), clock.instant(), Actor.Factory("conflict"), EventType.CARD_MOVED,
            mapOf("from" to "VALIDATION", "to" to "VALIDATION", "reason" to "conflict_round", "round" to round.toString()),
        )
        recorder.record("c1", current, event)
    }

    private fun conflictResolution(sync: BranchSync, runtime: Runtime, maxRounds: Int) = ConflictResolution(
        project = project, store = store, recorder = recorder, sessions = sessions, mcpPort = 4317, runtime = runtime,
        costRecorder = RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log), branchSync = sync,
        head = WorktreeHead { "head-2" }, configDir = configDir, clock = clock, model = "model-a",
        limits = ConflictLimits(maxFiles = 5, maxHunks = 20, maxRounds = maxRounds), log = log,
    )

    @Test
    fun `AC-6 a new conflict after an earlier round counts as another round, is resolved inside the maximum and the gates then decide`() {
        card("c1")
        conflictRound(1)
        val sync = ConflictingSync()
        val conflictRuntime = FakeRuntime(listOf(SessionScript.Succeed))

        pipeline(
            reviewing(approved), sync, gates(green),
            conflicts = conflictResolution(sync, conflictRuntime, maxRounds = 2),
        ).run("c1")

        assertThat(conflictRuntime.launched.single().stage).isEqualTo("conflict")
        val rounds = events(EventType.CARD_MOVED).filter { it.payload["reason"] == "conflict_round" }
        assertThat(rounds.map { it.payload["round"] }).containsExactly("1", "2")
        assertThat(order).containsExactly("gates", "review")
        assertThat(column("c1")).isEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-6 a new conflict past the maximum rounds blocks the card as exhausted and starts no run`() {
        card("c1")
        conflictRound(1)
        val sync = ConflictingSync()
        val conflictRuntime = FakeRuntime(listOf(SessionScript.Succeed))
        val runtime = reviewing(approved)

        pipeline(runtime, sync, gates(green), conflicts = conflictResolution(sync, conflictRuntime, maxRounds = 1)).run("c1")

        assertThat(conflictRuntime.launched).isEmpty()
        assertThat(runtime.launched()).isEmpty()
        assertThat(order).isEmpty()
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("conflict_rounds_exhausted")
    }

    @Test
    fun `AC-2 a conflict over the limits blocks the validation as merge_conflict without a conflict run`() {
        card("c1")
        val sync = ConflictingSync(ConflictAttempt.Conflicted(files = 6, hunks = 1))
        val conflictRuntime = FakeRuntime(listOf(SessionScript.Succeed))

        pipeline(reviewing(approved), sync, gates(green), conflicts = conflictResolution(sync, conflictRuntime, maxRounds = 2)).run("c1")

        assertThat(conflictRuntime.launched).isEmpty()
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("merge_conflict")
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-5 a git failure while syncing blocks the card and runs no review`() {
        card("c1")
        val runtime = reviewing(approved)

        pipeline(runtime, sync(MergeResult.Failed), gates(green)).run("c1")

        assertThat(runtime.launched()).isEmpty()
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("git_failed")
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-5 malformed reports in every round block the card after the last round, gates rerun each round`() {
        card("c1")
        val runtime = reviewing("looks fine to me", "VERDICT: MAYBE", null)

        pipeline(runtime, sync(MergeResult.Merged), gates(green), maxRounds = 3).run("c1")

        assertThat(runtime.launched()).hasSize(3)
        assertThat(order.count { it == "gates" }).isEqualTo(3)
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        val blocked = events(EventType.CARD_BLOCKED).single()
        assertThat(blocked.actor).isEqualTo(Actor.Factory("validate"))
        assertThat(requireNotNull(blocked.payload["reason"])).contains("review_malformed")
    }

    @Test
    fun `AC-5 a malformed report followed by a valid one in a later round is accepted`() {
        card("c1")

        pipeline(reviewing("garbage", approved), sync(MergeResult.Merged), gates(green), maxRounds = 3).run("c1")

        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
    }

    @Test
    fun `AC-6 the MCP config lives outside the worktree with mode 0600 while the run lasts and is gone with its session after`() {
        card("c1")
        val seen = mutableListOf<Triple<Path, String, String>>()
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(listOf(SessionScript.Succeed))) { request ->
            val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(request.mcpConfig))
            seen.add(Triple(request.mcpConfig, perms, Files.readString(request.mcpConfig)))
            report("c1", approved, clock.instant().plusSeconds(1))
        }

        pipeline(runtime, sync(MergeResult.Merged), gates(green)).run("c1")

        val (path, perms, content) = seen.single()
        assertThat(path.toAbsolutePath().normalize().startsWith(worktreeRoot)).isFalse()
        assertThat(path.toAbsolutePath().normalize().startsWith(configDir)).isTrue()
        assertThat(perms).isEqualTo("rw-------")
        val token = tokenIn(content)
        assertThat(Files.exists(path)).isFalse()
        assertThat(configDir.listDirectoryEntries()).isEmpty()
        assertThat(sessions.find(token)).isNull()
        assertNoLeak(token)
    }

    @Test
    fun `AC-6 the session belongs to the validate role and the card while the run lasts`() {
        card("c1")
        var live: McpSession? = null
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(listOf(SessionScript.Succeed))) { request ->
            live = sessions.find(tokenIn(Files.readString(request.mcpConfig)))
        }

        pipeline(runtime, sync(MergeResult.Merged), gates(green), maxRounds = 1).run("c1")

        assertThat(live).isEqualTo(McpSession(project.repo, "validate", "c1"))
    }

    @Test
    fun `AC-6 the session is revoked and the config deleted when the run throws`() {
        card("c1")
        val seen = mutableListOf<String>()
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(listOf(SessionScript.Succeed)), failure = IllegalStateException("boom")) { request ->
            seen.add(Files.readString(request.mcpConfig))
        }

        runCatching { pipeline(runtime, sync(MergeResult.Merged), gates(green)).run("c1") }

        val token = tokenIn(seen.single())
        assertThat(configDir.listDirectoryEntries()).isEmpty()
        assertThat(sessions.find(token)).isNull()
        assertNoLeak(token)
    }

    @Test
    fun `AC-6 a run that throws releases the card lock of the pool`() {
        assertThat(lockReleasedAfter(failure = IllegalStateException("boom"), script = emptyList())).isTrue()
    }

    @Test
    fun `AC-6 a run that times out releases the card lock of the pool and removes the config`() {
        assertThat(lockReleasedAfter(failure = null, script = listOf(SessionScript.Hang))).isTrue()
        assertThat(configDir.listDirectoryEntries()).isEmpty()
    }

    @Test
    fun `AC-1 the dispatcher hands a Validation card to the validate launcher, which holds the lock until the run ends`() {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val runtime = reviewing(approved)
        val launcher = ValidateStageLauncher({ pipeline(runtime, sync(MergeResult.Merged), gates(green)) }, pool)
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.VALIDATE), store, pool, launcher, DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.VALIDATE, "c1"))
            assertThat(RefineFixtures.await { column("c1") == Column.STAGED && !pool.isLocked("c1") }).isTrue()
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `AC-6 the validate launcher delegates to the shared PipelineStageLauncher and keeps no thread or lock handling of its own`() {
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val launcher = ValidateStageLauncher({ pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green)) }, pool)

        val fieldTypes = ValidateStageLauncher::class.java.declaredFields.map { it.type }
        val methods = ValidateStageLauncher::class.java.declaredMethods.map { it.name }

        assertThat(fieldTypes).contains(PipelineStageLauncher::class.java)
        assertThat(methods).doesNotContain("execute", "awaitLock")
        assertThat(launcher).isInstanceOf(StageLauncher::class.java)
    }

    @Test
    fun `AC-7 a run that throws is logged as validate_failed with the card id and still frees the lock`() {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(listOf(SessionScript.Succeed)), hold = null, failure = IllegalStateException("boom"))
        val launcher = ValidateStageLauncher({ pipeline(runtime, sync(MergeResult.Merged), gates(green)) }, pool, log)
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.VALIDATE), store, pool, launcher, DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.VALIDATE, "c1"))
            assertThat(RefineFixtures.await { !pool.isLocked("c1") }).isTrue()
            assertThat(log.lines).anyMatch { it.first == "validate_failed" && it.second["card"] == "c1" }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `AC-1 a pipeline built without maxRounds runs the documented default constant of rounds`() {
        card("c1")
        val runtime = reviewing(*arrayOfNulls<String>(DEFAULT_VALIDATE_MAX_ROUNDS + 2))
        val defaulted = ValidatePipeline(
            project = project, store = store, recorder = recorder, worktrees = worktrees, sessions = sessions, mcpPort = 4317,
            runtime = runtime, costRecorder = RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
            branchSync = sync(MergeResult.Merged), gates = gates(green), configDir = configDir, clock = clock, model = "model-a", log = log,
            delivery = greenDelivery(),
        )

        defaulted.run("c1")

        assertThat(DEFAULT_VALIDATE_MAX_ROUNDS).isEqualTo(3)
        assertThat(runtime.launched()).hasSize(DEFAULT_VALIDATE_MAX_ROUNDS)
        assertThat(order.count { it == "gates" }).isEqualTo(DEFAULT_VALIDATE_MAX_ROUNDS)
    }

    @Test
    fun `AC-2 with maxRounds of N and gates that keep failing the pipeline stops after N rounds and reports the failure`() {
        card("c1")
        val runtime = reviewing(null, null, null, null, null)

        pipeline(runtime, sync(MergeResult.Merged), gates(red), maxRounds = 2).run("c1")

        assertThat(runtime.launched()).hasSize(2)
        assertThat(order.count { it == "gates" }).isEqualTo(2)
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        assertThat(events(EventType.CARD_BLOCKED)).hasSize(1)
    }

    @Test
    fun `AC-3 one type turns executor, scope and spec results into the matching GatesState`() {
        val passing = SpecCheck(SpecWeight.STANDARD, emptyList())
        val failing = SpecCheck(SpecWeight.STANDARD, listOf(SpecFinding(SpecGate.WEIGHT, null, "weight mismatch")))

        assertThat(ValidateGateResults.fromExecutor("build-daemon", GateVerdict.Pass).state).isEqualTo(GatesState.GREEN)
        assertThat(ValidateGateResults.fromExecutor("test-daemon", GateVerdict.Fail("2 failed")).state).isEqualTo(GatesState.RED)
        assertThat(ValidateGateResults.fromScope(ScopeVerdict.Pass).state).isEqualTo(GatesState.GREEN)
        assertThat(ValidateGateResults.fromScope(ScopeVerdict.Violations(listOf("CLAUDE.md"), emptyList())).state).isEqualTo(GatesState.RED)
        assertThat(ValidateGateResults.fromSpec(passing).state).isEqualTo(GatesState.GREEN)
        assertThat(ValidateGateResults.fromSpec(failing).state).isEqualTo(GatesState.RED)
        assertThat(ValidateGateResults(emptyList()).state).isEqualTo(GatesState.GREEN)
    }

    @Test
    fun `AC-3 the conversions keep the gate names and reasons the events and the card show`() {
        val executor = ValidateGateResults.fromExecutor("test-daemon", GateVerdict.Fail("2 failed")).results
        val scope = ValidateGateResults.fromScope(ScopeVerdict.Violations(listOf("CLAUDE.md"), listOf("a", "b"))).results
        val spec = ValidateGateResults.fromSpec(
            SpecCheck(SpecWeight.STANDARD, listOf(SpecFinding(SpecGate.WEIGHT, null, "weight mismatch"))),
        ).results

        assertThat(executor).containsExactly(GateResult("test-daemon", false, "2 failed"))
        assertThat(ValidateGateResults.fromExecutor("build-daemon", GateVerdict.Pass).results)
            .containsExactly(GateResult("build-daemon", true, null))
        assertThat(scope).containsExactly(GateResult("scope", false, "forbidden=1 outside=2"))
        assertThat(ValidateGateResults.fromScope(ScopeVerdict.Pass).results).containsExactly(GateResult("scope", true, null))
        assertThat(spec).containsExactly(GateResult("test-plan", true, null), GateResult("weight", false, "weight mismatch"))
    }

    @Test
    fun `AC-4 failedNames lists only the failed gates in order`() {
        assertThat(ValidateGateResults(red).failedNames).containsExactly("test-daemon", "scope")
        assertThat(ValidateGateResults(green).failedNames).isEmpty()
        val spec = SpecCheck(
            SpecWeight.STANDARD,
            listOf(SpecFinding(SpecGate.TEST_PLAN, "AC-1", "no row"), SpecFinding(SpecGate.WEIGHT, null, "mismatch")),
        )
        assertThat(ValidateGateResults.fromSpec(spec).failedNames).containsExactly("test-plan", "weight")
    }

    @Test
    fun `AC-5 the pipeline reports the same gates_failed reason and lets green gates through as before`() {
        card("c1")
        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(red)).run("c1")

        assertThat(events(EventType.CARD_BLOCKED).single().payload["reason"]).isEqualTo("gates_failed: test-daemon,scope")
        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
    }

    private val forge = FakeForge()

    private class RecordingPublisher(private val result: PushResult = PushResult.Pushed) : BranchPublisher {
        val pushed = mutableListOf<String>()

        override fun push(worktree: Path, branch: String): PushResult {
            pushed.add(branch)
            return result
        }
    }

    private fun delivery(publisher: BranchPublisher, head: String = "sha-1", maxPolls: Int = 3, onPause: () -> Unit = {}) =
        PullRequestDelivery(forge, publisher, WorktreeHead { head }, CiPause { onPause() }, maxPolls)

    /** The default delivery of the stage tests: the forge shows the local head with a green CI. */
    private fun greenDelivery(): PullRequestDelivery {
        forge.scriptChecks("sha-1", ChecksState.Passed)
        return delivery(RecordingPublisher())
    }

    private fun stagedMove() = events(EventType.CARD_MOVED).single { it.payload["to"] == Column.STAGED.name }

    @Test
    fun `AC-1 an approved validate pushes the card branch and opens the PR for it through the forge`() {
        card("c1")
        val publisher = RecordingPublisher()
        forge.scriptChecks("sha-1", ChecksState.Passed)

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(publisher)).run("c1")

        val branch = worktrees.ensure(project, "c1", "Validate c1").branch
        assertThat(publisher.pushed).containsExactly(branch)
        assertThat(forge.opened.single().branch).isEqualTo(branch)
        assertThat(forge.opened.single().base).isEqualTo("main")
    }

    @Test
    fun `AC-1 an existing PR for the branch is updated and not duplicated`() {
        card("c1")
        val branch = worktrees.ensure(project, "c1", "Validate c1").branch
        forge.openPullRequest(PullRequestRequest(branch, "main", "old title", "old body"))
        forge.scriptChecks("sha-1", ChecksState.Passed)

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(RecordingPublisher())).run("c1")

        assertThat(forge.opened).hasSize(1)
        assertThat(forge.findOpenPullRequest(branch)).isNotNull
        assertThat(column("c1")).isEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-2 a pending CI keeps the card in Validation until the forge reports green after the wait`() {
        card("c1")
        var pauses = 0

        pipeline(
            reviewing(approved), sync(MergeResult.Merged), gates(green),
            delivery = delivery(RecordingPublisher(), maxPolls = 5) {
                pauses++
                forge.scriptChecks("sha-1", ChecksState.Passed)
            },
        ).run("c1")

        assertThat(pauses).isEqualTo(1)
        assertThat(column("c1")).isEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-2 a CI that stays pending past maxPolls leaves the card in Validation, blocked with ci_timeout`() {
        card("c1")
        var pauses = 0

        pipeline(
            reviewing(approved), sync(MergeResult.Merged), gates(green),
            delivery = delivery(RecordingPublisher(), maxPolls = 3) { pauses++ },
        ).run("c1")

        assertThat(pauses).isBetween(1, 3)
        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(store.events("c1").flatMap { it.payload.values }).doesNotContain(Column.STAGED.name)
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("ci_timeout")
    }

    @Test
    fun `AC-5 the green of an old PR head is not accepted, the card waits for the local head and records it on Staged`() {
        card("c1")
        forge.scriptChecks("sha-1", ChecksState.Failed(listOf(FailedCheck("old", "stale head"))))
        forge.scriptChecks("local-sha", ChecksState.Passed)
        var pauses = 0

        pipeline(
            reviewing(approved), sync(MergeResult.Merged), gates(green),
            delivery = delivery(RecordingPublisher(), head = "local-sha", maxPolls = 5) {
                pauses++
                forge.pushHead(PullRequestRef(1, "", ""), "local-sha")
            },
        ).run("c1")

        assertThat(pauses).isEqualTo(1)
        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(stagedMove().payload["head"]).isEqualTo("local-sha")
        assertThat(stagedMove().payload["ci"]).isEqualTo("green")
    }

    @Test
    fun `AC-5 a forge that never shows the local head times out instead of trusting the old head`() {
        card("c1")
        forge.scriptChecks("sha-1", ChecksState.Passed)
        forge.scriptChecks("local-sha", ChecksState.Passed)

        pipeline(
            reviewing(approved), sync(MergeResult.Merged), gates(green),
            delivery = delivery(RecordingPublisher(), head = "local-sha", maxPolls = 3),
        ).run("c1")

        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("ci_timeout")
    }

    @Test
    fun `AC-6 a refused push blocks the card with push_refused and never stages it`() {
        card("c1")
        forge.scriptChecks("sha-1", ChecksState.Passed)

        pipeline(
            reviewing(approved), sync(MergeResult.Merged), gates(green),
            delivery = delivery(RecordingPublisher(PushResult.Refused)),
        ).run("c1")

        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("push_refused")
        assertThat(forge.opened).isEmpty()
    }

    @Test
    fun `AC-6 the production publisher pushes only the card branch, no tags, and refuses invalid branches or the base`() {
        val remote = dir.resolve("remote.git")
        val work = dir.resolve("work")
        git(dir, "init", "--bare", "-b", "main", remote.toString())
        git(dir, "init", "-b", "main", work.toString())
        git(work, "commit", "--allow-empty", "-m", "base")
        git(work, "remote", "add", "origin", remote.toString())
        git(work, "push", "origin", "main")
        val mainBefore = git(remote, "rev-parse", "refs/heads/main")
        git(work, "checkout", "-b", "feat/c1")
        git(work, "commit", "--allow-empty", "-m", "card work")
        git(work, "branch", "feat/other")
        git(work, "tag", "v1")

        assertThat(SystemBranchPublisher().push(work, "feat/c1")).isEqualTo(PushResult.Pushed)

        assertThat(git(remote, "rev-parse", "refs/heads/feat/c1")).isEqualTo(git(work, "rev-parse", "HEAD"))
        assertThat(git(remote, "branch", "--list", "feat/other")).isEmpty()
        assertThat(git(remote, "tag", "--list")).isEmpty()
        assertThat(git(remote, "rev-parse", "refs/heads/main")).isEqualTo(mainBefore)
        listOf("-x", "a..b", "a b", "main").forEach { bad ->
            assertThat(SystemBranchPublisher().push(work, bad)).describedAs(bad).isEqualTo(PushResult.Refused)
        }
        assertThat(git(remote, "rev-parse", "refs/heads/main")).isEqualTo(mainBefore)
    }

    @Test
    fun `AC-7 a red CI with long multi-byte summaries returns the card to Coding with a reason cut at 2048 UTF-8 bytes`() {
        card("c1")
        val long = "€".repeat(1500)
        forge.scriptChecks(
            "sha-1",
            ChecksState.Failed(listOf(FailedCheck("test-a", long), FailedCheck("test-b", long), FailedCheck("test-c", long))),
        )

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(RecordingPublisher())).run("c1")

        assertThat(column("c1")).isEqualTo(Column.CODING)
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
        val reason = requireNotNull(events(EventType.CARD_MOVED).single().payload["reason"])
        assertThat(reason.toByteArray(Charsets.UTF_8).size).isBetween(1, 2048)
        assertThat(reason).doesNotContain("�").contains("test-a")
    }

    @Test
    fun `AC-1 the delivery is a required non-null constructor parameter of the ValidatePipeline`() {
        val constructor = ValidatePipeline::class.java.declaredConstructors.single { !it.isSynthetic && Modifier.isPublic(it.modifiers) }
        val values = listOf<Any>(
            project, store, recorder, worktrees, sessions, runtime(), RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
            sync(MergeResult.Merged), gates(green), configDir, clock, log, GitChangedPaths(), "model-a", 4317,
        )
        val arguments = constructor.parameterTypes.map { type ->
            when {
                type == PullRequestDelivery::class.java -> null
                type == ConflictResolver::class.java -> null
                type == ExternalMilestones::class.java -> null
                type == ProjectFileSource::class.java -> StubFiles()
                type.isPrimitive -> 4317
                else -> values.first { type.isInstance(it) }
            }
        }.toTypedArray()

        assertThat(ValidatePipeline::class.java.getDeclaredField("delivery").type).isEqualTo(PullRequestDelivery::class.java)
        assertThat(constructor.parameterTypes.count { it == PullRequestDelivery::class.java }).isEqualTo(1)
        assertThatThrownBy { constructor.newInstance(*arguments) }
            .isInstanceOf(InvocationTargetException::class.java)
            .hasCauseInstanceOf(NullPointerException::class.java)
    }

    private fun runtime(): Runtime = FakeRuntime(emptyList())

    // Issue #90 (convencoes corporativas). The pipeline gets a ProjectFileSource constructor parameter (default: no
    // files) from which the deliver step reads `conventions.pr_template` as committed on main. The constructor is
    // filled by parameter type so these tests compile before that parameter exists and fail by assertion instead.

    private class StubFiles(private val files: Map<String, String> = emptyMap(), private val failure: RuntimeException? = null) :
        ProjectFileSource {
        val requested = mutableListOf<Pair<Path, String>>()

        override fun read(repo: Path, path: String): String? {
            requested.add(repo to path)
            failure?.let { throw it }
            return files[path]
        }
    }

    private fun conventionPolicies(vararg conventions: Pair<String, String>) =
        ProjectPolicies.from(mapOf("conventions" to mapOf(*conventions)))

    private fun deliveryOf(conventionProject: Project, files: ProjectFileSource): ValidatePipeline {
        val constructor = ValidatePipeline::class.java.declaredConstructors.single { !it.isSynthetic && Modifier.isPublic(it.modifiers) }
        assertThat(constructor.parameterTypes).describedAs("ValidatePipeline takes a ProjectFileSource").contains(ProjectFileSource::class.java)
        val values = listOf<Any>(
            conventionProject, store, recorder, worktrees, sessions, reviewing(approved), RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
            sync(MergeResult.Merged), gates(green), configDir, clock, log, GitChangedPaths(), "model-a", greenDelivery(), files,
        )
        val arguments = constructor.parameterTypes.map { type ->
            when {
                type == ConflictResolver::class.java -> null
                type == ExternalMilestones::class.java -> null
                type.isPrimitive -> 3
                else -> values.first { type.isInstance(it) }
            }
        }.toTypedArray()
        return constructor.newInstance(*arguments) as ValidatePipeline
    }

    private val template = "## Summary\n\n## How to test\n"

    @Test
    fun `AC-3 the PR body follows the template read from main at the configured path and carries the ticket line`() {
        card("c1", ticket = "PROJ-123")
        val files = StubFiles(mapOf("docs/pr.md" to template))
        val configured = project.copy(
            policies = conventionPolicies("pr_template" to "docs/pr.md", "commit_format" to "[{ticket}] {subject}"),
        )

        deliveryOf(configured, files).run("c1")

        assertThat(files.requested).contains(Path.of(project.repo) to "docs/pr.md")
        val opened = forge.opened.single()
        assertThat(opened.body).contains(template).contains("Ticket: PROJ-123")
        assertThat(opened.title).isEqualTo("[PROJ-123] Validate c1")
        assertThat(column("c1")).isEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-4 with language pt-BR the body labels come in Portuguese`() {
        card("c1", ticket = "PROJ-123")
        val configured = project.copy(policies = conventionPolicies("language" to "pt-BR"))

        deliveryOf(configured, StubFiles(mapOf(".github/pull_request_template.md" to template))).run("c1")

        assertThat(forge.opened.single().body).contains(template).contains("Chamado: PROJ-123").doesNotContain("Ticket:")
    }

    @Test
    fun `AC-5 a template that cannot be read falls back to the minimal body and the card still reaches Staged`() {
        card("c1", ticket = "PROJ-123")
        val broken = StubFiles(failure = InvalidProjectConfigException("pull_request_template.md", "exceeds the byte limit"))

        deliveryOf(project, broken).run("c1")

        assertThat(forge.opened.single().body).contains("Card c1").doesNotContain("Ticket: {")
        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(events(EventType.CARD_BLOCKED)).isEmpty()
    }

    @Test
    fun `AC-2 an approval with no CI result from the delivery never stages`() {
        card("c1")

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(RecordingPublisher())).run("c1")

        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(requireNotNull(events(EventType.CARD_BLOCKED).single().payload["reason"])).contains("ci_timeout")
    }

    @Test
    fun `AC-3 an unavailable forge never moves the card to Staged`() {
        card("c1")
        forge.failNext("openPullRequest")

        runCatching {
            pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(RecordingPublisher())).run("c1")
        }

        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        assertThat(store.events("c1").flatMap { it.payload.values }).doesNotContain(Column.STAGED.name)
    }

    @Test
    fun `AC-3 a CI that is not green never moves the card to Staged`() {
        card("c1")
        forge.scriptChecks("sha-1", ChecksState.Failed(listOf(FailedCheck("test-a", "boom"))))

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green), delivery = delivery(RecordingPublisher())).run("c1")

        assertThat(column("c1")).isNotEqualTo(Column.STAGED)
        assertThat(store.events("c1").flatMap { it.payload.values }).doesNotContain(Column.STAGED.name)
    }

    @Test
    fun `AC-4 the stage tests run on a delivery over a FakeForge with a green CI scripted and reach Staged`() {
        card("c1")

        pipeline(reviewing(approved), sync(MergeResult.Merged), gates(green)).run("c1")

        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(forge.opened).hasSize(1)
        assertThat(stagedMove().payload["head"]).isEqualTo("sha-1")
        assertThat(stagedMove().payload["ci"]).isEqualTo("green")
    }

    private fun git(workdir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", workdir.toString(), "-c", "user.name=t", "-c", "user.email=t@t.invalid") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        assertThat(process.waitFor()).describedAs("git ${args.toList()}: $output").isZero()
        return output
    }

    private fun lockReleasedAfter(failure: RuntimeException?, script: List<SessionScript>): Boolean {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val hold = CountDownLatch(1)
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(script), hold = hold, failure = failure)
        val launcher = ValidateStageLauncher({ pipeline(runtime, sync(MergeResult.Merged), gates(green)) }, pool)
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.VALIDATE), store, pool, launcher, DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.VALIDATE, "c1"))
            assertThat(runtime.started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(pool.isLocked("c1")).isTrue()
            hold.countDown()
            return RefineFixtures.await { !pool.isLocked("c1") }
        } finally {
            hold.countDown()
            dispatcher.close()
        }
    }

    private fun tokenIn(config: String): String {
        val candidates = Regex("[A-Za-z0-9_-]{43}").findAll(config).map { it.value }.toList()
        return requireNotNull(candidates.firstOrNull { sessions.find(it) != null } ?: candidates.lastOrNull()) {
            "no session token found in the MCP config"
        }
    }

    private fun assertNoLeak(token: String) {
        val logged = log.lines.flatMap { listOf(it.first) + it.second.keys + it.second.values }
        val eventValues = store.eventsAfter(0).flatMap { it.event.payload.values }
        assertThat(logged + eventValues).noneMatch { it.contains(token) }
    }
}
