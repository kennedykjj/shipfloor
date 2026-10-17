package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
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
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database

object RefineFixtures {
    val green = listOf(GateResult("test-plan", true, null), GateResult("weight", true, null), GateResult("scope", true, null))
    val red = listOf(GateResult("test-plan", false, "missing"), GateResult("weight", true, null), GateResult("scope", false, "outside"))

    /** Answers one scripted gate list per evaluation (the last one repeats); can hold the first evaluation back. */
    class ScriptedGates(private val rounds: List<List<GateResult>>, private val hold: CountDownLatch? = null) : RefineGates {
        val evaluated = CopyOnWriteArrayList<String>()

        override fun evaluate(cardId: String, worktree: Path): List<GateResult> {
            hold?.await(10, TimeUnit.SECONDS)
            val index = evaluated.size
            evaluated.add(cardId)
            return rounds[minOf(index, rounds.size - 1)]
        }
    }

    /** Lets the test look at the world while the session runs; optionally blocks or throws. */
    class ProbingRuntime(
        private val inner: Runtime,
        private val hold: CountDownLatch? = null,
        private val failure: RuntimeException? = null,
        private val onLaunch: (SessionRequest) -> Unit = {},
    ) : Runtime {
        val started = CountDownLatch(1)

        override fun launch(request: SessionRequest): SessionResult {
            onLaunch(request)
            started.countDown()
            hold?.await(10, TimeUnit.SECONDS)
            failure?.let { throw it }
            return inner.launch(request)
        }
    }

    fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.yield()
        }
        return condition()
    }
}

