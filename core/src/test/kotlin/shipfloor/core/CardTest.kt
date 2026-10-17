package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class CardTest {

    @Test
    fun `AC-1 card created without column starts in Inbox`() {
        val card = Card.create("Write the spec")

        assertThat(card.column).isEqualTo(Column.INBOX)
    }

    @Test
    fun `AC-2 columns are listed in board order with display names`() {
        assertThat(Column.entries).containsExactly(
            Column.INBOX,
            Column.READY_FOR_REFINEMENT,
            Column.REFINEMENT,
            Column.SPEC_REVIEW,
            Column.READY_FOR_DEV,
            Column.CODING,
            Column.VALIDATION,
            Column.STAGED,
            Column.READY_FOR_PROD,
            Column.DONE,
        )
        assertThat(Column.READY_FOR_REFINEMENT.displayName).isEqualTo("Ready for Refinement")
    }

    @Test
    fun `AC-3 blank title is rejected with a validation error naming the title field`() {
        listOf("", "   ", "\t \n").forEach { blank ->
            val error = assertThrows<CardValidationException> { Card.create(blank) }

            assertThat(error).isInstanceOf(RuntimeException::class.java)
            assertThat(error.message).contains("title")
        }
    }

    @Test
    fun `AC-4 title surrounding spaces are trimmed`() {
        val card = Card.create("  Write the spec  ")

        assertThat(card.title).isEqualTo("Write the spec")
    }

    private val importedAt = Instant.parse("2026-10-15T10:00:00Z")

    private fun aRef(provider: String = "github", externalId: String = "42", text: String = "spec text") =
        ExternalRef(
            provider = provider,
            externalId = externalId,
            url = "https://example.com/$provider/$externalId",
            importedAt = importedAt,
            textHash = TextHash.of(text),
        )

    @Test
    fun `issue 11 AC-1 external ref keeps provider id url import date and text hash`() {
        val hash = TextHash.of("spec text")

        val ref = ExternalRef("jira", "ABC-1", "https://jira.example/ABC-1", importedAt, hash)

        assertThat(ref.provider).isEqualTo("jira")
        assertThat(ref.externalId).isEqualTo("ABC-1")
        assertThat(ref.url).isEqualTo("https://jira.example/ABC-1")
        assertThat(ref.importedAt).isEqualTo(importedAt)
        assertThat(ref.textHash).isEqualTo(hash)
    }

    @Test
    fun `issue 11 AC-2 text hash is stable for same text and changes with one character`() {
        assertThat(TextHash.of("hello")).isEqualTo(TextHash.of("hello"))
        assertThat(TextHash.of("hello")).isNotEqualTo(TextHash.of("hellp"))
    }

    @Test
    fun `issue 11 AC-3 card accumulates references with different provider or external id`() {
        val card = Card.create("Write the spec")
            .withReference(aRef("github", "42"))
            .withReference(aRef("github", "43"))
            .withReference(aRef("jira", "42"))

        assertThat(card.references.map { it.provider to it.externalId })
            .containsExactly("github" to "42", "github" to "43", "jira" to "42")
    }

    @Test
    fun `issue 11 AC-4 duplicate provider and external id is refused naming both`() {
        val card = Card.create("Write the spec").withReference(aRef("github", "42"))

        val error = assertThrows<DuplicateExternalRefException> {
            card.withReference(aRef("github", "42", text = "other text"))
        }

        assertThat(error).isInstanceOf(RuntimeException::class.java)
        assertThat(error.message).contains("github").contains("42")
        assertThat(card.references).hasSize(1)
    }
}
