package shipfloor.daemon

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val MINIMAL_ENVIRONMENT_NAMES = setOf("PATH", "HOME", "JAVA_HOME", "LANG")
private val MINIMAL_ENVIRONMENT_PREFIXES = listOf("LC_", "GRADLE_")

/** The variables a gate child may inherit; everything else (tokens, API keys) stays in the daemon. */
fun minimalEnvironment(source: Map<String, String>): Map<String, String> =
    source.filterKeys { name -> name in MINIMAL_ENVIRONMENT_NAMES || MINIMAL_ENVIRONMENT_PREFIXES.any { name.startsWith(it) } }

/** Runs a gate as a child process without a shell; stdout and stderr go straight to the output file. */
class ProcessGateRunner(private val log: DaemonLog = StderrDaemonLog) : GateRunner {

    override fun run(spec: GateSpec, outputFile: Path): GateRun {
        val process = try {
            val builder = ProcessBuilder(spec.command)
                .directory(spec.workingDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
            builder.environment().apply {
                clear()
                putAll(minimalEnvironment(System.getenv()))
            }
            builder.start()
        } catch (e: IOException) {
            log.log("gate_start_error", mapOf("gate" to spec.name))
            return GateRun(exitCode = null, timedOut = false)
        }
        if (process.waitFor(spec.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return GateRun(exitCode = process.exitValue(), timedOut = false)
        }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor()
        return GateRun(exitCode = null, timedOut = true)
    }
}
