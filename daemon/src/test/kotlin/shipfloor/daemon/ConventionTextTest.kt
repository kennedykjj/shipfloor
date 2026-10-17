package shipfloor.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Issue #90. Signatures these tests fix for the pure object `ConventionText` (daemon):
 * - `commitSubject(format, type, scope, subject, id, ticket: String?)`: `{id}` is always [id] (the card id),
 *   `{ticket}` is [ticket] when it matches `^[A-Za-z0-9_-]{1,40}$`, otherwise [id].
 * - `pullRequestTitle(format, scope, subject, id, ticket)`: the same as commitSubject with type `feat`.
 * - `pullRequestBody(template: String?, language, id, ticket)`: the template verbatim plus a line
 *   `Ticket: <key>` (en) or `Chamado: <key>` (pt-BR); the key is the validated ticket or [id]. Without a template
 *   the body is `Card <id>` (en) or `Cartão <id>` (pt-BR) plus that line.
 */
class ConventionTextTest {

    @Test
    fun `AC-1 the ticket key goes where the format puts it in the commit subject and in the PR title`() {
        assertThat(ConventionText.commitSubject("[{ticket}] {type}({scope}): {subject}", "feat", "core", "add x", "c1", "PROJ-123"))
            .isEqualTo("[PROJ-123] feat(core): add x")
        assertThat(ConventionText.pullRequestTitle("{ticket} {type}: {subject}", "core", "add x", "c1", "PROJ-123"))
            .isEqualTo("PROJ-123 feat: add x")
    }

    @Test
    fun `AC-6 a card without ticket uses its id for ticket and id stays the card id even with a ticket`() {
        assertThat(ConventionText.commitSubject("[{ticket}] {subject}", "feat", "core", "add x", "c1", null))
            .isEqualTo("[c1] add x")
        assertThat(ConventionText.commitSubject("{id}/{ticket}: {subject}", "feat", "core", "add x", "c1", "PROJ-123"))
            .isEqualTo("c1/PROJ-123: add x")
        val outputs = listOf(
            ConventionText.commitSubject("{type}({scope}): {subject} {ticket}", "feat", "core", "add x", "c1", null),
            ConventionText.pullRequestTitle("[{ticket}] {subject}", "core", "add x", "c1", null),
            ConventionText.pullRequestBody(null, "en", "c1", null),
        )
        assertThat(outputs).noneMatch { it.contains("{") || it.contains("}") }
        assertThat(outputs[2]).contains("Ticket: c1")
    }

    @Test
    fun `AC-7 an unsafe ticket key is replaced by the card id in commit, title and body`() {
        val unsafe = listOf("PROJ-1\nINJECTED", "PROJ 1", "PROJ-1]\n## Injected", "A".repeat(41), "PROJ-1<b>", "")
        unsafe.forEach { key ->
            val subject = ConventionText.commitSubject("[{ticket}] {subject}", "feat", "core", "add x", "c1", key)
            val title = ConventionText.pullRequestTitle("[{ticket}] {subject}", "core", "add x", "c1", key)
            val body = ConventionText.pullRequestBody("## Summary\n", "en", "c1", key)

            assertThat(subject).describedAs(key).isEqualTo("[c1] add x")
            assertThat(title).describedAs(key).isEqualTo("[c1] add x")
            assertThat(body).describedAs(key).contains("Ticket: c1").doesNotContain("INJECTED").doesNotContain("Injected")
                .doesNotContain("PROJ").doesNotContain("A".repeat(41))
        }
        val longest = "A".repeat(39) + "-"
        assertThat(ConventionText.commitSubject("[{ticket}] {subject}", "feat", "core", "add x", "c1", longest))
            .isEqualTo("[$longest] add x")
    }

    @Test
    fun `AC-3 the template comes verbatim in the body and AC-4 the labels follow the language`() {
        val template = "## Summary\n\n## How to test\n"

        val en = ConventionText.pullRequestBody(template, "en", "c1", "PROJ-123")
        val pt = ConventionText.pullRequestBody(template, "pt-BR", "c1", "PROJ-123")

        assertThat(en).contains(template).contains("Ticket: PROJ-123")
        assertThat(pt).contains(template).contains("Chamado: PROJ-123").doesNotContain("Ticket:")
        assertThat(ConventionText.pullRequestBody(null, "en", "c1", null)).contains("Card c1")
        assertThat(ConventionText.pullRequestBody(null, "pt-BR", "c1", null)).contains("Cartão c1").doesNotContain("Card c1")
    }
}
