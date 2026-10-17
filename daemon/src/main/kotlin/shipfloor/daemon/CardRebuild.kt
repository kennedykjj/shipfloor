package shipfloor.daemon

import java.time.Clock
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.CardValidationException
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.PullRequestState
import shipfloor.ports.PullRequestSummary
import shipfloor.store.CardEventStore

/**
 * Recreates the cards of a project from the pull requests on its forge (`card.rebuild`, owner only).
 * A card is born in Validation (open PR) or Done (merged PR), never Staged; cards already stored are untouched.
 * PR titles and branches are untrusted data: the id must survive an exact branch reconstruction.
 */
class CardRebuild private constructor(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val registry: ProjectRegistry,
    private val forgeOf: (Project) -> Forge,
    private val ownerProof: OwnerProof,
    private val clock: Clock,
    private val log: DaemonLog,
) {
    constructor(
        store: CardEventStore,
        recorder: EventRecorder,
        registry: ProjectRegistry,
        forge: Forge,
        ownerProof: OwnerProof,
        clock: Clock,
        log: DaemonLog,
    ) : this(store, recorder, registry, { forge }, ownerProof, clock, log)

    /** Serialized so two rebuilds cannot both create the same card. */
    @Synchronized
    fun handle(request: Request): Response {
        if (request !is Request.CardRebuild) return Response.Error("not a rebuild command")
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("card_command_refused", mapOf("cmd" to CMD))
            return Response.Error(OWNER_PROOF_ERROR)
        }
        val project = when (val chosen = chooseProject(request.project)) {
            is Chosen.Found -> chosen.project
            is Chosen.Failed -> return Response.Error(chosen.message)
        }
        val forge = forgeOf(project)
        val listed: List<PullRequestSummary>
        val login: String
        try {
            listed = forge.listPullRequests()
            if (listed.size >= LIST_LIMIT) {
                log.log("card_rebuild_failed", mapOf("cmd" to CMD, "reason" to "truncated"))
                return Response.Error("listagem truncada: o forge devolveu $LIST_LIMIT PRs, reconstruir só uma parte não é seguro")
            }
            login = forge.authenticatedLogin()
        } catch (e: ForgeException) {
            val operation = if (e.message?.contains("authenticatedLogin") == true) "authenticatedLogin" else "listPullRequests"
            log.log("card_rebuild_failed", mapOf("cmd" to CMD, "operation" to operation))
            return Response.Error("$operation failed: ${e.message}")
        }
        val format = project.policies.conventions.branchFormat
        val recreated = listed.mapNotNull { pr -> recreate(pr, format, login, project) }
        log.log("card_rebuild", mapOf("cmd" to CMD, "created" to recreated.size.toString(), "listed" to listed.size.toString()))
        return Response.CardsResult(recreated)
    }

    private fun recreate(pr: PullRequestSummary, format: String, login: String, project: Project): CardView? {
        val column = when (pr.state) {
            is PullRequestState.Open -> Column.VALIDATION
            PullRequestState.Merged -> Column.DONE
            PullRequestState.ClosedUnmerged -> return null
        }
        if (pr.fromFork || pr.author != login) return null
        val id = idFromBranch(format, pr.branch) ?: return null
        if (WorktreeManager.branchName(format, id, pr.title) != pr.branch) return null
        if (store.card(id) != null) return null
        val card = try {
            Card.create(pr.title, column)
        } catch (e: CardValidationException) {
            return null
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Owner,
            type = EventType.CARD_CREATED,
            payload = mapOf("title" to card.title, "origin" to "rebuilt", "pr" to pr.number.toString()),
        )
        recorder.record(id, card, event)
        store.assignProject(id, project.repo)
        log.log("card_rebuilt", mapOf("card" to id, "pr" to pr.number.toString(), "column" to column.name))
        return CardView(id, card.title, card.column)
    }

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
        private const val CMD = "card.rebuild"
        private const val LIST_LIMIT = 1000
        private const val OWNER_PROOF_ERROR = "owner proof missing or invalid"
        private val PLACEHOLDER = Regex("""\{id}|\{slug}""")

        /** Production wiring: the forge is the one of the project being rebuilt. */
        fun forProjects(
            store: CardEventStore,
            recorder: EventRecorder,
            registry: ProjectRegistry,
            forgeOf: (Project) -> Forge,
            ownerProof: OwnerProof,
            clock: Clock,
            log: DaemonLog,
        ): CardRebuild = CardRebuild(store, recorder, registry, forgeOf, ownerProof, clock, log)

        /** The canonical (lowercase) UUID in the `{id}` slot of [format], or null when [branch] does not match. */
        internal fun idFromBranch(format: String, branch: String): String? {
            val pattern = StringBuilder("^")
            var cursor = 0
            var idSeen = false
            for (match in PLACEHOLDER.findAll(format)) {
                pattern.append(Regex.escape(format.substring(cursor, match.range.first)))
                if (match.value == "{id}" && !idSeen) {
                    pattern.append("([0-9a-f-]{36})")
                    idSeen = true
                } else {
                    pattern.append(".+")
                }
                cursor = match.range.last + 1
            }
            if (!idSeen) return null
            pattern.append(Regex.escape(format.substring(cursor))).append("$")
            val candidate = Regex(pattern.toString()).matchEntire(branch)?.groupValues?.get(1) ?: return null
            val parsed = try {
                UUID.fromString(candidate)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return candidate.takeIf { parsed.toString() == it }
        }
    }
}
