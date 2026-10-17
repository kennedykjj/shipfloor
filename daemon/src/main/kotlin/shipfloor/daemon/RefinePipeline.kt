package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.store.CardEventStore
import shipfloor.store.SpecStore

/** Single source of the default number of review/fix rounds for the refine, develop and validate pipelines. */
const val DEFAULT_MAX_ROUNDS = 3

data class GateResult(val name: String, val passed: Boolean, val reason: String?)

/** Judges the worktree after a refine run; the verdict is the gates', never the agent's report. */
fun interface RefineGates {
    fun evaluate(cardId: String, worktree: Path): List<GateResult>
}

/** Spec test plan, spec weight and scope (only spec and plan files may change) over the card's worktree. */
class SpecScopeRefineGates(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val changedPaths: ChangedPaths,
    private val clock: Clock,
    private val baseRevision: String = "origin/main",
    private val specs: SpecStore? = null,
) : RefineGates {

    override fun evaluate(cardId: String, worktree: Path): List<GateResult> {
        val changed = (changedPaths.between(worktree, baseRevision) + changedPaths.untracked(worktree)).distinct()
        val results = specGates(cardId, worktree, changed) + scopeGate(changed)
        val card = store.card(cardId)
        if (card != null) results.forEach { record(cardId, card, it) }
        return results
    }

    /** A spec that passes its gates is registered for the card, so that `spec_get` and the divergence check read it. */
    private fun specGates(cardId: String, worktree: Path, changed: List<String>): List<GateResult> {
        val specPath = changed.filter { it.startsWith(SPEC_DIR) && it.endsWith(".md") }
            .maxByOrNull { Files.getLastModifiedTime(worktree.resolve(it)).toMillis() }
        if (specPath == null) {
            return listOf(GateResult("test-plan", false, "no spec file written under $SPEC_DIR"), GateResult("weight", true, null))
        }
        val layers = project.layers.map { SpecLayer(it.name, it.path, listOf("${it.path.trimEnd('/')}/**")) }
        val specText = Files.readString(worktree.resolve(specPath))
        val check = SpecGates.check(specText, layers)
        if (check.findings.isEmpty()) specs?.write(cardId, specText)
        return SpecGate.entries.map { gate ->
            val findings = check.findings.filter { it.gate == gate }
            val name = gate.name.lowercase().replace('_', '-')
            GateResult(name, findings.isEmpty(), findings.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.message })
        }
    }

    private fun scopeGate(changed: List<String>): GateResult =
        when (val verdict = ScopeGate.check(project, changed, emptySet())) {
            ScopeVerdict.Pass -> GateResult("scope", true, null)
            is ScopeVerdict.Violations ->
                GateResult("scope", false, "forbidden=${verdict.forbidden.size} outside=${verdict.outsideLayers.size}")
        }

    private fun record(cardId: String, card: Card, result: GateResult) {
        val payload = buildMap {
            put("gate", result.name)
            put("verdict", if (result.passed) "PASS" else "FAIL")
            result.reason?.let { put("summary", it) }
        }
        recorder.record(
            cardId,
            card,
            Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Factory("gate"), EventType.GATE_FINISHED, payload),
        )
    }

    private companion object {
        const val SPEC_DIR = "docs/superpowers/specs/"
    }
}

/** Writes the MCP client config (it carries the session token) under [configDir] with mode 0600; the caller deletes it. */
fun writeMcpConfig(configDir: Path, mcpPort: Int, token: String): Path {
    Files.createDirectories(configDir)
    val file = configDir.resolve("mcp-${UUID.randomUUID()}.json")
    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    val json = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject("shipfloor") {
                put("type", "http")
                put("url", "http://127.0.0.1:$mcpPort/mcp")
                putJsonObject("headers") { put("Authorization", "Bearer $token") }
            }
        }
    }
    Files.writeString(file, json.toString())
    return file
}

