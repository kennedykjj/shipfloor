package shipfloor.adapters

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** [ProcessRunner] on top of [ProcessBuilder]: stdout to a file, stderr merged, kill of the whole tree on timeout. */
class ProcessRunnerImpl : ProcessRunner {
    override fun run(
        command: List<String>,
        workingDir: Path,
        stdoutFile: Path,
        timeout: Duration,
        env: Map<String, String>,
        envMode: EnvMode,
    ): ProcessOutcome {
        val builder = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(stdoutFile.toFile())
        if (envMode == EnvMode.FILTERED) {
            val kept = filterEnvironment(builder.environment())
            builder.environment().clear()
            builder.environment().putAll(kept)
        }
        builder.environment().putAll(env)
        val process = builder.start()
        if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return ProcessOutcome.Exited(process.exitValue())
        }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor()
        return ProcessOutcome.TimedOut
    }

    companion object {
        private val ALLOWED_NAMES = setOf("PATH", "HOME", "LANG")
        private val ALLOWED_PREFIXES = listOf("LC_", "ACLI_")
        private val SENSITIVE_PARTS = listOf("TOKEN", "SECRET", "PASSWORD", "KEY")

        /** Keeps only the allowed variables; any name containing a credential-like word is dropped. */
        fun filterEnvironment(source: Map<String, String>): Map<String, String> =
            source.filterKeys { name ->
                val upper = name.uppercase()
                val allowed = upper in ALLOWED_NAMES || ALLOWED_PREFIXES.any { upper.startsWith(it) }
                allowed && SENSITIVE_PARTS.none { upper.contains(it) }
            }
    }
}
