package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class DevelopGatesTest {

    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-16T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var worktree: Path
    private val specs = mutableListOf<GateSpec>()

    private val runner = object : GateRunner {
        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            specs += spec
            Files.writeString(outputFile, "Test PASSED\n")
            return GateRun(exitCode = 0, timedOut = false)
        }
    }

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("cards.db"))
        store = CardEventStore(database)
        store.recordChange(
            "c1",
            Card.create("Develop", Column.CODING),
            Event(EventId("seed"), now, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Develop")),
        )
        worktree = Files.createDirectories(dir.resolve("wt"))
    }

    @AfterEach
    fun close() {
        database.close()
    }

    private fun writeSpec(vararg rows: String) {
        val specDir = Files.createDirectories(worktree.resolve("docs/superpowers/specs"))
        val table = rows.joinToString("\n") { "| AC-1 | daemon | `$it` |" }
        Files.writeString(specDir.resolve("spec.md"), "## Test plan\n\n| AC | Layer | Test file |\n|---|---|---|\n$table\n\n## Security\n")
    }

    private fun gates(layer: ProjectLayer): DevelopGates {
        val project = Project("alpha", "/repos/alpha", listOf(layer), ProjectPolicies.DEFAULT)
        val recorder = EventRecorder(store, EventStream(store))
        val paths = object : ChangedPaths {
            override fun between(worktree: Path, base: String): List<String> = emptyList()
        }
        return RealDevelopGates(
            project, store, recorder, GateExecutor(store, recorder, clock, runner), runner, paths, dir.resolve("runs"), clock,
            head = WorktreeHead { "abcdef1" },
        )
    }

    private val tokens = listOf("../gradlew", ":daemon:test", "--tests", "{selector}", "--rerun")

    @Test
    fun `green repeats the flag and the selector for each test class of the layer`() {
        writeSpec("daemon/src/test/kotlin/shipfloor/daemon/FirstTest.kt", "daemon/src/test/kotlin/shipfloor/daemon/SecondTest.kt")
        val layer = ProjectLayer("daemon", "daemon/", test = tokens, ran = "PASSED")
        val gates = gates(layer)

        gates.red("c1", worktree)
        val results = gates.green("c1", layer, worktree)

        val test = specs.first { it.name == "daemon-tests" }
        assertThat(test.command).containsExactly(
            "../gradlew", ":daemon:test", "--tests", "FirstTest", "--tests", "SecondTest", "--rerun",
        )
        assertThat(test.workingDir).isEqualTo(worktree.resolve("daemon/"))
        assertThat(results.map { it.name }).contains("daemon-tests", "scope")
    }

    @Test
    fun `the plan comes from the spec the card itself changed, not from the newest spec file`() {
        val specDir = Files.createDirectories(worktree.resolve("docs/superpowers/specs"))
        val row = { layer: String, file: String -> "## Test plan\n\n| AC | Layer | Test file |\n|---|---|---|\n| AC-1 | $layer | `$file` |\n\n## Security\n" }
        Files.writeString(specDir.resolve("own.md"), row("daemon", "daemon/src/test/kotlin/shipfloor/daemon/OwnTest.kt"))
        Files.writeString(specDir.resolve("other.md"), row("cli", "cli/src/test/kotlin/shipfloor/cli/OtherTest.kt"))
        Files.setLastModifiedTime(specDir.resolve("other.md"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
        val project = Project("alpha", "/repos/alpha", listOf(ProjectLayer("daemon", "daemon/", test = tokens), ProjectLayer("cli", "cli/", test = tokens)), ProjectPolicies.DEFAULT)
        val recorder = EventRecorder(store, EventStream(store))
        val paths = object : ChangedPaths {
            override fun between(worktree: Path, base: String): List<String> = listOf("docs/superpowers/specs/own.md")
        }
        val gates = RealDevelopGates(
            project, store, recorder, GateExecutor(store, recorder, clock, runner), runner, paths, dir.resolve("runs"), clock,
            head = WorktreeHead { "abcdef1" },
        )

        val planned = gates.layers(worktree)

        assertThat(planned.map { it.name }).containsExactly("daemon")
    }

    @Test
    fun `a layer without test command fails its gate with no_test_command`() {
        writeSpec("daemon/src/test/kotlin/shipfloor/daemon/FirstTest.kt")
        val layer = ProjectLayer("daemon", "daemon/")

        val results = gates(layer).red("c1", worktree)

        assertThat(results).containsExactly(GateResult("daemon-red", false, "no_test_command"))
        assertThat(specs).isEmpty()
    }

    @Test
    fun `a test class name outside the allowed characters fails with invalid_selector and runs nothing`() {
        writeSpec("daemon/src/test/kotlin/shipfloor/daemon/Bad-Name.kt")
        val layer = ProjectLayer("daemon", "daemon/", test = tokens)

        val results = gates(layer).red("c1", worktree)

        assertThat(results).containsExactly(GateResult("daemon-red", false, "invalid_selector"))
        assertThat(specs).isEmpty()
    }

    @Test
    fun `selector substitution keeps every token as one argument and handles a single class`() {
        val command = RealDevelopGates.substituteSelector(tokens, listOf("OnlyTest"))

        assertThat(command).containsExactly("../gradlew", ":daemon:test", "--tests", "OnlyTest", "--rerun")
    }
}