/** One pass of the refine stage: runs the agent, judges it with gates, and moves the card on the gates' word. */
@Suppress("LongParameterList")
class RefinePipeline(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val worktrees: WorktreeManager,
    private val sessions: McpSessions,
    private val mcpPort: Int,
    private val runtime: Runtime,
    private val costRecorder: RunCostRecorder,
    private val gates: RefineGates,
    private val configDir: Path,
    private val clock: Clock,
    private val model: String,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val log: DaemonLog = StderrDaemonLog,
    private val runtimeName: String = "claude",
    private val commit: WorktreeCommit = WorktreeCommit.None,
) {
    init {
        require(maxRounds > 0) { "maxRounds must be positive, got $maxRounds" }
    }

    fun run(cardId: String) {
        val card = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        when (card.column) {
            Column.READY_FOR_REFINEMENT -> move(cardId, Column.REFINEMENT)
            Column.REFINEMENT -> Unit
            else -> {
                log.log("refine_skipped", mapOf("card" to cardId, "column" to card.column.name))
                return
            }
        }
        val worktree = worktrees.ensure(project, cardId, card.title)
        var failed: List<GateResult> = emptyList()
        for (round in 1..maxRounds) {
            runSession(cardId, worktree.path, feedbackOf(failed))
            failed = gates.evaluate(cardId, worktree.path).filter { !it.passed }
            log.log("refine_round", mapOf("card" to cardId, "round" to round.toString(), "failed" to failed.size.toString()))
            if (failed.isEmpty()) {
                commitSpec(cardId, worktree.path)
                move(cardId, Column.SPEC_REVIEW)
                return
            }
        }
        block(cardId, failed)
    }

    private fun commitSpec(cardId: String, worktree: Path) {
        val card = store.card(cardId) ?: return
        val message = ConventionText.commitSubject(
            project.policies.conventions.commitFormat, "docs", "spec", card.title, cardId, ConventionText.ticketOf(card.references),
        )
        if (!commit.commitAll(worktree, message)) log.log("refine_commit_failed", mapOf("card" to cardId))
    }

    /** What the gates said about the previous round, so the agent corrects it instead of guessing; null on the first round. */
    private fun feedbackOf(failed: List<GateResult>): String? =
        failed.take(MAX_FEEDBACK_GATES).joinToString("\n") { "${it.name}: ${it.reason.orEmpty().take(MAX_FEEDBACK_CHARS)}" }
            .takeIf { it.isNotBlank() }

    private fun runSession(cardId: String, worktree: Path, feedback: String?) {
        val token = sessions.open(project.repo, "refine", cardId)
        val config = writeConfig(token)
        try {
            val result = runtime.launch(SessionRequest("refine", worktree, model, config, StagePrompt.of("refine", cardId, feedback = feedback)))
            recordCost(cardId, result)
        } finally {
            runCatching { Files.deleteIfExists(config) }
            sessions.close(token)
        }
    }

    private fun writeConfig(token: String): Path = writeMcpConfig(configDir, mcpPort, token)

    private fun recordCost(cardId: String, result: SessionResult) {
        costRecorder.record(cardId, UUID.randomUUID().toString(), runtimeName, "refine", model, result)
    }

    private fun move(cardId: String, to: Column) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("refine"),
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to current.column.name, "to" to to.name),
        )
        recorder.record(cardId, current.movedTo(to), event)
        log.log("refine_moved", mapOf("card" to cardId, "to" to to.name))
    }

    private fun block(cardId: String, failed: List<GateResult>) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val reason = "gates_failed: " + failed.joinToString(",") { it.name }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("refine"),
            type = EventType.CARD_BLOCKED,
            payload = mapOf("reason" to reason),
        )
        recorder.record(cardId, current, event)
        log.log("refine_blocked", mapOf("card" to cardId, "reason" to reason))
    }
}

/**
 * Runs a stage pipeline on its own thread; the card's pool lock is released whatever the outcome.
 * [stageName] names the thread and the failure log event (`<stageName>_failed`).
 */
class PipelineStageLauncher(
    private val stageName: String,
    private val pool: RunPool,
    private val log: DaemonLog = StderrDaemonLog,
    private val onRunFinished: (String) -> Unit = {},
    private val run: (String) -> Unit,
) {

    fun start(cardId: String): LaunchedRun {
        val thread = Thread({ execute(cardId) }, "$stageName-$cardId").apply { isDaemon = true }
        thread.start()
        val handle = ProcessHandle.current()
        return LaunchedRun(handle.pid(), handle.info().startInstant().orElse(Instant.now()))
    }

    private fun execute(cardId: String) {
        try {
            awaitLock(cardId)
            run(cardId)
        } catch (e: Exception) {
            log.log("${stageName}_failed", mapOf("card" to cardId, "error" to e.javaClass.simpleName))
        } finally {
            try {
                onRunFinished(cardId)
            } catch (e: Exception) {
                log.log("run_finished_hook_failed", mapOf("card" to cardId, "error" to e.javaClass.simpleName))
            } finally {
                pool.release(cardId)
            }
        }
    }

    /** The dispatcher takes the lock right after [launch] returns; releasing before that would leak it. */
    private fun awaitLock(cardId: String) {
        val deadline = System.nanoTime() + LOCK_WAIT_NANOS
        while (!pool.isLocked(cardId) && System.nanoTime() < deadline) Thread.sleep(LOCK_POLL_MILLIS)
    }

    private companion object {
        const val LOCK_WAIT_NANOS = 5_000_000_000L
        const val LOCK_POLL_MILLIS = 5L
    }
}

/** Starts the refine pipeline on its own thread; the card's pool lock is released whatever the outcome. */
class RefineStageLauncher(
    private val pipelineFor: (String) -> RefinePipeline,
    pool: RunPool,
    log: DaemonLog = StderrDaemonLog,
    onRunFinished: (String) -> Unit = {},
) : StageLauncher {

    private val launcher = PipelineStageLauncher(
        "refine", pool, log, onRunFinished,
    ) { cardId -> pipelineFor(cardId).run(cardId) }

    override fun launch(stage: DispatchStage, cardId: String): LaunchedRun {
        require(stage == DispatchStage.REFINE) { "RefineStageLauncher cannot launch stage $stage (card $cardId)" }
        return launcher.start(cardId)
    }
}
