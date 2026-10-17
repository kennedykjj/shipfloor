package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.DorPolicy
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ExternalRef
import shipfloor.core.TextHash
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.store.CardEventStore
import shipfloor.store.StoredCard

/**
 * Imports issues from the project's tracker into cards (`card.import`, owner only), by id or by search.
 * Issue text is untrusted data: it only becomes a normalized title and a hash, and is never logged.
 */
class CardImport private constructor(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val registry: ProjectRegistry,
    private val trackerOf: (Project) -> Tracker,
    private val ownerProof: OwnerProof,
    private val clock: Clock,
    private val log: DaemonLog,
) {
    constructor(
        store: CardEventStore,
        recorder: EventRecorder,
        registry: ProjectRegistry,
        tracker: Tracker,
        ownerProof: OwnerProof,
        clock: Clock,
        log: DaemonLog,
    ) : this(store, recorder, registry, { tracker }, ownerProof, clock, log)

    /** Serialized so two imports cannot both create the card of the same issue. */
    @Synchronized
    fun handle(request: Request): Response = when (request) {
        is Request.CardImport -> handleImport(request)
        is Request.CardImportBoard -> handleBoard(request)
        else -> Response.Error("not an import command")
    }

    private fun handleBoard(request: Request.CardImportBoard): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to BOARD_CMD))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val project = when (val chosen = chooseProject(request.project)) {
            is Chosen.Found -> chosen.project
            is Chosen.Failed -> return Response.Error(chosen.message)
        }
        val tracker = trackerOf(project)
        val created = mutableListOf<CardView>()
        val ignored = mutableListOf<IgnoredRow>()
        for (row in request.rows) {
            val target = V0_COLUMNS[row.column]
            if (target == null) {
                ignored.add(IgnoredRow(row.id, "unknown_column"))
                continue
            }
            val item = try {
                tracker.fetch(row.id)
            } catch (e: TrackerException) {
                log.log("card_import_failed", mapOf("cmd" to BOARD_CMD, "operation" to "fetch", "externalId" to row.id))
                return Response.Error("fetch failed for issue ${row.id}")
            }
            if (item == null) {
                ignored.add(IgnoredRow(row.id, "not_found"))
                continue
            }
            if (existing(project, item.id) != null) continue
            val extra = if (row.column in IN_FLIGHT_V0_COLUMNS) mapOf("v0_column" to row.column) else emptyMap()
            created.add(create(item, project, target, "v0", extra))
        }
        val onBoard = request.rows.map { it.id }.toSet()
        val open = try {
            tracker.search("")
        } catch (e: TrackerException) {
            log.log("card_import_failed", mapOf("cmd" to BOARD_CMD, "operation" to "search"))
            return Response.Error("search failed")
        }
        open
            .filter { it.state == TrackerItemState.OPEN && it.id !in onBoard && existing(project, it.id) == null }
            .forEach { created.add(create(it, project, Column.INBOX, "v0", emptyMap())) }
        log.log("card_import_board", mapOf("cmd" to BOARD_CMD, "created" to created.size.toString(), "ignored" to ignored.size.toString()))
        return Response.BoardImportResult(created, ignored)
    }

    private fun handleImport(request: Request.CardImport): Response {
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to CMD))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        if ((request.id == null) == (request.query == null)) {
            return Response.Error("exactly one of id and query is required")
        }
        val project = when (val chosen = chooseProject(request.project)) {
            is Chosen.Found -> chosen.project
            is Chosen.Failed -> return Response.Error(chosen.message)
        }
        val tracker = trackerOf(project)
        return if (request.id != null) importById(tracker, project, request.id) else importQuery(tracker, project, requireNotNull(request.query))
    }

    private fun importById(tracker: Tracker, project: Project, id: String): Response {
        val item = try {
            tracker.fetch(id)
        } catch (e: TrackerException) {
            log.log("card_import_failed", mapOf("cmd" to CMD, "operation" to "fetch", "externalId" to id))
            return Response.Error("fetch failed for issue $id")
        } ?: return Response.Error("issue not found: $id")
        return Response.CardsResult(listOf(upsert(item, project)))
    }

    private fun importQuery(tracker: Tracker, project: Project, query: String): Response {
        val found = try {
            tracker.search(query)
        } catch (e: TrackerException) {
            log.log("card_import_failed", mapOf("cmd" to CMD, "operation" to "search"))
            return Response.Error("search failed")
        }
        val created = found
            .filter { it.state == TrackerItemState.OPEN && existing(project, it.id) == null }
            .map { create(it, project) }
        log.log("card_import", mapOf("cmd" to CMD, "created" to created.size.toString(), "found" to found.size.toString()))
        return Response.CardsResult(created)
    }

    private fun upsert(item: TrackerItem, project: Project): CardView {
        val current = existing(project, item.id)
        return if (current == null) create(item, project) else reimport(item, current, project)
    }

    private fun existing(project: Project, externalId: String): StoredCard? =
        store.cards().firstOrNull { stored ->
            store.projectOf(stored.id) == project.repo &&
                stored.card.references.any { it.provider == project.policies.tracker.provider && it.externalId == externalId }
        }

    private fun create(
        item: TrackerItem,
        project: Project,
        column: Column = Column.INBOX,
        origin: String = "imported",
        extra: Map<String, String> = emptyMap(),
    ): CardView {
        val provider = project.policies.tracker.provider
        val card = Card.create(normalizeTitle(item.title.value, item.id), column).withReference(refOf(item, provider))
        val id = UUID.randomUUID().toString()
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Adapter(provider),
            type = EventType.CARD_CREATED,
            payload = mapOf("title" to card.title, "origin" to origin, "provider" to provider, "externalId" to item.id) + extra,
        )
        recorder.record(id, card, event)
        store.assignProject(id, project.repo)
        log.log("card_imported", mapOf("card" to id, "externalId" to item.id))
        if (column == Column.INBOX) blockIfIncomplete(id, card, item, project)
        return CardView(id, card.title, card.column)
    }

    /** DoR gate: a new Inbox card whose ticket lacks a required field is blocked right after creation. */
    private fun blockIfIncomplete(id: String, card: Card, item: TrackerItem, project: Project) {
        val missing = DorPolicy.missing(
            project.policies.dor, item.title.value, item.body.value, item.labels.map { it.value },
        )
        if (missing.isEmpty()) return
        val keys = missing.joinToString(",") { it.key }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("dor-gate"),
            type = EventType.CARD_BLOCKED,
            payload = mapOf("reason" to "dor_missing: $keys"),
        )
        recorder.record(id, card, event)
        log.log("card_blocked_by_dor", mapOf("card" to id, "externalId" to item.id, "missing" to keys))
    }

    private fun reimport(item: TrackerItem, current: StoredCard, project: Project): CardView {
        val provider = project.policies.tracker.provider
        val old = current.card
        val oldRef = old.references.first { it.provider == provider && it.externalId == item.id }
        val changed = oldRef.textHash != TextHash(item.textHash)
        val frozen = changed && old.column !in EDITABLE_TITLE_COLUMNS
        val payload = mutableMapOf("provider" to provider, "externalId" to item.id, "changed" to changed.toString())
        var card = old
        if (changed) {
            val title = if (frozen) old.title else normalizeTitle(item.title.value, item.id)
            card = old.references.fold(Card.create(title, old.column)) { acc, ref ->
                acc.withReference(if (ref === oldRef) refOf(item, provider) else ref)
            }
            if (frozen) payload["title_frozen"] = "true" else payload["title"] = title
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Adapter(provider),
            type = EventType.CARD_REIMPORTED,
            payload = payload,
        )
        recorder.record(current.id, card, event)
        if (changed) {
            TextChangeWarning.warnIfChanged(
                store, recorder, clock, current.id, card, item.id, oldRef.textHash.value, item.textHash, provider,
            )
        }
        log.log("card_reimported", mapOf("card" to current.id, "externalId" to item.id, "changed" to changed.toString()))
        return CardView(current.id, card.title, card.column)
    }

    private fun refOf(item: TrackerItem, provider: String): ExternalRef =
        ExternalRef(provider, item.id, item.url, clock.instant(), TextHash(item.textHash))

    private fun chooseProject(name: String?): Chosen {
        val projects = registry.list()
        if (name != null) {
            val found = projects.firstOrNull { it.name == name }
            return if (found != null) Chosen.Found(found) else Chosen.Failed("project not found: $name")
        }
        return when (projects.size) {
            0 -> Chosen.Failed("no project registered")
            1 -> Chosen.Found(projects.single())
            else -> Chosen.Failed("several projects registered: choose one with --project")
        }
    }

    private sealed interface Chosen {
        data class Found(val project: Project) : Chosen
        data class Failed(val message: String) : Chosen
    }

    companion object {
        private const val CMD = "card.import"
        private const val BOARD_CMD = "card.import_board"
        private val IN_FLIGHT_V0_COLUMNS = setOf("Ready for Dev", "Coding", "Validation", "Staged", "Ready for Prod")
        private val V0_COLUMNS: Map<String, Column> = mapOf(
            "Inbox" to Column.INBOX,
            "Ready for Refinement" to Column.READY_FOR_REFINEMENT,
            "Technical Refinement" to Column.READY_FOR_REFINEMENT,
            "Spec Review" to Column.SPEC_REVIEW,
            "Done" to Column.DONE,
        ) + IN_FLIGHT_V0_COLUMNS.associateWith { Column.SPEC_REVIEW }
        private const val MAX_TITLE = 200
        private const val OWNER_PROOF_ERROR = "owner proof missing or invalid"
        private val EDITABLE_TITLE_COLUMNS = setOf(
            Column.INBOX,
            Column.READY_FOR_REFINEMENT,
            Column.REFINEMENT,
            Column.SPEC_REVIEW,
            Column.READY_FOR_DEV,
        )

        /** Production wiring: the tracker is the one of the project being imported into. */
        fun forProjects(
            store: CardEventStore,
            recorder: EventRecorder,
            registry: ProjectRegistry,
            trackerOf: (Project) -> Tracker,
            ownerProof: OwnerProof,
            clock: Clock,
            log: DaemonLog,
        ): CardImport = CardImport(store, recorder, registry, trackerOf, ownerProof, clock, log)

        /** Control characters and whitespace runs become one space, cut at 200 characters; empty becomes `Issue <id>`. */
        internal fun normalizeTitle(raw: String, id: String): String {
            val spaced = raw.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("")
            val collapsed = spaced.replace(Regex(" {2,}"), " ").trim()
            return collapsed.take(MAX_TITLE).trimEnd().ifEmpty { "Issue $id" }
        }
    }
}
