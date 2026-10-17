package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.store.CardEventStore

/** How much conflict the factory resolves on its own, and how many correction rounds a card gets. */
data class ConflictLimits(val maxFiles: Int = 5, val maxHunks: Int = 20, val maxRounds: Int = 2) {
    init {
        require(maxFiles > 0 && maxHunks > 0 && maxRounds > 0) { "conflict limits must be positive, got $this" }
    }
}

sealed interface ConflictOutcome {
    /**
     * The worktree holds a clean merge of main at [head]; it only counts once the gates are green on it.
     * [cleanFrom] is the head the branch had before a merge that needed no conflict resolution at all (main only
     * moved ahead); null when an agent had to resolve conflicts.
     */
    data class Resolved(val head: String, val cleanFrom: String? = null) : ConflictOutcome

    /** More files or hunks than the limits; no run started and the merge was dropped. */
    data object OverLimit : ConflictOutcome

    /** The card used all its rounds; no further round starts. */
    data object RoundsExhausted : ConflictOutcome

    /** A git failure, or a round that ended with the conflict still open. */
    data object Failed : ConflictOutcome
}

/** Resolves the conflict of the card's branch with main inside its worktree. */
fun interface ConflictResolver {
    fun resolve(cardId: String, worktree: Path): ConflictOutcome
}

/**
 * One correction round per call: merges main keeping the conflict open, measures it, runs the `conflict` stage on the
 * worktree and concludes the merge. The rounds are counted from the card's own events, so a restart keeps the count.
 */
@Suppress("LongParameterList")
class ConflictResolution(
    private val project: Project,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val sessions: McpSessions,
    private val mcpPort: Int,
    private val runtime: Runtime,
    private val costRecorder: RunCostRecorder,
    private val branchSync: BranchSync,
    private val head: WorktreeHead,
    private val configDir: Path,
    private val clock: Clock,
    private val model: String,
    private val limits: ConflictLimits = ConflictLimits(),
    private val log: DaemonLog = StderrDaemonLog,
    private val runtimeName: String = "claude",
) : ConflictResolver {

    override fun resolve(cardId: String, worktree: Path): ConflictOutcome {
        val before = head.of(worktree)
        val attempt = branchSync.mergeMainKeepingConflicts(worktree)
        val conflict = when (attempt) {
            ConflictAttempt.Merged -> return headOutcome(cardId, worktree, cleanFrom = before)
            ConflictAttempt.Failed -> return ConflictOutcome.Failed
            is ConflictAttempt.Conflicted -> attempt
        }
        if (conflict.files > limits.maxFiles || conflict.hunks > limits.maxHunks) {
            branchSync.abortMerge(worktree)
            log.log("conflict_over_limit", mapOf("card" to cardId, "files" to conflict.files.toString(), "hunks" to conflict.hunks.toString()))
            return ConflictOutcome.OverLimit
        }
        val done = roundsDone(cardId)
        if (done >= limits.maxRounds) {
            branchSync.abortMerge(worktree)
            log.log("conflict_rounds_exhausted", mapOf("card" to cardId, "rounds" to done.toString()))
            return ConflictOutcome.RoundsExhausted
        }
        val round = done + 1
        recordRound(cardId, round)
        runAgent(cardId, worktree)
        if (!branchSync.concludeMerge(worktree)) {
            branchSync.abortMerge(worktree)
            log.log("conflict_round_unresolved", mapOf("card" to cardId, "round" to round.toString()))
            return if (round >= limits.maxRounds) ConflictOutcome.RoundsExhausted else ConflictOutcome.Failed
        }
        log.log("conflict_round_resolved", mapOf("card" to cardId, "round" to round.toString()))
        return headOutcome(cardId, worktree)
    }

    private fun headOutcome(cardId: String, worktree: Path, cleanFrom: String? = null): ConflictOutcome {
        val current = head.of(worktree)
        if (current == null) {
            log.log("conflict_head_unreadable", mapOf("card" to cardId))
            return ConflictOutcome.Failed
        }
        return ConflictOutcome.Resolved(current, cleanFrom)
    }

    private fun roundsDone(cardId: String): Int = store.events(cardId).count { isRound(it) }

    private fun recordRound(cardId: String, round: Int) {
        val current = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory(STAGE),
            type = EventType.CARD_MOVED,
            payload = mapOf(
                "from" to current.column.name, "to" to current.column.name,
                "reason" to ROUND_REASON, "round" to round.toString(),
            ),
        )
        recorder.record(cardId, current, event)
    }

    private fun runAgent(cardId: String, worktree: Path) {
        val token = sessions.open(project.repo, STAGE, cardId)
        val config = writeMcpConfig(configDir, mcpPort, token)
        try {
            val result = runtime.launch(SessionRequest(STAGE, worktree, model, config, StagePrompt.of(STAGE, cardId)))
            costRecorder.record(cardId, UUID.randomUUID().toString(), runtimeName, STAGE, model, result)
        } finally {
            runCatching { Files.deleteIfExists(config) }
            sessions.close(token)
        }
    }

    companion object {
        const val STAGE = "conflict"
        const val ROUND_REASON = "conflict_round"

        /** The rounds of a card are the move events the conflict stage itself recorded. */
        fun isRound(event: Event): Boolean =
            event.type == EventType.CARD_MOVED && event.actor == Actor.Factory(STAGE) && event.payload["reason"] == ROUND_REASON
    }
}
