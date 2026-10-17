package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.AcceptancePatterns
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.EventType
import shipfloor.ports.FakeRuntime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.SpecStore

class DevelopSkillTest {

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
                return factory.serverFor(McpSession("repo", "engineer", "c37")).tools.keys
            } finally {
                events.close()
            }
        } finally {
            database.close()
        }
    }

    private val skill: String get() = StageSkills.load("develop")

    private fun citedTools(text: String): Set<String> =
        Regex("`([a-z]+(?:_[a-z]+)*_(?:get|request|report))`").findAll(text).map { it.groupValues[1] }.toSet()

    private fun ruleText(text: String, rule: String): String? =
        Regex("(?s)<!--\\s*rule:$rule\\s*-->\\s*(.+?)(?:\\n\\s*\\n|<!--|\\z)").find(text)?.groupValues?.get(1)?.trim()

    private class FakeRunner(private val output: String, private val exitCode: Int) : GateRunner {
        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            Files.writeString(outputFile, output)
            return GateRun(exitCode, timedOut = false)
        }
    }

    @Test
    fun `AC-1 every tool the develop skill cites is registered by the server and an unknown one is caught`(@TempDir dir: Path) {
        val registered = registeredTools(dir)

        val cited = citedTools(skill)

        assertThat(registered).containsExactlyInAnyOrder(
            "card_get", "spec_get", "review_criteria_get", "policies_get", "layers_get", "completion_report", "file_write_request",
            "file_edit_request",
        )
        assertThat(cited).contains("card_get", "spec_get", "layers_get", "file_write_request", "completion_report")
        assertThat(registered).containsAll(cited)
        assertThat(citedTools("use `card_get` then `ghost_get`") - registered).containsExactly("ghost_get")
    }

    @Test
    fun `AC-2 the develop skill orders QA red tests before engineers who each stay in their own layer`() {
        val text = skill.lowercase()

        assertThat(StageSkills.steps("develop")).containsExactly(SkillStep("qa", "red"), SkillStep("engineer", "green"))
        assertThat(text).contains("vermelho").contains("layers_get").contains("camada")
        assertThat(text.indexOf("vermelho")).isNotNegative().isLessThan(text.indexOf("verde"))
    }

    @Test
    fun `AC-3 AC-4 red then implementation then green are recorded in order, each with its actor, and the card does not move`(
        @TempDir dir: Path,
    ) {
        val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
        val database = Database.open(dir.resolve("factory.db"))
        val store = CardEventStore(database)
        val events = EventStream(store)
        try {
            val config = McpFixtures.FakeConfigSource()
            val registry = ProjectRegistry(config)
            val repo = Files.createDirectories(dir.resolve("alpha"))
            Files.createDirectories(repo.resolve(".git"))
            config.files[repo.toRealPath()] = McpFixtures.yaml("alpha")
            val project = registry.register(repo)
            store.recordChange("c37", Card.create("Skill de develop", Column.CODING), McpFixtures.anEvent("e-c37"))
            store.assignProject("c37", project.repo)
            val worktrees = WorktreeManager(dir.resolve("wt"), WorktreeFixtures.FakeWorktreeGit())
            val worktree = Files.createDirectories(worktrees.ensure(project, "c37", "Skill de develop").path)
            val recorder = EventRecorder(store, events)
            val patterns = AcceptancePatterns(Regex("tests completed"), Regex("ASSERT_FAIL"), Regex("SETUP_BROKEN"))
            val steps = StageSkills.steps("develop")
            val runtime = FakeRuntime(steps.map { SessionScript.Succeed })
            val outputs = mapOf(
                "red" to FakeRunner("1 tests completed, 1 failed\nASSERT_FAIL expected 1\n", 1),
                "green" to FakeRunner("1 tests completed\n", 0),
            )
            val writes = mapOf(
                "qa" to "daemon/src/test/kotlin/shipfloor/daemon/XTest.kt",
                "engineer" to "daemon/src/main/kotlin/shipfloor/daemon/X.kt",
            )

            for (step in steps) {
                val tools = McpRequestTools(McpSession(project.repo, step.role, "c37"), store, recorder, worktrees, registry, clock)
                val request = SessionRequest("develop", worktree, "model", dir.resolve("mcp.json"), "prompt")
                assertThat((runtime.launch(request) as SessionResult.Exited).exitCode).isZero()
                tools.fileWriteRequest("c37", requireNotNull(writes[step.role]), "// ${step.role}")
                tools.completionReport("c37", "${step.role} done")
                val gate = GateSpec(step.gate, listOf("./gate.sh"), worktree, Duration.ofSeconds(30), check = checkFor(step.gate, patterns))
                val verdict = GateExecutor(store, recorder, clock, requireNotNull(outputs[step.gate])).execute("c37", gate, dir.resolve("run-${step.gate}"))
                assertThat(verdict).isEqualTo(GateVerdict.Pass)
            }

            val trail = store.events("c37")
                .filter { it.type == EventType.AGENT_REPORTED || it.type == EventType.GATE_FINISHED }
            assertThat(trail.map { it.type to it.actor }).containsExactly(
                EventType.AGENT_REPORTED to Actor.Agent("qa"),
                EventType.GATE_FINISHED to Actor.Factory("gate"),
                EventType.AGENT_REPORTED to Actor.Agent("engineer"),
                EventType.GATE_FINISHED to Actor.Factory("gate"),
            )
            assertThat(trail.filter { it.type == EventType.GATE_FINISHED }.map { it.payload["gate"] })
                .containsExactly("red", "green")
            assertThat(Files.readString(worktree.resolve(requireNotNull(writes["qa"])))).isEqualTo("// qa")
            assertThat(store.card("c37")?.column).isEqualTo(Column.CODING)
            assertThat(store.events("c37").map { it.type }).doesNotContain(EventType.CARD_MOVED)
        } finally {
            events.close()
            database.close()
        }
    }

    private fun checkFor(gate: String, patterns: AcceptancePatterns): GateCheck =
        if (gate == "red") GateCheck.Red(patterns) else GateCheck.Green(patterns)

    @Test
    fun `AC-4 the develop skill reports through completion_report and writes through file_write_request only`(@TempDir dir: Path) {
        val cited = citedTools(skill)

        assertThat(cited).contains("completion_report", "file_write_request")
        assertThat(registeredTools(dir)).containsAll(cited)
        assertThat(cited.filter { it.endsWith("_request") || it.endsWith("_report") })
            .containsExactlyInAnyOrder("completion_report", "file_write_request", "file_edit_request")
    }

    @Test
    fun `AC-5 the develop skill carries the three security rules, the shared two identical to refine`() {
        val develop = skill
        val refine = StageSkills.load("refine")

        assertThat(ruleText(develop, "untrusted-data")).isNotBlank().contains("untrusted")
            .isEqualTo(ruleText(refine, "untrusted-data"))
        assertThat(ruleText(develop, "no-shell")).isNotBlank().contains("gh", "git", "board.sh")
            .isEqualTo(ruleText(refine, "no-shell"))
        assertThat(ruleText(develop, "write-scope")).isNotBlank().contains("camada").contains("file_write_request")
        assertThat(ruleText("<!-- rule:no-shell -->\nsem shell\n", "untrusted-data")).isNull()
    }
}
