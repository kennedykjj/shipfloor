package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.ports.FakeRuntime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.ports.SessionScript
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.SpecRead
import shipfloor.store.SpecStore

class SpecGatesTest {

    private val daemonTest = "daemon/src/test/kotlin/shipfloor/daemon/XTest.kt"
    private val layers = listOf(
        SpecLayer("daemon", "daemon/", listOf("daemon/src/test/**")),
        SpecLayer("cli", "cli/", listOf("cli/src/test/**")),
    )

    private fun aSpec(
        weight: String? = "light",
        criteria: String = "- **AC-1** — primeiro criterio",
        testPlan: String = "| AC-1 | daemon | `$daemonTest` |",
        architecture: String = "Camada unica. Arquivos: `daemon/src/main/kotlin/A.kt`, `daemon/src/main/kotlin/B.kt`.",
        security: String = "_Cybersec_ — n/a",
        rollback: String = "n/a",
        touchesUi: String = "no",
    ): String {
        val weightLine = if (weight == null) "" else "**Weight:** $weight\n"
        return """
            # Spec

            **Issue:** #1
            $weightLine
            ## Product
            **Acceptance criteria**
            $criteria
            **Touches UI:** $touchesUi

            ## Architecture
            $architecture

            ## Test plan
            | AC | Layer | Test file |
            |---|---|---|
            $testPlan

            ## Security
            $security

            ## Rollback
            $rollback

            ## Decisions
            - nada
        """.trimIndent()
    }

    private fun check(spec: String) = SpecGates.check(spec, layers)

    @Test
    fun `AC-1 approves a spec whose criterion has a known layer and a matching test path`() {
        val result = check(aSpec())

        assertThat(result.findings).isEmpty()
    }

    @Test
    fun `AC-1 reports a criterion without a test plan row naming the AC`() {
        val result = check(aSpec(
            criteria = "- **AC-1** — um\n- **AC-2** — dois",
            testPlan = "| AC-1 | daemon | `$daemonTest` |",
        ))

        assertThat(result.findings).hasSize(1)
        assertThat(result.findings.single().gate).isEqualTo(SpecGate.TEST_PLAN)
        assertThat(result.findings.single().ac).isEqualTo("AC-2")
    }

    @Test
    fun `AC-1 reports a criterion whose layer does not exist in the project`() {
        val result = check(aSpec(testPlan = "| AC-1 | mobile | `mobile/src/test/X.kt` |"))

        assertThat(result.findings.map { it.gate to it.ac }).containsExactly(SpecGate.TEST_PLAN to "AC-1")
        assertThat(result.findings.single().message).contains("mobile")
    }

    @Test
    fun `AC-1 reports a real layer row without a test path`() {
        val result = check(aSpec(testPlan = "| AC-1 | daemon | |"))

        assertThat(result.findings.map { it.gate to it.ac }).containsExactly(SpecGate.TEST_PLAN to "AC-1")
    }

    @Test
    fun `AC-1 reports a test path that matches no test glob of the layer`() {
        val result = check(aSpec(testPlan = "| AC-1 | daemon | `daemon/src/main/kotlin/X.kt` |"))

        assertThat(result.findings.map { it.gate to it.ac }).containsExactly(SpecGate.TEST_PLAN to "AC-1")
    }

    @Test
    fun `AC-1 accepts a manual layer row without a path`() {
        val result = check(aSpec(testPlan = "| AC-1 | manual | |"))

        assertThat(result.findings).isEmpty()
    }

    @Test
    fun `AC-2 calculates light for one layer, few paths, no UI and no risk signals`() {
        assertThat(check(aSpec()).weight).isEqualTo(SpecWeight.LIGHT)
    }

    @Test
    fun `AC-2 calculates heavy when Architecture paths touch two layers`() {
        val result = check(aSpec(
            weight = "heavy",
            architecture = "Arquivos: `daemon/src/main/kotlin/A.kt`, `cli/src/main/kotlin/B.kt`.",
        ))

        assertThat(result.weight).isEqualTo(SpecWeight.HEAVY)
    }

    @Test
    fun `AC-2 calculates heavy when Security is filled in`() {
        val result = check(aSpec(weight = "heavy", security = "_Cybersec_\nToken passa por log; mascarar."))

        assertThat(result.weight).isEqualTo(SpecWeight.HEAVY)
    }

