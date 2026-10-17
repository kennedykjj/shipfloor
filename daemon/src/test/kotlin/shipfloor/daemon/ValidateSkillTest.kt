package shipfloor.daemon

import java.nio.file.Path
import java.time.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.SpecStore

class ValidateSkillTest {

    /** The tools the real MCP server registers for a session, read from the server itself. */
    private fun registeredTools(dir: Path): Set<String> {
        val database = Database.open(dir.resolve("tools.db"))
        try {
            val store = CardEventStore(database)
            val events = EventStream(store)
            try {
                val config = McpFixtures.FakeConfigSource()
                val factory = McpServerFactory(
                    McpSessions(),
                    store,
                    SpecStore(database, dir.resolve("specs")),
                    ProjectRegistry(config),
                    McpFixtures.FakeFileSource(),
                    recorder = EventRecorder(store, events),
                    worktrees = WorktreeManager(dir.resolve("wt-tools"), WorktreeFixtures.FakeWorktreeGit()),
                    clock = Clock.systemUTC(),
                )
                return factory.serverFor(McpSession("repo", "validate", "c38")).tools.keys
            } finally {
                events.close()
            }
        } finally {
            database.close()
        }
    }

    private val skill: String get() = StageSkills.load("validate")

    private fun citedTools(text: String): Set<String> =
        Regex("`([a-z]+(?:_[a-z]+)*_(?:get|request|report))`").findAll(text).map { it.groupValues[1] }.toSet()

    private fun ruleText(text: String, rule: String): String? =
        Regex("(?s)<!--\\s*rule:$rule\\s*-->\\s*(.+?)(?:\\n\\s*\\n|<!--|\\z)").find(text)?.groupValues?.get(1)?.trim()

    @Test
    fun `AC-1 every tool the validate skill cites is registered by the server and no other appears`(@TempDir dir: Path) {
        val registered = registeredTools(dir)

        val cited = citedTools(skill)

        assertThat(registered).containsExactlyInAnyOrder(
            "card_get", "spec_get", "review_criteria_get", "policies_get", "layers_get", "completion_report", "file_write_request",
            "file_edit_request",
        )
        assertThat(cited).isNotEmpty()
        assertThat(registered).containsAll(cited)
        assertThat(citedTools("use `card_get` then `ghost_get`") - registered).containsExactly("ghost_get")
    }

    @Test
    fun `AC-2 the validate skill delivers its result by completion_report and never orders a card move or a gate run`(@TempDir dir: Path) {
        val text = skill
        val lower = text.lowercase()

        assertThat(citedTools(text)).contains("completion_report")
        assertThat(registeredTools(dir)).noneMatch { it.contains("move") || it.contains("gate") }
        assertThat(lower).contains("daemon")
        assertThat(lower).doesNotContain("card_move").doesNotContain("card.move").doesNotContain("gate.sh")
        assertThat(lower).doesNotContain("rode o gate").doesNotContain("rodar o gate").doesNotContain("execute o gate")
        assertThat(text).contains("VERDICT: APPROVED").contains("VERDICT: BLOCKED layer=")
    }

    @Test
    fun `the conflict skill cites only registered tools and carries the three security rules`(@TempDir dir: Path) {
        val conflict = StageSkills.load("conflict")
        val refine = StageSkills.load("refine")

        assertThat(citedTools(conflict)).isNotEmpty()
        assertThat(registeredTools(dir)).containsAll(citedTools(conflict))
        assertThat(ruleText(conflict, "untrusted-data")).isNotBlank().contains("untrusted")
        assertThat(ruleText(conflict, "no-shell")).isNotBlank().contains("gh", "git", "board.sh")
            .isEqualTo(ruleText(refine, "no-shell"))
        assertThat(ruleText(conflict, "write-scope")).isNotBlank().contains("conflitantes")
    }

    @Test
    fun `AC-7 the validate skill carries the three security rules and the APPROVED-needs-green-gates rule`() {
        val validate = skill
        val refine = StageSkills.load("refine")

        assertThat(ruleText(validate, "untrusted-data")).isNotBlank().contains("untrusted")
            .isEqualTo(ruleText(refine, "untrusted-data"))
        assertThat(ruleText(validate, "no-shell")).isNotBlank().contains("gh", "git", "board.sh")
            .isEqualTo(ruleText(refine, "no-shell"))
        assertThat(ruleText(validate, "write-scope")).isNotBlank()
        assertThat(ruleText("<!-- rule:no-shell -->\nsem shell\n", "untrusted-data")).isNull()
        assertThat(validate.lowercase()).contains("approved").contains("gates")
        assertThat(validate.lowercase()).containsPattern("approved[^\\n]*(nunca|não)[^\\n]*(basta|sozinho)|(nunca|não)[^\\n]*(basta|sozinho)[^\\n]*approved")
    }
}
