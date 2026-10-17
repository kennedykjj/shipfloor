package shipfloor.daemon

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.CloseResult
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.store.CardEventStore

/**
 * Closure of the tracker issue of a Done card: `card.close.draft` (read only) and `card.close.confirm`
 * (owner only). The comment posted to the tracker carries only the card id, the merge commit and the PR link:
 * nothing that came from the issue, such as the card title.
 */
class CardClosure private constructor(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val registry: ProjectRegistry,
    private val trackerOf: (Project) -> Tracker,
    private val prLinkOf: (Project, String) -> String?,
    private val ownerProof: OwnerProof,
    private val clock: Clock,
    private val log: DaemonLog,
) {
    constructor(
        store: CardEventStore,
        recorder: EventRecorder,
        registry: ProjectRegistry,
        tracker: Tracker,
        prLinkOf: (Project, String) -> String?,
        ownerProof: OwnerProof,
        clock: Clock,
        log: DaemonLog,
    ) : this(store, recorder, registry, { tracker }, prLinkOf, ownerProof, clock, log)

    /** Serialized so two confirmations cannot both write to the tracker. */
    @Synchronized
    fun handle(request: Request): Response = when (request) {
        is Request.CardCloseDraft -> when (val result = draftOf(request.id)) {
            is Drafted.Ok -> result.draft.toResponse()
            is Drafted.Refused -> Response.Error(result.message)
        }
        is Request.CardCloseConfirm -> confirm(request)
        else -> Response.Error("not a closure command")
    }

    private fun confirm(request: Request.CardCloseConfirm): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to CMD_CONFIRM))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val draft = when (val result = draftOf(request.id)) {
            is Drafted.Ok -> result.draft
            is Drafted.Refused -> return Response.Error(result.message)
        }
        if (draft.hash != request.draftHash) {
            return Response.Error("draft changed for card ${request.id}: request a new draft")
        }
        var confirmed = false
        var written = false
        store.events(request.id).forEach { event ->
            when (event.type) {
                EventType.CLOSURE_CONFIRMED -> {
                    confirmed = event.payload["draft_hash"] == draft.hash
                    written = false
                }
                EventType.CLOSURE_WRITTEN -> if (confirmed) written = true
                else -> Unit
            }
        }
        if (written) return draft.toResponse()
        if (!confirmed) {
            record(request.id, EventType.CLOSURE_CONFIRMED, Actor.Owner, mapOf("draft_hash" to draft.hash))
        }
        val project = draft.project
        val result = try {
            trackerOf(project).close(draft.externalId, draft.comment)
        } catch (e: TrackerException) {
            val reason = "tracker close failed for issue ${draft.externalId}"
            record(request.id, EventType.CLOSURE_FAILED, FACTORY, mapOf("reason" to reason))
            log.log("card_closure_failed", mapOf("card" to request.id, "externalId" to draft.externalId))
            return Response.Error(reason)
        }
        val outcome = if (result == CloseResult.Closed) "closed" else "already_closed"
        record(request.id, EventType.CLOSURE_WRITTEN, FACTORY, mapOf("result" to outcome))
        log.log("card_closure_written", mapOf("card" to request.id, "externalId" to draft.externalId, "result" to outcome))
        return draft.toResponse()
    }

    private fun record(cardId: String, type: EventType, actor: Actor, payload: Map<String, String>) {
        val card = requireNotNull(store.card(cardId)) { "card $cardId not found" }
        recorder.record(cardId, card, Event(EventId(UUID.randomUUID().toString()), clock.instant(), actor, type, payload))
    }

    private fun draftOf(cardId: String): Drafted {
        val card = store.card(cardId) ?: return Drafted.Refused("card not found: $cardId")
        if (card.column != Column.DONE) return Drafted.Refused("card $cardId is not in Done")
        val project = store.projectOf(cardId)?.let { repo -> registry.list().firstOrNull { it.repo == repo } }
            ?: return Drafted.Refused("card $cardId has no registered project")
        val provider = project.policies.tracker.provider
        val ref = card.references.firstOrNull { it.provider == provider }
            ?: return Drafted.Refused("card $cardId has no $provider reference")
        val events = store.events(cardId)
        val merged = events.lastOrNull { it.type == EventType.PR_MERGED && it.payload["pr"] != null }
        val moved = events.lastOrNull { it.type == EventType.CARD_MOVED && it.payload["to"] == Column.DONE.name && it.payload["pr"] != null }
        val pr = merged?.payload?.get("pr") ?: moved?.payload?.get("pr")
            ?: return Drafted.Refused("no_pr_recorded: card $cardId has no pull request recorded")
        val commit = merged?.payload?.get("merge_commit")?.takeIf { it.isNotBlank() }
        val prLink = prLinkOf(project, pr)
            ?: return Drafted.Refused("no_pr_link: card $cardId has no pull request link, the project has no GitHub origin")
        val status = card.column.name
        val summary = if (commit == null) "Card $cardId delivered" else "Card $cardId delivered in commit $commit"
        val comment = "$summary\n$prLink"
        val hash = sha256("$status\n$summary\n$prLink\n$comment")
        return Drafted.Ok(Draft(project, ref.externalId, status, summary, prLink, comment, hash))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class Draft(
        val project: Project,
        val externalId: String,
        val status: String,
        val summary: String,
        val prLink: String,
        val comment: String,
        val hash: String,
    ) {
        fun toResponse(): Response = Response.CloseDraftResult(status, summary, prLink, hash)
    }

    private sealed interface Drafted {
        data class Ok(val draft: Draft) : Drafted
        data class Refused(val message: String) : Drafted
    }

    companion object {
        private const val CMD_CONFIRM = "card.close.confirm"
        private const val OWNER_PROOF_ERROR = "owner proof missing or invalid"
        private val FACTORY = Actor.Factory("closure")

        /** Production wiring: the tracker is the one of the project that owns the card. */
        fun forProjects(
            store: CardEventStore,
            recorder: EventRecorder,
            registry: ProjectRegistry,
            trackerOf: (Project) -> Tracker,
            prLinkOf: (Project, String) -> String?,
            ownerProof: OwnerProof,
            clock: Clock,
            log: DaemonLog,
        ): CardClosure = CardClosure(store, recorder, registry, trackerOf, prLinkOf, ownerProof, clock, log)
    }
}