class RefinePipelineTest {
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
    private lateinit var worktrees: WorktreeManager
    private lateinit var configDir: Path
    private lateinit var worktreeRoot: Path

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("refine.db"))
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

    private fun card(id: String, column: Column = Column.READY_FOR_REFINEMENT) {
        val event = Event(EventId("seed-$id"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Pipeline $id"))
        store.recordChange(id, Card.create("Pipeline $id", column), event)
    }

    private fun pipeline(runtime: Runtime, gates: RefineGates, maxRounds: Int = 3, commit: WorktreeCommit = WorktreeCommit.None) = RefinePipeline(
        project = project,
        store = store,
        recorder = recorder,
        worktrees = worktrees,
        sessions = sessions,
        mcpPort = 4317,
        runtime = runtime,
        costRecorder = RunCostRecorder(store, recorder, RunCostFixtures.prices(), clock, log),
        gates = gates,
        configDir = configDir,
        clock = clock,
        model = "model-a",
        maxRounds = maxRounds,
        log = log,
        commit = commit,
    )

    private fun column(id: String): Column = requireNotNull(store.card(id)).column

    private fun succeeding(times: Int) = FakeRuntime(List(times) { SessionScript.Succeed })

    @Test
    fun `AC-1 a card in Ready for Refinement moves to Refinement and a refine run starts, events with actor`() {
        card("c1")
        val runtime = succeeding(1)

        pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))).run("c1")

        assertThat(runtime.launched).hasSize(1)
        assertThat(runtime.launched.single().stage).isEqualTo("refine")
        assertThat(runtime.launched.single().worktree).isEqualTo(worktreeRoot.resolve("alpha").resolve("c1"))
        val moves = store.events("c1").filter { it.type == EventType.CARD_MOVED }
        assertThat(moves.map { it.payload["to"] }).containsExactly(Column.REFINEMENT.name, Column.SPEC_REVIEW.name)
        assertThat(moves.map { it.actor }).containsOnly(Actor.Factory("refine"))
        assertThat(store.events("c1").count { it.type == EventType.RUN_COST_RECORDED }).isEqualTo(1)
    }

    @Test
    fun `AC-2 a finished run with every gate green moves the card to Spec Review`() {
        card("c1")
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))

        pipeline(succeeding(1), gates).run("c1")

        assertThat(column("c1")).isEqualTo(Column.SPEC_REVIEW)
        assertThat(gates.evaluated).hasSize(1)
    }

    @Test
    fun `AC-1 the refine session prompt carries the skill, the card and the spec template`() {
        card("c1")
        val runtime = succeeding(1)

        pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))).run("c1")

        val prompt = runtime.launched.single().prompt
        assertThat(prompt).startsWith(StageSkills.load("refine").trimEnd()).contains("`c1`")
        assertThat(prompt).contains("## Modelo da spec").contains("- **AC-1** —").contains("| AC | Layer | Test file |")
    }

    @Test
    fun `AC-2 the spec is committed once the gates are green and not while one is red`() {
        card("green")
        card("red")
        val commits = CopyOnWriteArrayList<String>()
        val commit = WorktreeCommit { _, message -> commits.add(message) }

        pipeline(succeeding(1), RefineFixtures.ScriptedGates(listOf(RefineFixtures.green)), commit = commit).run("green")
        pipeline(succeeding(1), RefineFixtures.ScriptedGates(listOf(RefineFixtures.red)), maxRounds = 1, commit = commit).run("red")

        assertThat(commits).hasSize(1)
        assertThat(commits.single()).startsWith("docs(spec):")
    }

    @Test
    fun `AC-3 the agent saying done does not advance the card while a gate is red`() {
        card("c1")
        val runtime = succeeding(1)

        pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.red)), maxRounds = 1).run("c1")

        assertThat(column("c1")).isNotEqualTo(Column.SPEC_REVIEW)
        assertThat(store.events("c1").flatMap { it.payload.values }).doesNotContain(Column.SPEC_REVIEW.name)
    }

    @Test
    fun `AC-4 red gates with rounds left start a new run`() {
        card("c1")
        val runtime = succeeding(2)
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.red, RefineFixtures.green))

        pipeline(runtime, gates, maxRounds = 3).run("c1")

        assertThat(runtime.launched).hasSize(2)
        assertThat(gates.evaluated).hasSize(2)
        assertThat(column("c1")).isEqualTo(Column.SPEC_REVIEW)
    }

    @Test
    fun `the second run carries what the red gates said and the first run carries none`() {
        card("c1")
        val runtime = succeeding(2)
        val weightRed = listOf(GateResult("weight", false, "peso declarado light abaixo do calculado standard"))
        val gates = RefineFixtures.ScriptedGates(listOf(weightRed, RefineFixtures.green))

        pipeline(runtime, gates, maxRounds = 3).run("c1")

        val (first, second) = runtime.launched.map { it.prompt }
        assertThat(first).doesNotContain("Gates que falharam")
        assertThat(second).contains("Gates que falharam").contains("weight: peso declarado light abaixo do calculado standard")
    }

    @Test
    fun `AC-5 red gates after the last round block the card with the failed gates as reason and start no new run`() {
        card("c1")
        val runtime = succeeding(5)
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.red))

        pipeline(runtime, gates, maxRounds = 3).run("c1")

        assertThat(runtime.launched).hasSize(3)
        assertThat(gates.evaluated).hasSize(3)
        val blocked = store.events("c1").single { it.type == EventType.CARD_BLOCKED }
        assertThat(blocked.actor).isEqualTo(Actor.Factory("refine"))
        val reason = requireNotNull(blocked.payload["reason"])
        assertThat(reason).contains("test-plan").contains("scope").doesNotContain("weight")
        assertThat(column("c1")).isNotEqualTo(Column.SPEC_REVIEW)
    }

    @Test
    fun `AC-7 the MCP config lives outside the worktree with mode 0600 while the run lasts and is gone with its session after`() {
        card("c1")
        val seen = mutableListOf<Triple<Path, String, String>>()
        val runtime = RefineFixtures.ProbingRuntime(succeeding(1)) { request ->
            val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(request.mcpConfig))
            seen.add(Triple(request.mcpConfig, perms, Files.readString(request.mcpConfig)))
        }

        pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))).run("c1")

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
    fun `AC-7 the session is revoked and the config deleted when the run throws`() {
        card("c1")
        val seen = mutableListOf<String>()
        val runtime = RefineFixtures.ProbingRuntime(succeeding(1), failure = IllegalStateException("boom")) { request ->
            seen.add(Files.readString(request.mcpConfig))
        }

        runCatching { pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))).run("c1") }

        val token = tokenIn(seen.single())
        assertThat(configDir.listDirectoryEntries()).isEmpty()
        assertThat(sessions.find(token)).isNull()
        assertNoLeak(token)
    }

    @Test
    fun `AC-7 the token is valid for the session while the run lasts`() {
        card("c1")
        var live: McpSession? = null
        val runtime = RefineFixtures.ProbingRuntime(succeeding(1)) { request ->
            live = sessions.find(tokenIn(Files.readString(request.mcpConfig)))
        }

        pipeline(runtime, RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))).run("c1")

        assertThat(live).isEqualTo(McpSession(project.repo, "refine", "c1"))
    }

    @Test
    fun `AC-8 a run that throws releases the card lock of the pool`() {
        assertThat(lockReleasedAfter(failure = IllegalStateException("boom"), script = emptyList())).isTrue()
    }

    @Test
    fun `AC-8 a run that times out releases the card lock of the pool`() {
        assertThat(lockReleasedAfter(failure = null, script = listOf(SessionScript.Hang))).isTrue()
    }

    private fun lockReleasedAfter(failure: RuntimeException?, script: List<SessionScript>): Boolean {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val hold = CountDownLatch(1)
        val runtime = RefineFixtures.ProbingRuntime(FakeRuntime(script), hold = hold, failure = failure)
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))
        val launcher = RefineStageLauncher({ pipeline(runtime, gates) }, pool)
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.REFINE), store, pool, launcher, DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))
            assertThat(runtime.started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(pool.isLocked("c1")).isTrue()
            hold.countDown()
            return RefineFixtures.await { !pool.isLocked("c1") }
        } finally {
            hold.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `AC-1 the dispatcher moves a ready card to Refinement through the launcher and the run holds the lock until it ends`() {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val hold = CountDownLatch(1)
        val runtime = RefineFixtures.ProbingRuntime(succeeding(1))
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.green), hold)
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.REFINE), store, pool, RefineStageLauncher({ pipeline(runtime, gates) }, pool),
            DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))
            assertThat(runtime.started.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(column("c1")).isEqualTo(Column.REFINEMENT)
            assertThat(pool.isLocked("c1")).isTrue()
            hold.countDown()
            assertThat(RefineFixtures.await { column("c1") == Column.SPEC_REVIEW && !pool.isLocked("c1") }).isTrue()
        } finally {
            hold.countDown()
            dispatcher.close()
        }
    }

    private fun finishedCards(failure: RuntimeException?): List<String> {
        card("c1")
        val pool = RunPool(2, store, clock, ProcessProbe { _, _ -> true }, log, recorder = recorder)
        val runtime = RefineFixtures.ProbingRuntime(succeeding(1), failure = failure)
        val gates = RefineFixtures.ScriptedGates(listOf(RefineFixtures.green))
        val finished = CopyOnWriteArrayList<String>()
        val launcher = RefineStageLauncher({ pipeline(runtime, gates) }, pool, onRunFinished = { finished.add(it) })
        val dispatcher = DispatcherFixtures.dispatcherFor(
            setOf(DispatchStage.REFINE), store, pool, launcher, DispatcherFixtures.FakePause(), AlwaysWorkTime, clock, log,
        )
        try {
            assertThat(dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))
            assertThat(RefineFixtures.await { finished.isNotEmpty() && !pool.isLocked("c1") }).isTrue()
            return finished.toList()
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `AC-2 a finished refine run tells the cost budget which card ended`() {
        assertThat(finishedCards(failure = null)).containsExactly("c1")
    }

    @Test
    fun `AC-2 a refine run that throws still tells the cost budget which card ended`() {
        assertThat(finishedCards(failure = IllegalStateException("boom"))).containsExactly("c1")
    }

    private fun composedWithTwoProjects(): Triple<ComposedDaemon, Path, Path> {
        val daemon = ComposedDaemon(Files.createDirectories(dir.resolve("composed-${System.nanoTime()}")))
        val alpha = daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
        val beta = daemon.repo("beta")
        daemon.prices()
        return Triple(daemon, alpha, beta)
    }

    /** The refine run reached its end (moved on, blocked or failed), so nothing writes under the temp dir any more. */
    private fun refineEnded(daemon: ComposedDaemon, cardId: String) =
        RefineFixtures.await {
            listOf("refine_moved", "refine_blocked", "refine_failed").any { name ->
                daemon.log.events(name).any { it.second["card"] == cardId && (name != "refine_moved" || it.second["to"] != Column.REFINEMENT.name) }
            }
        }

    private fun refineFailed(daemon: ComposedDaemon, cardId: String) =
        RefineFixtures.await { daemon.log.events("refine_failed").any { it.second["card"] == cardId } }

    @Test
    fun `AC-5 with two projects registered the card goes to the project of its card_project link`() {
        val (daemon, _, beta) = composedWithTwoProjects()
        daemon.use {
            daemon.card("c1")
            daemon.store.assignProject("c1", beta.toString())

            assertThat(daemon.cycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))

            assertThat(RefineFixtures.await { daemon.store.card("c1")?.column == Column.REFINEMENT }).isTrue()
            assertThat(daemon.store.projectOf("c1")).isEqualTo(beta.toString())
        }
    }

    @Test
    fun `AC-5 with two projects a card without link is not refined, fails with its id and links to no project`() {
        val (daemon, alpha, beta) = composedWithTwoProjects()
        daemon.use {
            daemon.card("c2")

            assertThat(daemon.cycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c2"))

            assertThat(refineFailed(daemon, "c2")).isTrue()
            assertThat(daemon.store.card("c2")?.column).isEqualTo(Column.READY_FOR_REFINEMENT)
            assertThat(daemon.store.projectOf("c2")).isNull()
            assertThat(daemon.log.everything()).doesNotContain(alpha.toString()).doesNotContain(beta.toString())
        }
    }

    @Test
    fun `AC-5 a card without link and exactly one registered project uses it and records the link`() {
        ComposedDaemon(Files.createDirectories(dir.resolve("single"))).use { daemon ->
            val alpha = daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c3")

            assertThat(daemon.cycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c3"))

            assertThat(refineEnded(daemon, "c3")).isTrue()
            assertThat(RefineFixtures.await { daemon.store.card("c3")?.column == Column.REFINEMENT }).isTrue()
            assertThat(daemon.store.projectOf("c3")).isEqualTo(alpha.toString())
        }
    }

    @Test
    fun `AC-5 a link to a repository that is not registered fails with the card id and falls back to no other project`() {
        ComposedDaemon(Files.createDirectories(dir.resolve("unregistered"))).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c4")
            daemon.store.assignProject("c4", "/repos/nowhere")

            assertThat(daemon.cycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c4"))

            assertThat(refineFailed(daemon, "c4")).isTrue()
            assertThat(daemon.store.card("c4")?.column).isEqualTo(Column.READY_FOR_REFINEMENT)
            assertThat(daemon.store.projectOf("c4")).isEqualTo("/repos/nowhere")
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
