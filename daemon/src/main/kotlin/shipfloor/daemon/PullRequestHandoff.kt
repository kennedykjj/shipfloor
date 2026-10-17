package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.DeliveryMode
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestState
import shipfloor.store.CardEventStore

sealed interface HandoffOutcome {
    /** The PR is open: the card waits in Ready for Prod. */
    data object Waiting : HandoffOutcome

    /** Someone else merged the PR; the card moved to Done. */
    data object Merged : HandoffOutcome

    /** The PR was closed without merge, or never recorded; the card went back to Staged. */
    data object ClosedUnmerged : HandoffOutcome

    /** The forge did not answer; the card stays and the next cycle decides. */
    data object ForgeFailed : HandoffOutcome

    /** Nothing to do: wrong column or a project that does not hand off through the PR. */
    data object Skipped : HandoffOutcome
}

/**
 * Delivery in `PR_HANDOFF` mode: the factory never merges. The owner's `card.move` to Ready for Prod, recorded with
 * proof, parks the card there; this class only follows the PR and the card closes when the forge shows it merged by
 * someone else. It exposes no way to record an owner event.
 */
class PullRequestHandoff(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val forge: (String) -> Forge,
    private val policies: (String) -> ProjectPolicies,
    private val clock: Clock,
    private val log: DaemonLog,
) {
    /** Follows the PR of a card waiting in Ready for Prod; forge state is data and never an instruction. */
    fun reconcile(cardId: String): HandoffOutcome {
        val card = store.card(cardId)
        if (card == null || card.column != Column.READY_FOR_PROD) return HandoffOutcome.Skipped
        val projectPolicies = policies(cardId)
        if (projectPolicies.deliveryMode != DeliveryMode.PR_HANDOFF) return HandoffOutcome.Skipped
        val staged = store.events(cardId).lastOrNull {
            it.type == EventType.CARD_MOVED && it.actor == Actor.Factory(VALIDATE) && it.payload["to"] == Column.STAGED.name
        }
        val number = staged?.payload?.get("pr")?.toIntOrNull()
            ?: return returnToStaged(cardId, card, "no_pr_recorded", HandoffOutcome.ClosedUnmerged)
        val branch = WorktreeManager.branchName(projectPolicies.conventions.branchFormat, cardId, card.title)
        val ref = PullRequestRef(number, branch, staged.payload["head"].orEmpty())
        val state = try {
            forge(cardId).state(ref)
        } catch (e: ForgeException) {
            log.log("handoff_forge_failed", mapOf("card" to cardId))
            return HandoffOutcome.ForgeFailed
        }
        return when (state) {
            is PullRequestState.Open -> HandoffOutcome.Waiting
            PullRequestState.Merged -> {
                move(cardId, card, Column.DONE, Actor.Factory(DELIVER), mapOf("pr" to number.toString()))
                log.log("handoff_done", mapOf("card" to cardId))
                HandoffOutcome.Merged
            }
            PullRequestState.ClosedUnmerged -> returnToStaged(cardId, card, "pr_closed_unmerged", HandoffOutcome.ClosedUnmerged)
        }
    }

    private fun returnToStaged(cardId: String, card: Card, reason: String, outcome: HandoffOutcome): HandoffOutcome {
        move(cardId, card, Column.STAGED, Actor.Factory(DELIVER), mapOf("reason" to reason))
        log.log("handoff_returned", mapOf("card" to cardId, "reason" to reason))
        return outcome
    }

    private fun move(cardId: String, card: Card, to: Column, actor: Actor, extra: Map<String, String>) {
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = actor,
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to card.column.name, "to" to to.name) + extra,
        )
        recorder.record(cardId, card.movedTo(to), event)
    }

    private companion object {
        const val VALIDATE = "validate"
        const val DELIVER = "deliver"
    }
}