    @Test
    fun `AC-2 calculates heavy when Rollback is not n-a`() {
        val result = check(aSpec(weight = "heavy", rollback = "Reverter a migracao com drop da coluna."))

        assertThat(result.weight).isEqualTo(SpecWeight.HEAVY)
    }

    @Test
    fun `AC-2 calculates heavy with more than eight paths in one layer`() {
        val paths = (1..9).joinToString(", ") { "`daemon/src/main/kotlin/F$it.kt`" }
        val result = check(aSpec(weight = "heavy", architecture = "Arquivos: $paths."))

        assertThat(result.weight).isEqualTo(SpecWeight.HEAVY)
    }

    @Test
    fun `AC-2 calculates standard when there are four paths in one layer`() {
        val paths = (1..4).joinToString(", ") { "`daemon/src/main/kotlin/F$it.kt`" }
        val result = check(aSpec(weight = "standard", architecture = "Arquivos: $paths."))

        assertThat(result.weight).isEqualTo(SpecWeight.STANDARD)
    }

    @Test
    fun `AC-2 calculates standard when the card touches UI`() {
        val result = check(aSpec(weight = "standard", touchesUi = "yes"))

        assertThat(result.weight).isEqualTo(SpecWeight.STANDARD)
    }

    @Test
    fun `AC-2 does not let the number of criteria change the weight`() {
        val criteria = (1..12).joinToString("\n") { "- **AC-$it** — criterio $it" }
        val plan = (1..12).joinToString("\n") { "| AC-$it | daemon | `$daemonTest` |" }
        val result = check(aSpec(criteria = criteria, testPlan = plan))

        assertThat(result.weight).isEqualTo(SpecWeight.LIGHT)
        assertThat(result.findings).isEmpty()
    }

    @Test
    fun `AC-3 reports a missing declared weight with the calculated value`() {
        val result = check(aSpec(weight = null))

        assertThat(result.findings.map { it.gate }).containsExactly(SpecGate.WEIGHT)
        assertThat(result.findings.single().message.lowercase()).contains("light")
    }

    @Test
    fun `AC-3 reports an invalid declared weight with both values`() {
        val result = check(aSpec(weight = "gigantic"))

        assertThat(result.findings.map { it.gate }).containsExactly(SpecGate.WEIGHT)
        assertThat(result.findings.single().message.lowercase()).contains("gigantic").contains("light")
    }

    @Test
    fun `AC-3 reports a declared weight below the calculated one with both values`() {
        val result = check(aSpec(weight = "light", security = "_Cybersec_\nToken em log."))

        assertThat(result.weight).isEqualTo(SpecWeight.HEAVY)
        assertThat(result.findings.map { it.gate }).containsExactly(SpecGate.WEIGHT)
        assertThat(result.findings.single().message.lowercase()).contains("light").contains("heavy")
    }

    @Test
    fun `AC-3 accepts a declared weight above the calculated one`() {
        val result = check(aSpec(weight = "heavy"))

        assertThat(result.weight).isEqualTo(SpecWeight.LIGHT)
        assertThat(result.findings).isEmpty()
    }

    @Test
    fun `AC-4 finds criteria written in every format used by factory specs`() {
        val criteria = listOf(
            "- **AC-1** — bold dash",
            "- AC-2: colon",
            "AC-3 — bare dash",
        ).joinToString("\n")
        val result = check(aSpec(criteria = criteria, testPlan = "| AC-1 | daemon | `$daemonTest` |"))

        assertThat(result.findings.map { it.ac }).containsExactlyInAnyOrder("AC-2", "AC-3")
        assertThat(result.findings.map { it.gate }.toSet()).containsExactly(SpecGate.TEST_PLAN)
    }

    @Test
    fun `AC-4 reports a TEST_PLAN finding when no criterion is recognizable`() {
        val result = check(aSpec(criteria = "- o sistema deve funcionar", testPlan = ""))

        assertThat(result.findings.map { it.gate }).contains(SpecGate.TEST_PLAN)
    }

    /** Scripted refine agent: the runtime launches it, then it fills the skill's template and writes the spec through the MCP tool. */
    private class ScriptedRefineAgent(private val tools: McpRequestTools, private val template: String) {
        private val bodies = mapOf(
            "Product" to "**Acceptance criteria**\n- **AC-1** — primeiro criterio\n**Touches UI:** no",
            "Architecture" to "Camada unica. Arquivos: `daemon/src/main/kotlin/A.kt`.",
            "Test plan" to "| AC | Layer | Test file |\n|---|---|---|\n| AC-1 | daemon | `daemon/src/test/kotlin/shipfloor/daemon/XTest.kt` |",
            "Security" to "n/a",
            "Rollback" to "n/a",
            "Decisions" to "- nada",
        )

