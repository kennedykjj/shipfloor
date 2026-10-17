package shipfloor.daemon

import java.nio.file.Path
import shipfloor.core.Column
import shipfloor.core.ProjectPolicies
import shipfloor.store.CardEventStore
import shipfloor.store.SpecRead
import shipfloor.store.SpecStore

data class ReferenceView(val provider: String, val externalId: String, val url: UntrustedText)

data class McpCardView(val id: String, val title: UntrustedText, val column: Column, val references: List<ReferenceView>)

data class SpecView(val cardId: String, val spec: UntrustedText)

data class CriteriaView(val criteria: UntrustedText)

class McpToolException(message: String) : RuntimeException(message)

/** Read-only tools of one project's session. Every card access is checked against the session's project first. */
class McpReadTools(
    private val session: McpSession,
    private val store: CardEventStore,
    private val specs: SpecStore,
    private val registry: ProjectRegistry,
    private val files: ProjectFileSource,
    private val log: DaemonLog = StderrDaemonLog,
) {

    fun cardGet(cardId: String): McpCardView {
        requireOwnCard("card_get", cardId)
        val card = store.card(cardId) ?: throw McpToolException("card $cardId not found")
        log.log("mcp_tool", mapOf("tool" to "card_get", "card" to cardId))
        return McpCardView(
            cardId,
            UntrustedText("card", card.title),
            card.column,
            card.references.map { ReferenceView(it.provider, it.externalId, UntrustedText("reference_url", it.url)) },
        )
    }

    fun specGet(cardId: String): SpecView {
        requireOwnCard("spec_get", cardId)
        val text = when (val read = specs.read(cardId)) {
            is SpecRead.Valid -> read.text
            is SpecRead.Diverged -> throw McpToolException("spec of card $cardId diverged from its registered hash")
            is SpecRead.NotRegistered -> throw McpToolException("card $cardId has no spec")
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw McpToolException("spec of card $cardId exceeds the $MAX_BYTES byte limit")
        }
        log.log("mcp_tool", mapOf("tool" to "spec_get", "card" to cardId))
        return SpecView(cardId, UntrustedText("spec", text))
    }

    fun reviewCriteriaGet(): CriteriaView {
        val project = project()
        val text = files.read(Path.of(project.repo), CRITERIA_PATH)
            ?: throw McpToolException("$CRITERIA_FILE not found on main")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw McpToolException("$CRITERIA_FILE exceeds the $MAX_BYTES byte limit")
        }
        log.log("mcp_tool", mapOf("tool" to "review_criteria_get", "project" to project.name))
        return CriteriaView(UntrustedText("project-file", text))
    }

    fun policiesGet(): ProjectPolicies = project().policies

    fun layersGet(): List<ProjectLayer> = project().layers

    private fun project(): Project =
        registry.list().firstOrNull { it.repo == session.repo }
            ?: throw McpToolException("project ${session.repo} is not registered")

    private fun requireOwnCard(tool: String, cardId: String) {
        if (!CARD_ID.matches(cardId)) {
            log.log("mcp_refused", mapOf("tool" to tool, "reason" to "invalid_card_id"))
            throw McpToolException("argument cardId is invalid")
        }
        if (store.projectOf(cardId) != session.repo) {
            log.log("mcp_refused", mapOf("tool" to tool, "card" to cardId))
            throw McpToolException("card $cardId does not belong to project ${project().name}")
        }
    }

    private companion object {
        val CARD_ID = Regex("[A-Za-z0-9-]{1,64}")
        const val CRITERIA_PATH = ".shipfloor/review-criteria.md"
        const val CRITERIA_FILE = "review-criteria.md"
        const val MAX_BYTES = ProjectFileSource.MAX_BYTES
    }
}
