package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.Milestone
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.store.CardEventStore

/** Judges the card's worktree before the review; the verdict is the gates', never the agent's report. */
fun interface ValidateGates {
    fun evaluate(cardId: String, worktree: Path): ValidateGateResults
}

/**
 * One pass of the validate stage: syncs the branch with main, reruns the gates, runs the review and moves the card on
 * the word of the gates and of the review's verdict line.
 */
@Suppress("LongParameterList")
class ValidatePipeline(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val worktrees: WorktreeManager,
    private val sessions: McpSessions,
    private val mcpPort: Int,
    private val runtime: Runtime,
    private val costRecorder: RunCostRecorder,
    private val branchSync: BranchSync,
    private val gates: ValidateGates,
    private val configDir: Path,
    private val clock: Clock,
    private val model: String,
    private val delivery: PullRequestDelivery,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val log: DaemonLog = StderrDaemonLog,
    private val runtimeName: String = "claude",
    private val changedPaths: ChangedPaths = GitChangedPaths(),
    private val conflicts: ConflictResolver? = null,
    private val milestones: ExternalMilestones? = null,
    private val files: ProjectFileSource = NoProjectFiles,
) {
    private val outcome = ValidationOutcome(store, recorder, project.layers.map { it.name }.toSet(), clock)

    init {
        require(maxRounds > 0) { "maxRounds must be positive, got $maxRounds" }
    }

    fun run(cardId: String) {
        val card = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        if (card.column != Column.VALIDATION) {
            log.log("validate_skipped", mapOf("card" to cardId, "column" to card.column.name))
            return
        }
        val worktree = worktrees.ensure(project, cardId, card.title)
        when (branchSync.mergeMain(worktree.path)) {
            MergeResult.Merged -> Unit
            MergeResult.Conflict -> resolveConflict(cardId, worktree.path)?.let { return block(cardId, it) }
            MergeResult.Failed -> return block(cardId, "git_failed")
        }
        for (round in 1..maxRounds) {
            val results = gates.evaluate(cardId, worktree.path)
            val report = review(cardId, worktree.path)
            val decision = try {
                report?.let { decide(cardId, worktree, it, results.state) } ?: ValidationDecision.Malformed
            } catch (e: PushRefusedException) {
                return block(cardId, "push_refused")
            }
            log.log("validate_round", mapOf("card" to cardId, "round" to round.toString(), "decision" to decision.javaClass.simpleName))
            when (decision) {
                ValidationDecision.Staged, is ValidationDecision.Returned -> return
                is ValidationDecision.CiUnresolved -> return block(cardId, decision.reason)
                ValidationDecision.Held -> return holdOrRework(cardId, worktree.path, results)
                ValidationDecision.Malformed -> Unit
            }
        }
        block(cardId, "review_malformed")
    }

    /**
     * A build or test gate that failed (what a merge of the base can break) sends the card back to Coding with the
     * gate output, up to [MAX_REWORKS] times; any other failed gate, or too many returns, blocks the card.
     */
    private fun holdOrRework(cardId: String, worktree: Path, results: ValidateGateResults) {
        val failed = results.results.filter { !it.passed }
        val reworkable = failed.isNotEmpty() && failed.all { it.name.startsWith("build-") || it.name.startsWith("test-") }
        val returns = store.events(cardId).count {
            it.type == EventType.CARD_MOVED && it.actor == Actor.Factory(STAGE) && it.payload["to"] == Column.CODING.name
        }
        if (reworkable && returns < MAX_REWORKS) {
            val reason = "gates_failed:\n" + failed.joinToString("\n") { "${it.name}: ${it.reason.orEmpty().take(REWORK_CHARS)}" }
            if (outcome.returnForGates(cardId, layerWithMostChanges(worktree), reason.take(REWORK_TOTAL_CHARS))) {
                log.log("validate_rework", mapOf("card" to cardId, "gates" to results.failedNames.joinToString(",")))
                return
            }
        }
        block(cardId, "gates_failed: " + results.failedNames.joinToString(","))
    }

    /** Null when the conflict is resolved and the gates may run; otherwise the reason that blocks the card. */
    private fun resolveConflict(cardId: String, worktree: Path): String? {
        val resolver = conflicts ?: return "merge_conflict"
        return when (resolver.resolve(cardId, worktree)) {
            is ConflictOutcome.Resolved -> null
            ConflictOutcome.OverLimit, ConflictOutcome.Failed -> "merge_conflict"
            ConflictOutcome.RoundsExhausted ->
                "conflict_rounds_exhausted rounds=" + store.events(cardId).count { ConflictResolution.isRound(it) }
        }
    }

    private fun decide(cardId: String, worktree: CardWorktree, report: String, gates: GatesState): ValidationDecision =
        outcome.apply(
            cardId, report, gates,
            ci = { deliver(cardId, worktree) },
            head = { delivery.headOf(worktree.path) },
            redLayer = { layerWithMostChanges(worktree.path) },
        )

    private fun deliver(cardId: String, worktree: CardWorktree): CiResult {
        val card = store.card(cardId)
        val subject = card?.title ?: cardId
        val ticket = card?.let { ConventionText.ticketOf(it.references) }
        val conventions = project.policies.conventions
        val scope = project.layers.firstOrNull()?.name ?: ""
        val title = ConventionText.pullRequestTitle(conventions.commitFormat, scope, subject, cardId, ticket)
        val body = ConventionText.pullRequestBody(prTemplate(cardId, conventions.prTemplate), conventions.language, cardId, ticket)
        val result = delivery.deliver(cardId, worktree.branch, title, body, worktree.path)
        if (result is CiResult.Green) milestones?.reached(cardId, Milestone.PR_OPENED, result.pr.toString())
        return result
    }

    /** The template as committed on main; null (minimal body) when it is absent or cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    private fun prTemplate(cardId: String, path: String): String? = try {
        files.read(Path.of(project.repo), path)
    } catch (e: RuntimeException) {
        log.log("pr_template_unreadable", mapOf("card" to cardId, "reason" to e.javaClass.simpleName))
        null
    }

    private fun layerWithMostChanges(worktree: Path): String {
        val changed = try {
            changedPaths.between(worktree, "origin/main")
        } catch (e: ChangedPathsException) {
            emptyList()
        }
        val layers = project.layers
        return layers.maxByOrNull { layer -> changed.count { it.startsWith(layer.path) } }?.name ?: layers.first().name
    }

    /** Runs one review session and returns the report the agent wrote during it, or null when it wrote none. */
    private fun review(cardId: String, worktree: Path): String? {
        val before = store.lastSeq(cardId, EventType.AGENT_REPORTED) ?: 0L
        val token = sessions.open(project.repo, "validate", cardId)
        val config = writeMcpConfig(configDir, mcpPort, token)
        try {
            val result = runtime.launch(SessionRequest("validate", worktree, model, config, StagePrompt.of("validate", cardId)))
            costRecorder.record(cardId, UUID.randomUUID().toString(), runtimeName, "validate", model, result)
        } finally {
            runCatching { Files.deleteIfExists(config) }
            sessions.close(token)
        }
        return store.eventsAfter(before)
            .lastOrNull { it.cardId == cardId && it.event.type == EventType.AGENT_REPORTED }
            ?.event?.payload?.get("summary")
    }

    private fun block(cardId: String, reason: String) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory(STAGE),
            type = EventType.CARD_BLOCKED,
            payload = mapOf("reason" to reason),
        )
        recorder.record(cardId, current, event)
        log.log("validate_blocked", mapOf("card" to cardId, "reason" to reason))
    }

    private companion object {
        const val STAGE = "validate"
        const val MAX_REWORKS = 3
        const val REWORK_CHARS = 600
        const val REWORK_TOTAL_CHARS = 1500
    }
}

/** Starts the validate pipeline on its own thread; the card's pool lock is released whatever the outcome. */
class ValidateStageLauncher(
    private val pipelineFor: (String) -> ValidatePipeline,
    pool: RunPool,
    log: DaemonLog = StderrDaemonLog,
    onRunFinished: (String) -> Unit = {},
) : StageLauncher {

    private val launcher = PipelineStageLauncher(
        "validate", pool, log, onRunFinished,
    ) { cardId -> pipelineFor(cardId).run(cardId) }

    override fun launch(stage: DispatchStage, cardId: String): LaunchedRun {
        require(stage == DispatchStage.VALIDATE) { "ValidateStageLauncher cannot launch stage $stage (card $cardId)" }
        return launcher.start(cardId)
    }
}
