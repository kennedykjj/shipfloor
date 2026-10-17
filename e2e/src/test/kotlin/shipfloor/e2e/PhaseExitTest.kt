package shipfloor.e2e

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.cli.runCli
import shipfloor.core.Actor
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.daemon.BranchSync
import shipfloor.daemon.CardCommands
import shipfloor.daemon.CiPause
import shipfloor.daemon.PullRequestDelivery
import shipfloor.daemon.PushResult
import shipfloor.daemon.WorktreeHead
import shipfloor.daemon.DaemonLog
import shipfloor.daemon.DevelopGates
import shipfloor.daemon.DevelopPipeline
import shipfloor.daemon.GateResult
import shipfloor.daemon.McpSessions
import shipfloor.daemon.MergeResult
import shipfloor.daemon.ModelStepUp
import shipfloor.daemon.OwnerProof
import shipfloor.daemon.PriceTable
import shipfloor.daemon.Project
import shipfloor.daemon.ProjectLayer
import shipfloor.daemon.RefineGates
import shipfloor.daemon.RefinePipeline
import shipfloor.daemon.RunCostRecorder
import shipfloor.daemon.SocketServer
import shipfloor.daemon.ValidateGateResults
import shipfloor.daemon.ValidateGates
import shipfloor.daemon.ValidatePipeline
import shipfloor.daemon.WorktreeGit
import shipfloor.daemon.WorktreeManager
import shipfloor.daemon.resolveSocketPath
import shipfloor.ports.ChecksState
import shipfloor.ports.FakeForge
import shipfloor.ports.FakeRuntime
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database

