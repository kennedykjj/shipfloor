package shipfloor.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Commits what an agent session wrote into the card's worktree; the agents have no shell, so the daemon does it. */
fun interface WorktreeCommit {
    /** True when the worktree has nothing left to commit; false when git failed. */
    fun commitAll(worktree: Path, message: String): Boolean

    companion object {
        val None = WorktreeCommit { _, _ -> true }
    }
}

/**
 * `git add -A` and `git commit` without a shell. Hooks and fsmonitor are switched off and the commit identity is fixed,
 * so nothing from the repository's own config runs inside the daemon process.
 */
class SystemWorktreeCommit(private val timeoutSeconds: Long = TIMEOUT_SECONDS) : WorktreeCommit {

    override fun commitAll(worktree: Path, message: String): Boolean {
        if (git(worktree, "add", "-A") != 0) return false
        if (git(worktree, "diff", "--cached", "--quiet") == 0) return true
        return git(worktree, "commit", "-q", "-m", message) == 0
    }

    /** Exit code of git, or -1 when it could not start or timed out. */
    private fun git(worktree: Path, vararg args: String): Int {
        val command = listOf(
            "git", "-C", worktree.toString(),
            "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
            "-c", "user.name=shipfloor factory", "-c", "user.email=factory@shipfloor.invalid",
        ) + args
        val process = try {
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (e: IOException) {
            return -1
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return -1
            return process.exitValue()
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
