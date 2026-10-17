package shipfloor.daemon

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ExternalEffect
import shipfloor.core.ExternalRef
import shipfloor.core.Milestone
import shipfloor.core.ProjectPolicies
import shipfloor.core.Signer
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.store.CardEventStore

/**
 * Writes the optional milestones (policy `milestones`) of a card to its external issue, once each.
 * With `signers.milestone` = `owner` (the default) a reached milestone is only held as MILESTONE_PENDING until the owner
 * confirms it over the socket; with `policy` it is written at once.
 * The tracker provider comes from the project policies (`tracker`); the PR link comes from [prLinkOf].
 * [reached] never throws: an optional milestone must not break the pipeline that triggers it. Both lookups take the card id.
 */
class ExternalMilestones(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val trackerOf: (String) -> Tracker,
    private val policiesOf: (String) -> ProjectPolicies,
    private val projectOf: (String) -> Project,
    private val prLinkOf: (Project, String) -> String?,
    private val clock: Clock,
    private val log: DaemonLog,
    private val ownerProof: OwnerProof,
) {
    /** Serialized so the "already written" check and the write cannot race for the same card. */
    @Synchronized
    fun reached(cardId: String, milestone: Milestone, detail: String? = null) {
        try {
            val policies = policiesOf(cardId)
            if (milestone !in policies.milestones) return
            val card = store.card(cardId) ?: return
            val project = projectOf(cardId)
            val provider = project.policies.tracker.provider
            val ref = card.references.firstOrNull { it.provider == provider } ?: return
            when (policies.signers[ExternalEffect.MILESTONE] ?: Signer.OWNER) {
                Signer.OWNER -> hold(cardId, card, ref, project, milestone, detail)
                Signer.POLICY -> write(cardId, card, ref, project, milestone, detail)
            }
        } catch (e: TrackerException) {
            log.log("milestone_failed", mapOf("card" to cardId, "milestone" to milestone.key))
        } catch (e: RuntimeException) {
            log.log(
                "milestone_failed",
                mapOf("card" to cardId, "milestone" to milestone.key, "error" to e.javaClass.simpleName),
            )
        }
    }

    /** Answers `milestone.list` and `milestone.confirm`. Serialized, like [reached]. */
    @Synchronized
    fun handle(request: Request): Response = when (request) {
        Request.MilestoneList -> Response.MilestonesResult(pendingItems())
        is Request.MilestoneConfirm -> confirm(request)
        else -> Response.Error("not a milestone command")
    }

    private fun hold(cardId: String, card: Card, ref: ExternalRef, project: Project, milestone: Milestone, detail: String?) {
        val events = store.events(cardId)
        if (events.any { (it.type == EventType.MILESTONE_PENDING || it.type == EventType.MILESTONE_WRITTEN) && it.payload["milestone"] == milestone.key }) return
        val text = textOf(milestone, project, detail) ?: return
        val payload = buildMap {
            put("milestone", milestone.key)
            put("externalId", ref.externalId)
            put("textHash", sha256(text))
            if (detail != null) put("detail", detail)
        }
        record(cardId, card, Actor.Adapter(ref.provider), EventType.MILESTONE_PENDING, payload)
        log.log("milestone_pending", mapOf("card" to cardId, "milestone" to milestone.key))
    }

    private fun write(cardId: String, card: Card, ref: ExternalRef, project: Project, milestone: Milestone, detail: String?) {
        val tracker = trackerOf(cardId)
        val item = tracker.fetch(ref.externalId)
        if (item != null) {
            TextChangeWarning.warnIfChanged(
                store, recorder, clock, cardId, card, ref.externalId, ref.textHash.value, item.textHash, ref.provider,
            )
        }
        if (store.events(cardId).any { it.type == EventType.MILESTONE_WRITTEN && it.payload["milestone"] == milestone.key }) return
        val text = textOf(milestone, project, detail) ?: return
        tracker.writeMilestone(ref.externalId, text)
        recordWritten(cardId, card, milestone, ref)
    }

    private fun recordWritten(cardId: String, card: Card, milestone: Milestone, ref: ExternalRef) {
        record(
            cardId, card, Actor.Adapter(ref.provider), EventType.MILESTONE_WRITTEN,
            mapOf("milestone" to milestone.key, "externalId" to ref.externalId),
        )
        log.log("milestone_written", mapOf("card" to cardId, "milestone" to milestone.key))
    }

    private fun confirm(request: Request.MilestoneConfirm): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to CMD_CONFIRM))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val cardId = request.id
        val card = store.card(cardId) ?: return Response.Error("card not found: $cardId")
        val milestone = Milestone.entries.firstOrNull { it.key == request.milestone }
            ?: return Response.Error("unknown milestone for card $cardId")
        val events = store.events(cardId)
        val pending = events.lastOrNull { it.type == EventType.MILESTONE_PENDING && it.payload["milestone"] == milestone.key }
            ?: return Response.Error("no pending milestone ${milestone.key} for card $cardId")
        val pendingHash = pending.payload["textHash"]
        if (pendingHash != request.textHash) {
            return Response.Error("text changed for milestone ${milestone.key} of card $cardId: list the pending milestones again")
        }
        val written = events.any { it.type == EventType.MILESTONE_WRITTEN && it.payload["milestone"] == milestone.key }
        if (written) return Response.MilestonesResult(pendingItems())
        val project = projectOf(cardId)
        val provider = project.policies.tracker.provider
        val ref = card.references.firstOrNull { it.provider == provider }
            ?: return Response.Error("card $cardId has no $provider reference")
        val text = textOf(milestone, project, pending.payload["detail"])
        if (text == null || sha256(text) != pendingHash) {
            return Response.Error("text changed for milestone ${milestone.key} of card $cardId: list the pending milestones again")
        }
        val confirmed = events.any { it.type == EventType.MILESTONE_CONFIRMED && it.payload["milestone"] == milestone.key && it.payload["textHash"] == pendingHash }
        if (!confirmed) {
            record(cardId, card, Actor.Owner, EventType.MILESTONE_CONFIRMED, mapOf("milestone" to milestone.key, "textHash" to request.textHash))
        }
        return try {
            val tracker = trackerOf(cardId)
            tracker.fetch(ref.externalId)?.let {
                TextChangeWarning.warnIfChanged(
                    store, recorder, clock, cardId, card, ref.externalId, ref.textHash.value, it.textHash, provider,
                )
            }
            tracker.writeMilestone(ref.externalId, text)
            recordWritten(cardId, card, milestone, ref)
            Response.MilestonesResult(pendingItems())
        } catch (e: TrackerException) {
            log.log("milestone_failed", mapOf("card" to cardId, "milestone" to milestone.key))
            Response.Error("tracker write failed for milestone ${milestone.key} of card $cardId")
        }
    }

    /** Pending = MILESTONE_PENDING with no MILESTONE_WRITTEN of the same milestone, derived from the events. */
    private fun pendingItems(): List<MilestoneItem> = store.cards().flatMap { stored ->
        val events = store.events(stored.id)
        val pendingEvents = events.filter { it.type == EventType.MILESTONE_PENDING }
        if (pendingEvents.isEmpty()) return@flatMap emptyList()
        val project = try {
            projectOf(stored.id)
        } catch (e: RuntimeException) {
            return@flatMap emptyList()
        }
        val written = events.filter { it.type == EventType.MILESTONE_WRITTEN }.mapNotNull { it.payload["milestone"] }.toSet()
        val ref = stored.card.references.firstOrNull { it.provider == project.policies.tracker.provider }
        pendingEvents
            .distinctBy { it.payload["milestone"] }
            .filter { it.payload["milestone"] !in written }
            .mapNotNull { event ->
                val milestone = Milestone.entries.firstOrNull { it.key == event.payload["milestone"] }
                val text = if (milestone == null || ref == null) null else textOf(milestone, project, event.payload["detail"])
                text?.let { MilestoneItem(stored.id, requireNotNull(milestone).key, it, sha256(it)) }
            }
    }

    private fun textOf(milestone: Milestone, project: Project, detail: String?): String? = when (milestone) {
        Milestone.STARTED -> "started"
        Milestone.PR_OPENED -> detail?.let { prLinkOf(project, it) }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun record(cardId: String, card: Card, actor: Actor, type: EventType, payload: Map<String, String>) {
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = actor,
            type = type,
            payload = payload,
        )
        recorder.record(cardId, card, event)
    }

    private companion object {
        const val CMD_CONFIRM = "milestone.confirm"
        const val OWNER_PROOF_ERROR = "owner proof missing or invalid"
    }
}

/** The "external text changed" warning, shared by the reimport and the milestones; carries ids and hashes only. */
internal object TextChangeWarning {
    fun warnIfChanged(
        store: CardEventStore,
        recorder: EventRecorder,
        clock: Clock,
        cardId: String,
        card: Card,
        externalId: String,
        previousHash: String,
        newHash: String,
        provider: String,
    ) {
        if (previousHash == newHash) return
        val already = store.events(cardId).any { it.type == EventType.EXTERNAL_TEXT_CHANGED && it.payload["newHash"] == newHash }
        if (already) return
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Adapter(provider),
            type = EventType.EXTERNAL_TEXT_CHANGED,
            payload = mapOf("externalId" to externalId, "previousHash" to previousHash, "newHash" to newHash),
        )
        recorder.record(cardId, card, event)
    }
}
