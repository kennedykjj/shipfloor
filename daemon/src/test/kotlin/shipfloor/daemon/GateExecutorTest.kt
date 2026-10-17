package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.AcceptancePatterns
import shipfloor.core.AcceptanceRules
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class GateExecutorTest {

    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-16T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var runDir: Path

    /** Fake runner: writes [output] to the output file and reports [run]; counts how often it ran. */
    private class FakeRunner(private val output: String, private val run: GateRun) : GateRunner {
        var calls = 0

        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            calls++
            Files.writeString(outputFile, output)
            return run
        }
    }

    /** Clock that only moves when told to. */
    private class SteppedClock(private var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    /** Fake runner whose process "takes" [takes] on the stepped clock, however often the executor reads it. */
    private class TimedRunner(
        private val clock: SteppedClock,
        private val takes: Duration,
        private val run: GateRun,
    ) : GateRunner {
        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            clock.advance(takes)
            Files.writeString(outputFile, "output\n")
            return run
        }
    }

    @BeforeEach
    fun openStore() {
        database = Database.open(dir.resolve("cards.db"))
        store = CardEventStore(database)
        store.recordChange(
            "card-1",
            Card.create("Executor de gates", Column.INBOX),
            Event(EventId("evt-0"), now, Actor.Owner, EventType.CARD_CREATED, mapOf("card" to "card-1")),
        )
        runDir = dir.resolve("runs").resolve("run-1")
    }

    @AfterEach
    fun closeStore() {
        database.close()
    }

    private fun spec(name: String = "lint") = GateSpec(
        name = name,
        command = listOf("./gate.sh"),
        workingDir = dir,
        timeout = Duration.ofSeconds(30),
    )

    private fun executor(runner: GateRunner) = GateExecutor(store, EventRecorder(store, EventStream(store)), clock, runner)

    private fun executor(runner: GateRunner, onClock: Clock) =
        GateExecutor(store, EventRecorder(store, EventStream(store)), onClock, runner)

    private fun gateEvents(): List<Event> = store.events("card-1").filter { it.type.name == "GATE_FINISHED" }

    @Test
    fun `AC-1 a passing gate is recorded as a factory gate event with verdict and summary`() {
        val runner = FakeRunner("all good\n", GateRun(exitCode = 0, timedOut = false))

        val verdict = executor(runner).execute("card-1", spec(), runDir)

        assertThat(verdict).isEqualTo(GateVerdict.Pass)
        val event = gateEvents().single()
        assertThat(event.actor).isEqualTo(Actor.Factory("gate"))
        assertThat(event.at).isEqualTo(now)
        assertThat(event.payload["gate"]).isEqualTo("lint")
        assertThat(event.payload["verdict"]).isEqualTo("PASS")
        assertThat(event.payload["exit_code"]).isEqualTo("0")
        assertThat(event.payload["summary"]).contains("all good")
    }

    @Test
    fun `AC-1 a failing gate is recorded with verdict FAIL, exit code and the tail of its output`() {
        val runner = FakeRunner("compiling\nerror: boom\n", GateRun(exitCode = 3, timedOut = false))

        val verdict = executor(runner).execute("card-1", spec(), runDir)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        val event = gateEvents().single()
        assertThat(event.payload["verdict"]).isEqualTo("FAIL")
        assertThat(event.payload["reason"]).isEqualTo("exit_code")
        assertThat(event.payload["exit_code"]).isEqualTo("3")
        assertThat(event.payload["summary"]).contains("error: boom")
    }

    @Test
    fun `AC-2 a gate that exceeds the time limit fails with reason timeout and no exit code`() {
        val runner = FakeRunner("still working\n", GateRun(exitCode = null, timedOut = true))

        val verdict = executor(runner).execute("card-1", spec(), runDir)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        val event = gateEvents().single()
        assertThat(event.payload["verdict"]).isEqualTo("FAIL")
        assertThat(event.payload["reason"]).isEqualTo("timeout")
        assertThat(event.payload).doesNotContainKey("exit_code")
    }

    @Test
    fun `AC-3 long output stays in the run file and the event keeps only a bounded summary`() {
        val lines = (1..200).joinToString("\n") { "line-$it" } + "\n"
        val runner = FakeRunner(lines, GateRun(exitCode = 1, timedOut = false))

        executor(runner).execute("card-1", spec(), runDir)

        val event = gateEvents().single()
        val outputFile = Path.of(requireNotNull(event.payload["output_file"]) { "output_file missing" })
        assertThat(outputFile.startsWith(runDir)).isTrue()
        assertThat(Files.readString(outputFile)).isEqualTo(lines)
        val summary = requireNotNull(event.payload["summary"]) { "summary missing" }
        assertThat(summary.lines().filter { it.isNotBlank() }).hasSize(20)
        assertThat(summary).contains("line-200").contains("line-181").doesNotContain("line-180\n")
        assertThat(summary).doesNotContain("line-1\n")
        assertThat(event.payload.values.sumOf { it.length }).isLessThan(lines.length)
    }

    @Test
    fun `AC-3 a single huge line is cut to at most 2000 characters in the summary`() {
        val runner = FakeRunner("x".repeat(100_000) + "\n", GateRun(exitCode = 1, timedOut = false))

        executor(runner).execute("card-1", spec(), runDir)

        val event = gateEvents().single()
        assertThat(requireNotNull(event.payload["summary"]).length).isLessThanOrEqualTo(2000)
        assertThat(Files.size(Path.of(requireNotNull(event.payload["output_file"])))).isGreaterThan(100_000L)
    }

    @Test
    fun `AC-4 an invalid gate name is refused naming the field, with no process and no file`() {
        listOf("", "..", "a/b", "../x", "-lint", "Lint", "a b").forEach { bad ->
            val runner = FakeRunner("never\n", GateRun(exitCode = 0, timedOut = false))

            val error = assertThrows<RuntimeException> { executor(runner).execute("card-1", spec(bad), runDir) }

            assertThat(error).hasMessageContaining("name")
            assertThat(runner.calls).isZero()
            assertThat(runDir).doesNotExist()
            assertThat(gateEvents()).isEmpty()
        }
    }

    private val patterns = AcceptancePatterns(
        ran = Regex("tests completed"),
        assertions = Regex("ASSERT_FAIL"),
        setupErrors = Regex("SETUP_BROKEN"),
    )

    private fun redSpec() = spec("red-core").copy(check = GateCheck.Red(patterns))

    private fun greenSpec() = spec("green-core").copy(check = GateCheck.Green(patterns))

    private fun runRed(output: String, exitCode: Int): Pair<GateVerdict, Event> {
        val verdict = executor(FakeRunner(output, GateRun(exitCode, false))).execute("card-1", redSpec(), runDir)
        return verdict to gateEvents().single()
    }

    private fun runGreen(output: String, exitCode: Int): Pair<GateVerdict, Event> {
        val verdict = executor(FakeRunner(output, GateRun(exitCode, false))).execute("card-1", greenSpec(), runDir)
        return verdict to gateEvents().single()
    }

    @Test
    fun `AC-1 red gate passes only when the test failed by assertion`() {
        val (verdict, event) = runRed("1 tests completed, 1 failed\nASSERT_FAIL expected 1 but was 2\n", 1)

        assertThat(verdict).isEqualTo(GateVerdict.Pass)
        assertThat(event.payload["verdict"]).isEqualTo("PASS")
    }

    @Test
    fun `AC-1 red gate fails with tests_passed_before_impl when the test passed`() {
        val passed = runRed("1 tests completed\n", 0)
        assertThat(passed.first).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(passed.second.payload["reason"]).isEqualTo("tests_passed_before_impl")
        assertThat(passed.second.payload["verdict"]).isEqualTo("FAIL")
    }

    @Test
    fun `AC-1 red gate fails with setup_error when the output shows a setup error`() {
        val setup = runRed("SETUP_BROKEN cannot compile\n", 1)
        assertThat(setup.first).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(setup.second.payload["reason"]).isEqualTo("setup_error")
    }

    @Test
    fun `AC-1 red gate fails with no_assertion_failure when the output has no assertion line`() {
        val (verdict, event) = runRed("1 tests completed, 1 failed\nsomething exploded\n", 1)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(event.payload["reason"]).isEqualTo("no_assertion_failure")
    }

    @Test
    fun `AC-2 green gate passes when the test passed and the output matches the ran pattern`() {
        val (verdict, event) = runGreen("3 tests completed\n", 0)

        assertThat(verdict).isEqualTo(GateVerdict.Pass)
        assertThat(event.payload["verdict"]).isEqualTo("PASS")
    }

    @Test
    fun `AC-2 green gate fails with tests_failed when the test fails`() {
        val (verdict, event) = runGreen("3 tests completed, 1 failed\nASSERT_FAIL\n", 1)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(event.payload["reason"]).isEqualTo("tests_failed")
    }

    @Test
    fun `AC-2 green gate fails with no_test_ran when it passes without matching the ran pattern`() {
        val (verdict, event) = runGreen("BUILD SUCCESSFUL, nothing selected\n", 0)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(event.payload["reason"]).isEqualTo("no_test_ran")
    }

    @Test
    fun `AC-2 timeout takes precedence over the acceptance check`() {
        val runner = FakeRunner("3 tests completed\n", GateRun(exitCode = null, timedOut = true))

        val verdict = executor(runner).execute("card-1", greenSpec(), runDir)

        assertThat(verdict).isInstanceOf(GateVerdict.Fail::class.java)
        assertThat(gateEvents().single().payload["reason"]).isEqualTo("timeout")
    }

    /** Records the command it was asked to run and writes a fixed diff as output. */
    private class RecordingRunner : GateRunner {
        var calls = 0
        var command: List<String> = emptyList()

        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            calls++
            command = spec.command
            Files.writeString(outputFile, "")
            return GateRun(0, false)
        }
    }

    private val keep = Regex("assertThat")

    @Test
    fun `AC-4 acceptance edit gate refuses an invalid qaCommit naming the field, with no process`() {
        listOf("", "abc12", "--output=x", "ABCDEF1", "g123456", "a".repeat(41), "abc1234; rm").forEach { bad ->
            val runner = RecordingRunner()
            val gate = AcceptanceEditGate(executor(runner), runner)

            val error = assertThrows<RuntimeException> {
                gate.check("card-1", dir, bad, listOf("a/FooTest.kt"), keep, runDir)
            }

            assertThat(error).hasMessageContaining("qaCommit")
            assertThat(runner.calls).isZero()
            assertThat(gateEvents()).isEmpty()
        }
    }

    @Test
    fun `AC-4 acceptance edit gate refuses an invalid file naming the field, with no process`() {
        listOf("-x", "--output=x", "../x", "a/../b", "a\u0000b", "a\nb").forEach { bad ->
            val runner = RecordingRunner()
            val gate = AcceptanceEditGate(executor(runner), runner)

            val error = assertThrows<RuntimeException> {
                gate.check("card-1", dir, "abc1234", listOf("ok/FooTest.kt", bad), keep, runDir)
            }

            assertThat(error).hasMessageContaining("files")
            assertThat(runner.calls).isZero()
            assertThat(gateEvents()).isEmpty()
        }
    }

    @Test
    fun `AC-4 acceptance edit gate puts the files after a double dash and runs without a shell`() {
        val runner = RecordingRunner()

        val verdict = AcceptanceEditGate(executor(runner), runner)
            .check("card-1", dir, "abc1234", listOf("a/FooTest.kt"), keep, runDir)

        assertThat(verdict).isEqualTo(GateVerdict.Pass)
        assertThat(runner.command.takeLast(2)).containsExactly("--", "a/FooTest.kt")
        assertThat(runner.command).contains("abc1234", "git")
        assertThat(gateEvents().single().payload["gate"]).isEqualTo("acceptance-edit")
    }

    @Test
    fun `AC-1 a finished gate records duration_ms measured by the injected clock around the process`() {
        val stepped = SteppedClock(now)
        val runner = TimedRunner(stepped, Duration.ofMillis(850), GateRun(exitCode = 0, timedOut = false))

        executor(runner, stepped).execute("card-1", spec(), runDir)

        assertThat(gateEvents().single().payload["duration_ms"]).isEqualTo("850")
    }

    @Test
    fun `AC-1 a failing gate records the duration of its run too`() {
        val stepped = SteppedClock(now)
        val runner = TimedRunner(stepped, Duration.ofSeconds(12), GateRun(exitCode = 3, timedOut = false))

        executor(runner, stepped).execute("card-1", spec(), runDir)

        val payload = gateEvents().single().payload
        assertThat(payload["verdict"]).isEqualTo("FAIL")
        assertThat(payload["duration_ms"]).isEqualTo("12000")
    }

    @Test
    fun `AC-1 a gate that times out records the time it ran`() {
        val stepped = SteppedClock(now)
        val runner = TimedRunner(stepped, Duration.ofSeconds(30), GateRun(exitCode = null, timedOut = true))

        executor(runner, stepped).execute("card-1", spec(), runDir)

        val payload = gateEvents().single().payload
        assertThat(payload["reason"]).isEqualTo("timeout")
        assertThat(payload["duration_ms"]).isEqualTo("30000")
    }

    @Test
    fun `AC-1 a gate that could not start records its duration`() {
        val stepped = SteppedClock(now)
        val runner = TimedRunner(stepped, Duration.ofMillis(40), GateRun(exitCode = null, timedOut = false))

        executor(runner, stepped).execute("card-1", spec(), runDir)

        val payload = gateEvents().single().payload
        assertThat(payload["reason"]).isEqualTo("start_error")
        assertThat(payload["duration_ms"]).isEqualTo("40")
    }

    @Test
    fun `AC-4 acceptance edit ignores assertions that a merge of the base added to the test files`() {
        val repo = Files.createDirectory(dir.resolve("repo"))
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repo.toString(), "-c", "user.name=t", "-c", "user.email=t@t") + args)
                .redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { "git ${args.toList()}: $out" }
            return out
        }
        git("init", "-q", "-b", "main")
        Files.createDirectories(repo.resolve("a"))
        Files.writeString(repo.resolve("a/FooTest.kt"), "assertThat(1)\n")
        git("add", ".")
        git("commit", "-q", "-m", "qa")
        val qa = git("rev-parse", "HEAD").trim()
        git("switch", "-q", "-c", "feat")
        Files.writeString(repo.resolve("b.txt"), "impl")
        git("add", ".")
        git("commit", "-q", "-m", "impl")
        git("switch", "-q", "main")
        Files.writeString(repo.resolve("a/FooTest.kt"), "assertThat(1)\nassertThat(2)\n")
        git("commit", "-q", "-am", "main adds a test assertion")
        git("switch", "-q", "feat")
        git("merge", "-q", "--no-edit", "main")
        val runner = RecordingRunner()
        AcceptanceEditGate(executor(runner), runner).check("card-1", repo, qa, listOf("a/FooTest.kt"), keep, runDir)

        val process = ProcessBuilder(runner.command).redirectErrorStream(true).start()
        val patch = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()

        assertThat(AcceptanceRules.assertionEdit(patch.lineSequence(), keep)).isEmpty()
    }
}
