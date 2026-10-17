package shipfloor.core

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * API these tests define (the implementation follows it):
 * - `AgentRequests.reportCompletion(cardId, card, role, summary, id, at): Event` and
 *   `AgentRequests.requestWrite(cardId, card, role, path, bytes, id, at): Event`, pure.
 * - `AgentRequestRefusedException(message)` is a RuntimeException whose message names the card id.
 */
class AgentRequestsTest {

    private val at = Instant.parse("2026-10-16T10:00:00Z")
    private val id = EventId("evt-9")
    private val coding = Card.create("Add login", Column.CODING)
    private val workColumns = listOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION)

    @Test
    fun `AC-5 a completion report becomes an agent event with the role, summary and current column`() {
        workColumns.forEach { column ->
            val card = Card.create("Add login", column)

            val event = AgentRequests.reportCompletion("c1", card, "engineer", "  done, tests green  ", id, at)

            assertThat(event.id).isEqualTo(id)
            assertThat(event.at).isEqualTo(at)
            assertThat(event.actor).isEqualTo(Actor.Agent("engineer"))
            assertThat(event.type.name).isEqualTo("AGENT_REPORTED")
            assertThat(event.payload).containsEntry("column", column.name)
            assertThat(event.payload["summary"]).contains("done, tests green")
        }
    }

    @Test
    fun `AC-5 a write request records path and size but never the content`() {
        val event = AgentRequests.requestWrite("c1", coding, "qa", "src/Login.kt", 42, id, at)

        assertThat(event.actor).isEqualTo(Actor.Agent("qa"))
        assertThat(event.type.name).isEqualTo("AGENT_WRITE_REQUESTED")
        assertThat(event.payload).containsEntry("path", "src/Login.kt").containsEntry("bytes", "42")
            .containsEntry("column", "CODING")
        assertThat(event.payload.keys).doesNotContain("content")
    }

    @Test
    fun `AC-5 the new events survive the JSON round trip`() {
        val events = listOf(
            AgentRequests.reportCompletion("c1", coding, "engineer", "done", id, at),
            AgentRequests.requestWrite("c1", coding, "engineer", "a.txt", 1, id, at),
        )

        events.forEach {
            assertThat(it.type.name).startsWith("AGENT_")
            assertThat(EventJson.decode(EventJson.encode(it))).isEqualTo(it)
        }
    }

    @Test
    fun `AC-5 a card outside the agent work columns refuses both requests naming the card`() {
        Column.entries.filterNot { it in workColumns }.forEach { column ->
            val card = Card.create("Add login", column)

            listOf(
                { AgentRequests.reportCompletion("c-out", card, "engineer", "done", id, at) },
                { AgentRequests.requestWrite("c-out", card, "engineer", "a.txt", 1, id, at) },
            ).forEach { call ->
                assertThatThrownBy { call() }
                    .isInstanceOf(AgentRequestRefusedException::class.java)
                    .isInstanceOf(RuntimeException::class.java)
                    .hasMessageContaining("c-out")
            }
        }
    }

    @Test
    fun `AC-5 only the conflict role works on a card in Ready for Prod`() {
        val card = Card.create("Add login", Column.READY_FOR_PROD)

        val report = AgentRequests.reportCompletion("c-rfp", card, AgentRequests.CONFLICT_ROLE, "resolved", id, at)
        val write = AgentRequests.requestWrite("c-rfp", card, AgentRequests.CONFLICT_ROLE, "a.txt", 1, id, at)

        assertThat(report.type).isEqualTo(EventType.AGENT_REPORTED)
        assertThat(write.type).isEqualTo(EventType.AGENT_WRITE_REQUESTED)
        assertThatThrownBy { AgentRequests.requestWrite("c-rfp", card, "engineer", "a.txt", 1, id, at) }
            .isInstanceOf(AgentRequestRefusedException::class.java)
            .hasMessageContaining("c-rfp")
        Column.entries.filterNot { it in workColumns || it == Column.READY_FOR_PROD }.forEach { column ->
            assertThatThrownBy {
                AgentRequests.reportCompletion("c-out", Card.create("Add login", column), AgentRequests.CONFLICT_ROLE, "done", id, at)
            }.isInstanceOf(AgentRequestRefusedException::class.java)
        }
    }

    @Test
    fun `AC-5 a blank summary or one above 4 KB is refused and 4 KB exactly is accepted`() {
        val atLimit = "a".repeat(4096)

        assertThat(AgentRequests.reportCompletion("c1", coding, "engineer", atLimit, id, at).payload["summary"]).hasSize(4096)
        listOf("", "   \n", "a".repeat(4097), "é".repeat(2049)).forEach { summary ->
            assertThatThrownBy { AgentRequests.reportCompletion("c1", coding, "engineer", summary, id, at) }
                .isInstanceOf(AgentRequestRefusedException::class.java)
                .hasMessageContaining("c1")
        }
    }

    @Test
    fun `AC-5 a write path that is empty, absolute or has a control character is refused`() {
        listOf("", "   ", "/etc/passwd", "a\u0000b", "a\nb").forEach { path ->
            assertThatThrownBy { AgentRequests.requestWrite("c1", coding, "engineer", path, 1, id, at) }
                .isInstanceOf(AgentRequestRefusedException::class.java)
                .hasMessageContaining("c1")
        }
    }
}
