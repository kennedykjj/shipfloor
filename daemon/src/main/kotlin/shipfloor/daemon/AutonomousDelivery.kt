package shipfloor.daemon

import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.DeliveryMode
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.ports.ChecksState
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.MergeResult
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestState
import shipfloor.store.CardEventStore

sealed interface DeliveryOutcome {
    /** The merge is on main, its CI is green and the card moved to Done. */
    data object Done : DeliveryOutcome

    /** The card went back to Staged with [reason] recorded; the owner decides again. */
    data class ReturnedToOwner(val reason: String) : DeliveryOutcome

    /** Nothing to do: wrong column, a project that does not deliver autonomously, or an interrupted wait. */
    data object Skipped : DeliveryOutcome
}

/** Hands a card in Ready for Prod to the delivery; must return quickly. */
fun interface DeliveryTrigger {
    fun trigger(cardId: String)
}

/**
 * Merges the head the owner approved and closes the card only on a green main. The merge is recorded as `PR_MERGED`
 * before its CI is followed, so a restart or a new approval never merges twice.
 */
class AutonomousDelivery(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val forge: (String) -> Forge,
    private val policies: (String) -> ProjectPolicies,
    private val pause: CiPause,
    private val maxPolls: Int,
    private val clock: Clock,
    private val log: DaemonLog,
    private val conflicts: ConflictResolver? = null,
    private val publisher: BranchPublisher? = null,
    private val worktreeOf: ((String) -> Path)? = null,
) {
    init {
        require(maxPolls > 0) { "maxPolls must be positive, got $maxPolls" }
    }

    private sealed interface Merge {
        data class Ready(val commit: String) : Merge

        data class Stop(val outcome: DeliveryOutcome) : Merge
    }

    fun deliver(cardId: String): DeliveryOutcome {
        val card = store.card(cardId)
        if (card == null || card.column != Column.READY_FOR_PROD) return DeliveryOutcome.Skipped
        val projectPolicies = policies(cardId)
        if (projectPolicies.deliveryMode != DeliveryMode.AUTONOMOUS) return DeliveryOutcome.Skipped
        val events = store.events(cardId)
        val approvedHead = approvedHead(events)
            ?: return returnToOwner(cardId, card, "no_approved_head", emptyMap())
        val recorded = events.lastOrNull { it.type == EventType.PR_MERGED }?.payload?.get("merge_commit")
        val commit = if (recorded != null) {
            recorded
        } else {
            val branch = WorktreeManager.branchName(projectPolicies.conventions.branchFormat, cardId, card.title)
            when (val merge = merge(cardId, card, branch, approvedHead)) {
                is Merge.Stop -> return merge.outcome
                is Merge.Ready -> merge.commit
            }
        }
        return followMain(cardId, card, commit)
    }

    private fun approvedHead(events: List<Event>): String? = events
        .lastOrNull {
            it.type == EventType.CARD_MOVED && !it.payload["head"].isNullOrEmpty() &&
                (isCarry(it) || (it.actor == Actor.Factory(VALIDATE) && it.payload["to"] == Column.STAGED.name && it.payload["ci"] == "green"))
        }
        ?.payload?.get("head")

    private fun isCarry(event: Event): Boolean =
        event.type == EventType.CARD_MOVED && event.actor == Actor.Factory(DELIVER) && event.payload["reason"] == CARRIED

    private fun merge(cardId: String, card: Card, branch: String, approvedHead: String): Merge {
        val forge = forge(cardId)
        var pr: PullRequestRef? = null
        try {
            pr = forge.findOpenPullRequest(branch)
                ?: return notOpen(forge, cardId, card, branch, approvedHead)
            return when (val result = forge.merge(pr, approvedHead)) {
                is MergeResult.Merged -> {
                    record(cardId, card, EventType.PR_MERGED, mapOf("pr" to pr.number.toString(), "merge_commit" to result.mergeCommit, "head" to approvedHead))
                    Merge.Ready(result.mergeCommit)
                }
                is MergeResult.HeadMismatch ->
                    stop(returnToOwner(cardId, card, "head_mismatch expected=$approvedHead actual=${result.actualHead}", emptyMap()))
                MergeResult.Conflict -> stop(resolveConflict(cardId, card, branch, approvedHead))
                is MergeResult.NotOpen -> notOpen(forge, cardId, card, branch, approvedHead)
            }
        } catch (e: ForgeException) {
            log.log("delivery_forge_error", mapOf("card" to cardId, "error" to e.message.orEmpty().take(200)))
            val merged = pr?.let { stateOf(forge, it) } == PullRequestState.Merged
            return stop(returnToOwner(cardId, card, if (merged) "merge_unrecorded" else "forge_failed", emptyMap()))
        }
    }

    /**
     * A branch that only fell behind main (merge without a single conflict, on exactly the approved head) keeps the
     * owner's approval once the checks of the new head are green: nothing of the card changed, only main moved. The
     * approval is carried to the new head by an event; after [MAX_CARRIES] the card goes through Validation as usual.
     */
    private fun carriesApproval(cardId: String, card: Card, outcome: ConflictOutcome.Resolved, approvedHead: String): Boolean {
        if (outcome.cleanFrom != approvedHead || outcome.head == approvedHead) return false
        if (store.events(cardId).count { isCarry(it) } >= MAX_CARRIES) return false
        if (!checksGreen(forge(cardId), outcome.head)) return false
        record(
            cardId,
            card,
            EventType.CARD_MOVED,
            mapOf("from" to card.column.name, "to" to card.column.name, "reason" to CARRIED, "head" to outcome.head, "previous_head" to approvedHead),
        )
        log.log("delivery_approval_carried", mapOf("card" to cardId))
        return true
    }

    private fun checksGreen(forge: Forge, head: String): Boolean =
        try {
            var green = false
            for (poll in 1..maxPolls) {
                val state = forge.checks(head)
                if (state is ChecksState.Failed) break
                if (state == ChecksState.Passed) {
                    green = true
                    break
                }
                if (poll == maxPolls) break
                pause.await()
                if (Thread.currentThread().isInterrupted) break
            }
            green
        } catch (e: ForgeException) {
            log.log("delivery_forge_error", mapOf("error" to e.message.orEmpty().take(200)))
            false
        }

    /**
     * A resolved conflict is a new head: it is pushed and the card goes back to Validation, because the owner's approval
     * and the green gates belong to the old head.
     */
    private fun resolveConflict(cardId: String, card: Card, branch: String, approvedHead: String): DeliveryOutcome {
        val resolver = conflicts
        val worktree = worktreeOf?.invoke(cardId)
        val publish = publisher
        if (resolver == null || worktree == null || publish == null) return returnToOwner(cardId, card, "merge_conflict", emptyMap())
        return when (val outcome = resolver.resolve(cardId, worktree)) {
            is ConflictOutcome.Resolved -> when (publish.push(worktree, branch)) {
                PushResult.Pushed -> if (carriesApproval(cardId, card, outcome, approvedHead)) {
                    deliver(cardId)
                } else {
                    val round = maxOf(store.events(cardId).count { ConflictResolution.isRound(it) }, 1)
                    move(cardId, card, Column.VALIDATION, mapOf("reason" to "conflict_round", "round" to round.toString(), "head" to outcome.head))
                    log.log("delivery_conflict_resolved", mapOf("card" to cardId, "round" to round.toString()))
                    DeliveryOutcome.Skipped
                }
                PushResult.Refused, PushResult.Failed -> returnToOwner(cardId, card, "push_failed", emptyMap())
            }
            ConflictOutcome.OverLimit, ConflictOutcome.Failed -> returnToOwner(cardId, card, "merge_conflict", emptyMap())
            ConflictOutcome.RoundsExhausted -> {
                val rounds = store.events(cardId).count { ConflictResolution.isRound(it) }
                block(cardId, card, "conflict_rounds_exhausted rounds=$rounds")
            }
        }
    }

    private fun block(cardId: String, card: Card, reason: String): DeliveryOutcome {
        record(cardId, card, EventType.CARD_BLOCKED, mapOf("reason" to reason))
        log.log("delivery_blocked", mapOf("card" to cardId, "reason" to reason.substringBefore(' ')))
        return DeliveryOutcome.Skipped
    }

    /** No open PR: one merged outside the factory closes the card (same head) or goes to the owner (other head). */
    private fun notOpen(forge: Forge, cardId: String, card: Card, branch: String, approvedHead: String): Merge {
        val mergedPr = forge.findMergedPullRequest(branch)
            ?: return stop(returnToOwner(cardId, card, "pr_not_open", emptyMap()))
        val payload = mapOf(
            "pr" to mergedPr.number.toString(),
            "merge_commit" to mergedPr.mergeCommit,
            "head" to mergedPr.head,
            "origin" to EXTERNAL,
        ) + (mergedPr.mergedBy?.let { mapOf("merged_by" to it) } ?: emptyMap())
        record(cardId, card, EventType.PR_MERGED, payload)
        if (mergedPr.head != approvedHead) {
            return stop(returnToOwner(cardId, card, "merged_head_differs expected=$approvedHead actual=${mergedPr.head}", emptyMap()))
        }
        move(
            cardId,
            card,
            Column.DONE,
            mapOf("merge_commit" to mergedPr.mergeCommit, "origin" to EXTERNAL, "reason" to "merged_outside_factory"),
        )
        log.log("delivery_done", mapOf("card" to cardId, "origin" to EXTERNAL))
        return stop(DeliveryOutcome.Done)
    }

    private fun stateOf(forge: Forge, pr: PullRequestRef): PullRequestState? =
        try {
            forge.state(pr)
        } catch (e: ForgeException) {
            null
        }

    private fun stop(outcome: DeliveryOutcome) = Merge.Stop(outcome)

    private fun followMain(cardId: String, card: Card, commit: String): DeliveryOutcome {
        val forge = forge(cardId)
        val base = mapOf("merge_commit" to commit)
        val events = store.events(cardId)
        val mergedEvent = events.lastOrNull { it.type == EventType.PR_MERGED }
        val prNumber = mergedEvent?.payload?.get("pr")?.toIntOrNull()
        val rerunEarlier = events.any { it.type == EventType.MAIN_CI_RERUN && it.payload["merge_commit"] == commit }
        var rerun = rerunEarlier
        var sawPending = false
        var result: ChecksState = ChecksState.Pending
        try {
            for (poll in 1..maxPolls) {
                result = forge.checks(commit)
                if (result is ChecksState.Failed) {
                    if (!rerun) {
                        forge.rerunChecks(commit)
                        record(cardId, card, EventType.MAIN_CI_RERUN, base + ("pr" to prNumber?.toString().orEmpty()))
                        rerun = true
                    } else if (rerunEarlier || sawPending) {
                        return revertMerge(cardId, card, forge, result, base, prNumber, mergedEvent?.payload?.get("head").orEmpty())
                    }
                } else if (result == ChecksState.Pending) {
                    sawPending = true
                } else {
                    break
                }
                if (poll == maxPolls) break
                pause.await()
                if (Thread.currentThread().isInterrupted) return DeliveryOutcome.Skipped
            }
        } catch (e: ForgeException) {
            log.log("delivery_forge_error", mapOf("card" to cardId, "error" to e.message.orEmpty().take(200)))
            return returnToOwner(cardId, card, "forge_failed", base)
        }
        return when (result) {
            ChecksState.Passed -> {
                move(cardId, card, Column.DONE, base + ("ci" to "green"))
                log.log("delivery_done", mapOf("card" to cardId))
                DeliveryOutcome.Done
            }
            is ChecksState.Failed -> returnToOwner(cardId, card, "rerun_not_started", base)
            ChecksState.Pending -> returnToOwner(cardId, card, "main_ci_timeout", base)
        }
    }

    private fun revertMerge(
        cardId: String,
        card: Card,
        forge: Forge,
        state: ChecksState.Failed,
        base: Map<String, String>,
        prNumber: Int?,
        head: String,
    ): DeliveryOutcome {
        val reason = redReason(state)
        val number = prNumber ?: return returnToOwner(cardId, card, "revert_failed $reason", base)
        val ref = PullRequestRef(number, "", head)
        return try {
            val revertPr = forge.revert(ref)
            returnToOwner(cardId, card, reason, base + ("revert_pr" to revertPr.number.toString()))
        } catch (e: ForgeException) {
            returnToOwner(cardId, card, "revert_failed $reason", base)
        }
    }

    private fun redReason(state: ChecksState.Failed): String {
        val names = state.failing.joinToString(",") { it.name }
        val summaries = state.failing.joinToString("\n") { "${it.name}: ${it.summary}" }
        return "main_ci_red checks=$names\n$summaries"
    }

    private fun returnToOwner(cardId: String, card: Card, reason: String, extra: Map<String, String>): DeliveryOutcome.ReturnedToOwner {
        val capped = truncateUtf8(reason, MAX_REASON_BYTES)
        move(cardId, card, Column.STAGED, extra + ("reason" to capped))
        log.log("delivery_returned", mapOf("card" to cardId, "reason" to capped.substringBefore(' ').substringBefore('\n')))
        return DeliveryOutcome.ReturnedToOwner(capped)
    }

    private fun move(cardId: String, card: Card, to: Column, extra: Map<String, String>) {
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory(DELIVER),
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to card.column.name, "to" to to.name) + extra,
        )
        recorder.record(cardId, card.movedTo(to), event)
    }

    private fun record(cardId: String, card: Card, type: EventType, payload: Map<String, String>) {
        val event = Event(EventId(UUID.randomUUID().toString()), clock.instant(), Actor.Factory(DELIVER), type, payload)
        recorder.record(cardId, card, event)
    }

    private companion object {
        const val VALIDATE = "validate"
        const val DELIVER = "deliver"
        const val EXTERNAL = "external"
        const val MAX_REASON_BYTES = 2048
        const val CARRIED = "approval_carried"
        const val MAX_CARRIES = 3
    }
}

/**
 * Runs the deliveries on one thread so the CI polling never stalls the dispatcher cycle; a card already being
 * delivered is not queued again.
 */
class AutonomousDeliveryTrigger(
    private val delivery: AutonomousDelivery,
    private val log: DaemonLog,
    private val handoff: PullRequestHandoff? = null,
) : DeliveryTrigger, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "autonomous-delivery").apply { isDaemon = true }
    }
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun trigger(cardId: String) {
        if (!inFlight.add(cardId)) return
        try {
            executor.execute {
                try {
                    delivery.deliver(cardId)
                    handoff?.reconcile(cardId)
                } catch (e: RuntimeException) {
                    log.log("delivery_failed", mapOf("card" to cardId, "error" to e.javaClass.simpleName))
                } finally {
                    inFlight.remove(cardId)
                }
            }
        } catch (e: RejectedExecutionException) {
            inFlight.remove(cardId)
        }
    }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val CLOSE_SECONDS = 5L
    }
}
