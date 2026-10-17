package shipfloor.daemon

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import shipfloor.core.AcceptancePatterns
import shipfloor.core.AcceptanceReason
import shipfloor.core.AcceptanceRules
import shipfloor.core.AcceptanceVerdict
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore

/** What to run: [command] is executed without a shell in [workingDir], killed after [timeout]. */
data class GateSpec(
    val name: String,
    val command: List<String>,
    val workingDir: Path,
    val timeout: Duration,
    val check: GateCheck = GateCheck.ExitCode,
)

/** How the verdict is derived once the process ends. */
sealed interface GateCheck {
    data object ExitCode : GateCheck

    data class Red(val patterns: AcceptancePatterns) : GateCheck

    data class Green(val patterns: AcceptancePatterns) : GateCheck

    /** The output is a git diff; the gate fails when it changes lines matching [keep]. */
    data class AssertionEdit(val keep: Regex) : GateCheck
}

/** Raw outcome of a gate process: no exit code and not timed out means it could not start. */
data class GateRun(val exitCode: Int?, val timedOut: Boolean)

sealed interface GateVerdict {
    data object Pass : GateVerdict

    data class Fail(val reason: String) : GateVerdict
}

/** Runs a gate, sending its combined output to [outputFile]. */
interface GateRunner {
    fun run(spec: GateSpec, outputFile: Path): GateRun
}

class GateCardNotFoundException(cardId: String) : RuntimeException("card not found: $cardId")

/** Runs a gate for a card and records the result as a GATE_FINISHED event with a bounded summary. */
class GateExecutor(
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val clock: Clock,
    private val runner: GateRunner,
    private val log: DaemonLog = StderrDaemonLog,
) {

    fun execute(cardId: String, spec: GateSpec, runDir: Path): GateVerdict {
        require(NAME_PATTERN.matches(spec.name)) { "invalid gate name: must match [a-z0-9][a-z0-9-]*" }
        val card = store.card(cardId) ?: throw GateCardNotFoundException(cardId)
        val base = runDir.toAbsolutePath().normalize()
        val outputFile = base.resolve("gate-${spec.name}.log").normalize()
        require(outputFile.startsWith(base)) { "invalid gate name: output file escapes the run directory" }
        Files.createDirectories(base)

        val startedAt = clock.instant()
        val run = runner.run(spec, outputFile)
        val finishedAt = clock.instant()
        if (!Files.exists(outputFile)) Files.createFile(outputFile)

        val (verdict, reason) = when {
            run.timedOut -> GateVerdict.Fail("timeout") to "timeout"
            run.exitCode == null -> GateVerdict.Fail("start_error") to "start_error"
            else -> when (val check = spec.check) {
                GateCheck.ExitCode ->
                    if (run.exitCode == 0) GateVerdict.Pass to null else GateVerdict.Fail("exit_code") to "exit_code"
                is GateCheck.Red -> acceptance(
                    outputFile,
                ) { AcceptanceRules.red(run.exitCode, it, check.patterns) }
                is GateCheck.Green -> acceptance(
                    outputFile,
                ) { AcceptanceRules.green(run.exitCode, it, check.patterns) }
                is GateCheck.AssertionEdit ->
                    if (run.exitCode != 0) {
                        GateVerdict.Fail("exit_code") to "exit_code"
                    } else if (readLines(outputFile) { AcceptanceRules.assertionEdit(it, check.keep) }.isEmpty()) {
                        GateVerdict.Pass to null
                    } else {
                        val name = AcceptanceReason.ASSERTION_EDITED.name.lowercase()
                        GateVerdict.Fail(name) to name
                    }
            }
        }
        val payload = buildMap {
            put("gate", spec.name)
            put("verdict", if (verdict is GateVerdict.Pass) "PASS" else "FAIL")
            if (reason != null) put("reason", reason)
            if (!run.timedOut && run.exitCode != null) put("exit_code", run.exitCode.toString())
            put("duration_ms", Duration.between(startedAt, finishedAt).toMillis().toString())
            put("summary", summary(outputFile))
            put("output_file", outputFile.toString())
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = finishedAt,
            actor = Actor.Factory("gate"),
            type = EventType.GATE_FINISHED,
            payload = payload,
        )
        recorder.record(cardId, card, event)
        log.log(
            "gate_finished",
            mapOf("card" to cardId, "gate" to spec.name, "verdict" to requireNotNull(payload["verdict"])),
        )
        return verdict
    }

    private fun acceptance(
        file: Path,
        rule: (Sequence<String>) -> AcceptanceVerdict,
    ): Pair<GateVerdict, String?> =
        when (val result = readLines(file, rule)) {
            AcceptanceVerdict.Ok -> GateVerdict.Pass to null
            is AcceptanceVerdict.Rejected -> {
                val name = result.reason.name.lowercase()
                GateVerdict.Fail(name) to name
            }
        }

    /** Streams the file line by line; malformed bytes are replaced instead of failing the read. */
    private fun <T> readLines(file: Path, block: (Sequence<String>) -> T): T =
        Files.newInputStream(file).use { input ->
            input.reader(StandardCharsets.UTF_8).buffered().use { block(it.lineSequence()) }
        }

    /** Last [SUMMARY_LINES] lines, at most [SUMMARY_CHARS] characters, reading only the end of the file. */
    private fun summary(file: Path): String {
        val tail = RandomAccessFile(file.toFile(), "r").use { raf ->
            val start = maxOf(0L, raf.length() - TAIL_BYTES)
            raf.seek(start)
            ByteArray((raf.length() - start).toInt()).also { raf.readFully(it) }
        }
        val lines = String(tail, StandardCharsets.UTF_8).trimEnd('\n', '\r').lines()
        return lines.takeLast(SUMMARY_LINES).joinToString("\n").takeLast(SUMMARY_CHARS)
    }

    private companion object {
        val NAME_PATTERN = Regex("[a-z0-9][a-z0-9-]*")
        const val SUMMARY_LINES = 20
        const val SUMMARY_CHARS = 2000
        const val TAIL_BYTES = 16L * 1024
    }
}
