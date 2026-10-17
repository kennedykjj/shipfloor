package shipfloor.daemon

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.AssistantStates
import shipfloor.core.Card
import shipfloor.core.CardValidationException
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.MoveDecision
import shipfloor.core.MoveRules
import shipfloor.store.CardEventStore
import shipfloor.store.SpecRead
import shipfloor.store.SpecStore

/** Applies the core rules to card commands and writes through the store. A refusal writes nothing. */
class CardCommands(
    private val store: CardEventStore,
    private val clock: Clock,
    internal val ownerProof: OwnerProof,
    private val log: DaemonLog = StderrDaemonLog,
    subscriberQueueLimit: Int = EventStream.DEFAULT_QUEUE_LIMIT,
    closeGrace: Duration = EventStream.DEFAULT_CLOSE_GRACE,
    private val specs: SpecStore? = null,
) {

    /** Live feed of every recorded change. */
    val events: EventStream = EventStream(store, subscriberQueueLimit, log, clock, closeGrace)

    /** The only writer of events; shared with the run pool and the gate executor. */
    val recorder: EventRecorder = EventRecorder(store, events)

    private val columnTimes = ColumnTimesQuery(store)

    /** Serialized so the read-check-write of a move cannot interleave with another command. */
    @Synchronized
    fun handle(request: Request): Response = when (request) {
        is Request.CardCreate -> create(request)
        is Request.CardMove -> move(request)
        is Request.CardApprove -> approve(request)
        is Request.CardUnblock -> unblock(request)
        is Request.CardRequestChange -> requestChange(request)
        Request.CardList -> Response.CardsResult(visibleCards().map { viewOf(it.id, it.card) })
        is Request.CardShow -> show(request.id)
        is Request.CardDetail -> detail(request.id)
        Request.ReportCsv -> Response.ReportResult(CardReport.toCsv(CardReport.build(visibleCards()) { store.events(it) }))
        is Request.AuditExport -> auditExport(request)
        is Request.EventsRecent -> recent(request.limit)
        is Request.ColumnTimes -> columnTimes.handle(request)
        Request.SettingsGet, is Request.SettingsSave, Request.Ping, is Request.Rejected, is Request.Subscribe, is Request.ProjectAdd, Request.ProjectList, is Request.ProjectDocsDraft, is Request.CardRebuild, is Request.CardImport, is Request.CardImportBoard, is Request.CardCloseDraft, is Request.CardCloseConfirm, Request.MilestoneList, is Request.MilestoneConfirm, is Request.CostSummary, Request.FactoryPulse -> Response.Error("not a card command")
    }

    private fun recent(limit: Int): Response {
        val newest = store.latestEvents(limit + 1L)
        return Response.EventsRecentResult(newest.take(limit).reversed(), truncated = newest.size > limit)
    }

    /** Every card but the reserved one that carries the settings events. */
    private fun visibleCards() = store.cards().filter { it.id != SettingsCommands.SETTINGS_CARD_ID }

    private fun create(request: Request.CardCreate): Response {
        if (request.actor == Actor.Owner && !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to "card.create"))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val card = try {
            Card.create(request.title, Column.INBOX)
        } catch (e: CardValidationException) {
            return Response.Error(e.message ?: "invalid card")
        }
        val id = UUID.randomUUID().toString()
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = request.actor,
            type = EventType.CARD_CREATED,
            payload = mapOf("title" to card.title),
        )
        recorder.record(id, card, event)
        log.log("card_command", mapOf("cmd" to "card.create", "card" to id))
        return Response.CardResult(CardView(id, card.title, card.column))
    }

    private fun move(request: Request.CardMove): Response {
        if (request.actor == Actor.Owner && !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to "card.move", "card" to request.id))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val current = store.card(request.id) ?: return notFound(request.id)
        when (val decision = MoveRules.check(current.column, request.to, request.actor)) {
            is MoveDecision.Refused -> {
                log.log("card_command_refused", mapOf("cmd" to "card.move", "card" to request.id))
                return Response.Error(decision.reason)
            }
            MoveDecision.Allowed -> Unit
        }
        val moved = current.movedTo(request.to)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = request.actor,
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to current.column.name, "to" to request.to.name),
        )
        recorder.record(request.id, moved, event)
        log.log("card_command", mapOf("cmd" to "card.move", "card" to request.id))
        return Response.CardResult(CardView(request.id, moved.title, moved.column))
    }

    private fun approve(request: Request.CardApprove): Response =
        reviewDecision(
            cmd = "card.approve",
            actor = request.actor,
            proof = request.proof,
            id = request.id,
            to = Column.READY_FOR_DEV,
            type = EventType.OWNER_APPROVED,
            note = null,
        )

    private fun requestChange(request: Request.CardRequestChange): Response {
        val note = request.note
        if (note == null || note.isBlank()) {
            return Response.Error("note is required to request a change")
        }
        if (note.toByteArray(Charsets.UTF_8).size > MAX_NOTE_BYTES) {
            return Response.Error("note exceeds the $MAX_NOTE_BYTES byte limit")
        }
        return reviewDecision(
            cmd = "card.request-change",
            actor = request.actor,
            proof = request.proof,
            id = request.id,
            to = Column.READY_FOR_REFINEMENT,
            type = EventType.CARD_MOVED,
            note = note,
        )
    }

    /** Owner decision on a card waiting in Spec Review: proof, card and column are checked before anything is written. */
    private fun reviewDecision(
        cmd: String,
        actor: Actor,
        proof: String?,
        id: String,
        to: Column,
        type: EventType,
        note: String?,
    ): Response {
        if (actor != Actor.Owner || !ownerProof.matches(proof)) {
            log.log("card_command_refused", mapOf("cmd" to cmd, "card" to id))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val current = store.card(id) ?: return notFound(id)
        if (current.column != Column.SPEC_REVIEW) {
            log.log("card_command_refused", mapOf("cmd" to cmd, "card" to id))
            return Response.Error("card $id is in ${current.column.name}, not in SPEC_REVIEW")
        }
        when (val decision = MoveRules.check(current.column, to, actor)) {
            is MoveDecision.Refused -> {
                log.log("card_command_refused", mapOf("cmd" to cmd, "card" to id))
                return Response.Error(decision.reason)
            }
            MoveDecision.Allowed -> Unit
        }
        val moved = current.movedTo(to)
        val payload = buildMap {
            put("from", current.column.name)
            put("to", to.name)
            if (note != null) put("note", note)
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = actor,
            type = type,
            payload = payload,
        )
        recorder.record(id, moved, event)
        log.log("card_command", mapOf("cmd" to cmd, "card" to id))
        return Response.CardResult(CardView(id, moved.title, moved.column))
    }

    /** Owner release of an open block: the event is recorded; a card blocked mid-work returns to the queue of its stage. */
    private fun unblock(request: Request.CardUnblock): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to "card.unblock", "card" to request.id))
            return Response.Error("$OWNER_PROOF_ERROR for card ${request.id}")
        }
        val current = store.card(request.id) ?: return notFound(request.id)
        if (!AssistantStates.hasOpenBlock(store.events(request.id))) {
            log.log("card_command_refused", mapOf("cmd" to "card.unblock", "card" to request.id))
            return Response.Error("card ${request.id} is not blocked")
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = request.actor,
            type = EventType.CARD_UNBLOCKED,
            payload = emptyMap(),
        )
        recorder.record(request.id, current, event)
        val queue = QUEUE_OF[current.column]
        val result = if (queue != null) requeue(request, current, queue) else current
        log.log("card_command", mapOf("cmd" to "card.unblock", "card" to request.id))
        return Response.CardResult(viewOf(request.id, result))
    }

    /** The dispatcher only picks the ready columns, so a card blocked mid-work goes back to the queue of its stage. */
    private fun requeue(request: Request.CardUnblock, current: Card, queue: Column): Card {
        val moved = current.movedTo(queue)
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = request.actor,
            type = EventType.CARD_MOVED,
            payload = mapOf("from" to current.column.name, "to" to queue.name, "reason" to "unblock"),
        )
        recorder.record(request.id, moved, event)
        return moved
    }

    private fun viewOf(id: String, card: Card): CardView {
        val events = store.events(id)
        return CardView(
            id,
            card.title,
            card.column,
            blocked = AssistantStates.hasOpenBlock(events),
            enteredAt = enteredAt(card.column, events),
            cost = totalCost(events),
        )
    }

    /** The last move into the current column, or the creation when the card never moved. */
    private fun enteredAt(column: Column, events: List<Event>): Instant? =
        events.lastOrNull { it.type == EventType.CARD_MOVED && it.payload["to"] == column.name }?.at
            ?: events.firstOrNull { it.type == EventType.CARD_CREATED }?.at

    private fun totalCost(events: List<Event>): CardListCost {
        val runs = events.filter { it.type == EventType.RUN_COST_RECORDED }.map { run ->
            if (run.payload["status"] == "KNOWN") run.payload["cost_usd"]?.toBigDecimalOrNull() else null
        }
        val known = runs.filterNotNull()
        return when {
            runs.isEmpty() -> CardListCost(CostStatus.NONE)
            known.isEmpty() -> CardListCost(CostStatus.UNKNOWN)
            else -> CardListCost(
                if (known.size == runs.size) CostStatus.KNOWN else CostStatus.PARTIAL,
                known.fold(BigDecimal.ZERO, BigDecimal::add),
            )
        }
    }

    private fun show(id: String): Response {
        val card = store.card(id) ?: return notFound(id)
        return Response.CardResult(viewOf(id, card))
    }

    private fun auditExport(request: Request.AuditExport): Response {
        val format = AuditFormat.parse(request.format)
            ?: return Response.Error("invalid audit format for card ${request.id}: use json or csv")
        store.card(request.id) ?: return notFound(request.id)
        return Response.AuditResult(AuditExport.render(request.id, store.events(request.id), format))
    }

    private fun detail(id: String): Response {
        val card = store.card(id) ?: return notFound(id)
        val spec = specs?.read(id) ?: SpecRead.NotRegistered(id)
        return Response.CardDetailResult(CardDetail.build(id, card, spec, store.events(id)))
    }

    private fun notFound(id: String): Response = Response.Error("card not found: $id")

    private companion object {
        val QUEUE_OF = mapOf(Column.REFINEMENT to Column.READY_FOR_REFINEMENT, Column.CODING to Column.READY_FOR_DEV)
        const val MAX_NOTE_BYTES = 2048
        const val OWNER_PROOF_ERROR = "owner proof missing or invalid"
    }
}