        fun run(runtime: FakeRuntime, request: SessionRequest, cardId: String, path: String) {
            val exited = runtime.launch(request) as SessionResult.Exited
            assertThat(exited.exitCode).isEqualTo(0)
            tools.fileWriteRequest(cardId, path, fill())
        }

        private fun fill(): String {
            val out = mutableListOf<String>()
            var skipping = false
            for (line in template.lines()) {
                if (line.startsWith("## ")) {
                    skipping = true
                    out += line
                    out += bodies[line.removePrefix("## ").trim()] ?: "n/a"
                    out += ""
                } else if (!skipping) {
                    out += if (line.startsWith("**Weight:**")) "**Weight:** light" else line
                }
            }
            return out.joinToString("\n")
        }
    }

    private fun registeredAfterGates(dir: Path, specText: String): SpecRead {
        val database = Database.open(dir.resolve("gates.db"))
        val events = EventStream(CardEventStore(database))
        try {
            val worktree = Files.createDirectories(dir.resolve("wt"))
            val specFile = "docs/superpowers/specs/2026-10-16-x-design.md"
            Files.createDirectories(worktree.resolve(specFile).parent)
            Files.writeString(worktree.resolve(specFile), specText)
            val changed = object : ChangedPaths {
                override fun between(worktree: Path, base: String): List<String> = emptyList()

                override fun untracked(worktree: Path): List<String> = listOf(specFile)
            }
            val specs = SpecStore(database, dir.resolve("specs"))
            val store = CardEventStore(database)
            val gates = SpecScopeRefineGates(
                DevelopFixtures.project, store, EventRecorder(store, events), changed,
                Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC), specs = specs,
            )

            gates.evaluate("c1", worktree)

            return specs.read("c1")
        } finally {
            events.close()
            database.close()
        }
    }

    @Test
    fun `AC-5 a spec that passes the gates is registered for the card`(@TempDir dir: Path) {
        val spec = aSpec()

        val registered = registeredAfterGates(dir, spec)

        assertThat(registered).isInstanceOf(SpecRead.Valid::class.java)
        assertThat((registered as SpecRead.Valid).text).isEqualTo(spec)
    }

    @Test
    fun `AC-5 a spec that fails a gate is not registered`(@TempDir dir: Path) {
        val registered = registeredAfterGates(dir, aSpec(testPlan = ""))

        assertThat(registered).isInstanceOf(SpecRead.NotRegistered::class.java)
    }

    @Test
    fun `AC-2 the scripted runtime fills the refine template and the written spec raises no gate finding`(@TempDir dir: Path) {
        val resource = javaClass.classLoader.getResourceAsStream("skills/refine/spec-template.md")
        assertThat(resource).describedAs("skills/refine/spec-template.md is packaged").isNotNull()
        val template = requireNotNull(resource).use { String(it.readAllBytes(), Charsets.UTF_8) }
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
            store.recordChange("c36", Card.create("Skill de refine", Column.REFINEMENT), McpFixtures.anEvent("e-c36"))
            store.assignProject("c36", project.repo)
            val worktrees = WorktreeManager(dir.resolve("wt"), WorktreeFixtures.FakeWorktreeGit())
            val worktree = Files.createDirectories(worktrees.ensure(project, "c36", "Skill de refine").path)
            val tools = McpRequestTools(
                McpSession(project.repo, "refiner", "c36"),
                store,
                EventRecorder(store, events),
                worktrees,
                registry,
                clock,
            )
            val specPath = "docs/superpowers/specs/2026-10-16-skill-de-refine-via-mcp-design.md"
            val request = SessionRequest("refine", worktree, "model", dir.resolve("mcp.json"), "prompt")

            ScriptedRefineAgent(tools, template).run(FakeRuntime(listOf(SessionScript.Succeed)), request, "c36", specPath)

            val written = Files.readString(worktree.resolve(specPath))
            val result = check(written)
            assertThat(result.findings).isEmpty()
            assertThat(result.weight).isEqualTo(SpecWeight.LIGHT)
            assertThat(written.lines().filter { it.startsWith("## ") })
                .containsSubsequence("## Product", "## Architecture", "## Test plan", "## Security", "## Rollback")
        } finally {
            events.close()
            database.close()
        }
    }
}
