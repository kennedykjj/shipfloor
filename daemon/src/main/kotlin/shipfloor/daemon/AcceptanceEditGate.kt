package shipfloor.daemon

import java.nio.file.Path
import java.time.Duration
import shipfloor.core.GateInputs

/** Fails when the implementation removed or replaced an assertion of the acceptance tests committed by QA. */
class AcceptanceEditGate(private val executor: GateExecutor, @Suppress("unused") private val runner: GateRunner) {

    fun check(
        cardId: String,
        worktree: Path,
        qaCommit: String,
        files: List<String>,
        keep: Regex,
        runDir: Path,
    ): GateVerdict {
        val commit = GateInputs.qaCommit(qaCommit)
        val validFiles = files.map(GateInputs::file)
        // The net change since the QA commit: an assertion removed there is gone, whatever commits came and went between.
        val command = listOf("git", "-C", worktree.toString(), "diff", commit, "HEAD", "--") + validFiles
        val spec = GateSpec(
            name = GATE_NAME,
            command = command,
            workingDir = worktree,
            timeout = TIMEOUT,
            check = GateCheck.AssertionEdit(keep),
        )
        return executor.execute(cardId, spec, runDir)
    }

    private companion object {
        const val GATE_NAME = "acceptance-edit"
        val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