/** Phase 2 exit: a card goes from Inbox to Staged with a fake runtime and fake gates; only the owner's decisions intervene. */
class PhaseExitTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val silent = DaemonLog { _, _ -> }
    private val model = "model-a"
    private val project by lazy {
        Project("alpha", dir.resolve("repo").toString(), listOf(ProjectLayer("daemon", "daemon/")), ProjectPolicies.DEFAULT)
    }

    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var commands: CardCommands
    private lateinit var server: SocketServer
    private lateinit var stepUp: ModelStepUp
    private lateinit var env: Map<String, String>

    @BeforeEach
    fun start() {
        Files.createDirectories(dir.resolve("repo"))
        env = mapOf("XDG_RUNTIME_DIR" to dir.resolve("run").toString())
        Files.createDirectories(dir.resolve("run"))
        val socket = resolveSocketPath(env)
        database = Database.open(dir.resolve("phase.db"))
        store = CardEventStore(database)
        val proof = OwnerProof.create(socket.resolveSibling("owner.token"), SecureRandom())
        commands = CardCommands(store, clock, proof, silent)
        server = SocketServer.start(socket, log = silent, commands = commands)
        stepUp = ModelStepUp(listOf(model), store)
    }

    @AfterEach
    fun stop() {
        stepUp.close()
        server.close()
        commands.events.close()
        database.close()
    }

    private fun cli(vararg args: String): String {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runCli(args.toList(), "test", { out.appendLine(it) }, { err.appendLine(it) }, env = env)
        assertThat(code).describedAs("exit of ${args.toList()}: $err").isZero()
        return out.toString()
    }

    private fun gate(cardId: String, names: List<String>): List<GateResult> =
        names.map { name ->
            val card = requireNotNull(store.card(cardId))
            val payload = mapOf("gate" to name, "verdict" to "PASS")
            val event = Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Factory("gate"), EventType.GATE_FINISHED, payload)
            commands.recorder.record(cardId, card, event)
            GateResult(name, true, null)
        }

    private class DirectoryWorktreeGit : WorktreeGit {
        private val known = mutableSetOf<Path>()

        override fun addWorktree(repo: Path, dir: Path, branch: String) {
            Files.createDirectories(dir)
            known.add(dir)
        }

        override fun hasWorktree(repo: Path, dir: Path): Boolean = dir in known

        override fun isDirty(dir: Path): Boolean = false

        override fun removeWorktree(repo: Path, dir: Path) {
            known.remove(dir)
        }

        override fun deleteBranch(repo: Path, branch: String) = Unit
    }

    private fun greenDelivery(): PullRequestDelivery {
        val forge = FakeForge()
        forge.scriptChecks("sha-1", ChecksState.Passed)
        return PullRequestDelivery(forge, { _, _ -> PushResult.Pushed }, WorktreeHead { "sha-1" }, CiPause { })
    }

    /** The fake runtime plus what a real session leaves behind: a priced transcript and, for validate, the review report. */
    private inner class RecordingRuntime(private val inner: Runtime, private val cardId: () -> String) : Runtime {
        private var sessions = 0

        override fun launch(request: SessionRequest): SessionResult {
            inner.launch(request)
            val transcript = dir.resolve("transcripts").resolve("${request.stage}-${sessions++}.jsonl")
            Files.createDirectories(transcript.parent)
            Files.writeString(
                transcript,
                """{"type":"assistant","message":{"id":"m-$sessions","model":"$model","usage":{"input_tokens":1000,"output_tokens":500}}}""" + "\n",
            )
            if (request.stage == "validate") {
                val id = cardId()
                val payload = mapOf("column" to Column.VALIDATION.name, "summary" to "VERDICT: APPROVED")
                val event = Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Agent("validate"), EventType.AGENT_REPORTED, payload)
                commands.recorder.record(id, requireNotNull(store.card(id)), event)
            }
            return SessionResult.Exited(0, transcript)
        }
    }

    @Test
    fun `AC-6 a card goes from Inbox to Staged with only the owner decisions, cost recorded and every gate an event`() {
        val prices = PriceTable.parse("models:\n  $model:\n    input: 3.00\n    output: 15.00\n")
        val costs = RunCostRecorder(store, commands.recorder, prices, clock, silent)
        val worktrees = WorktreeManager(dir.resolve("worktrees"), DirectoryWorktreeGit(), silent)
        val sessions = McpSessions(clock)
        val configDir = dir.resolve("mcp")
        var cardId = ""
        val runtime = RecordingRuntime(FakeRuntime(List(4) { SessionScript.Succeed })) { cardId }

        val refineGates = RefineGates { id, _ -> gate(id, listOf("test-plan", "weight", "scope")) }
        val developGates = object : DevelopGates {
            override fun red(cardId: String, worktree: Path) = gate(cardId, listOf("daemon-red"))

            override fun layers(worktree: Path) = project.layers

            override fun green(cardId: String, layer: ProjectLayer, worktree: Path) =
                gate(cardId, listOf("daemon-tests", "daemon-build", "scope"))
        }
        val validateGates = ValidateGates { id, _ -> ValidateGateResults(gate(id, listOf("daemon-build", "daemon-tests", "scope"))) }

        val refine = RefinePipeline(
            project, store, commands.recorder, worktrees, sessions, 4317, runtime, costs, refineGates, configDir, clock, model, log = silent,
        )
        val develop = DevelopPipeline(
            project, store, commands.recorder, worktrees, sessions, 4317, runtime, costs, developGates, stepUp, configDir, clock, log = silent,
        )
        val validate = ValidatePipeline(
            project, store, commands.recorder, worktrees, sessions, 4317, runtime, costs,
            BranchSync { MergeResult.Merged }, validateGates, configDir, clock, model, delivery = greenDelivery(), log = silent,
        )

        cardId = cli("card", "create", "Ship the phase").trim().substringBefore(" ")
        cli("card", "move", cardId, "ready-for-refinement")
        refine.run(cardId)
        assertThat(store.card(cardId)?.column).isEqualTo(Column.SPEC_REVIEW)
        cli("card", "approve", cardId)
        develop.run(cardId)
        assertThat(store.card(cardId)?.column).isEqualTo(Column.VALIDATION)
        validate.run(cardId)

        assertThat(cli("card", "show", cardId).trim()).isEqualTo("$cardId  STAGED  Ship the phase")
        val events = store.events(cardId)
        val reached = events.filter { it.type == EventType.CARD_MOVED || it.type == EventType.OWNER_APPROVED }.map { it.payload["to"] }
        assertThat(reached).containsExactly(
            "READY_FOR_REFINEMENT", "REFINEMENT", "SPEC_REVIEW", "READY_FOR_DEV", "CODING", "VALIDATION", "STAGED",
        )
        assertThat(events.filter { it.actor == Actor.Owner }.map { it.type to it.payload["to"] }).containsExactly(
            EventType.CARD_CREATED to null,
            EventType.CARD_MOVED to "READY_FOR_REFINEMENT",
            EventType.OWNER_APPROVED to "READY_FOR_DEV",
        )
        assertThat(events.filter { it.type == EventType.CARD_BLOCKED }).isEmpty()

        val gates = events.filter { it.type == EventType.GATE_FINISHED }
        assertThat(gates.map { it.payload["gate"] }).containsExactly(
            "test-plan", "weight", "scope", "daemon-red", "daemon-tests", "daemon-build", "scope", "daemon-build", "daemon-tests", "scope",
        )
        assertThat(gates.map { it.payload["verdict"] }).containsOnly("PASS")

        val costEvents = events.filter { it.type == EventType.RUN_COST_RECORDED }
        assertThat(costEvents.map { it.payload["stage"] }).containsExactly("refine", "develop", "develop", "validate")
        assertThat(costEvents.map { it.payload["status"] }).containsOnly("KNOWN")
        assertThat(costEvents.map { BigDecimal(requireNotNull(it.payload["cost_usd"])) }).allMatch { it.signum() > 0 }

        val streamed = cli("events", "--after", "0", "--count", "${store.eventsAfter(0).size}")
        assertThat(streamed.lines().filter { it.isNotBlank() }).hasSize(store.eventsAfter(0).size)
        assertThat(streamed).contains("OWNER_APPROVED").contains("\"actor\":\"owner\"")
    }
}
