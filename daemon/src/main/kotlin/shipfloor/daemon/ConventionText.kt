package shipfloor.daemon

import shipfloor.core.ExternalRef

/** Pure text builders for commit subjects, pull request titles and bodies from the project conventions. */
object ConventionText {
    private val SAFE_TICKET = Regex("^[A-Za-z0-9_-]{1,40}$")

    /** The card's ticket key: the external id of its first reference, if any. */
    fun ticketOf(references: List<ExternalRef>): String? = references.firstOrNull()?.externalId

    fun commitSubject(format: String, type: String, scope: String, subject: String, id: String, ticket: String?): String =
        format
            .replace("{type}", type)
            .replace("{scope}", scope)
            .replace("{subject}", subject)
            .replace("{id}", id)
            .replace("{ticket}", ticketKey(id, ticket))

    fun pullRequestTitle(format: String, scope: String, subject: String, id: String, ticket: String?): String =
        commitSubject(format, "feat", scope, subject, id, ticket)

    fun pullRequestBody(template: String?, language: String, id: String, ticket: String?): String {
        val pt = language == "pt-BR"
        val line = (if (pt) "Chamado: " else "Ticket: ") + ticketKey(id, ticket)
        val head = template ?: ((if (pt) "Cartão " else "Card ") + id + "\n")
        val separated = if (head.endsWith("\n")) head else head + "\n"
        return separated + "\n" + line + "\n"
    }

    private fun ticketKey(id: String, ticket: String?): String = ticket?.takeIf { SAFE_TICKET.matches(it) } ?: id
}
