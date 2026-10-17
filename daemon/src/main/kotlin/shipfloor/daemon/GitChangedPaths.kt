package shipfloor.daemon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The worktree's changed files against [base] could not be listed. */
class ChangedPathsException(message: String) : RuntimeException(message)

interface ChangedPaths {
    fun between(worktree: Path, base: String): List<String>

    /** Files the worktree holds that git does not track yet (and does not ignore). */
    fun untracked(worktree: Path): List<String> = emptyList()
}

/**
 * Lists the paths changed since the branch left [base] with `git diff --name-only --no-renames`, so a rename yields
 * origin and destination.
 */
class GitChangedPaths(private val timeoutSeconds: Long = 30) : ChangedPaths {

    override fun between(worktree: Path, base: String): List<String> {
        if (base.isBlank() || base.startsWith("-")) {
            throw ChangedPathsException("invalid base revision: $base")
        }
        // Against the fork point, not the tip of [base]: a base that moved on after the branch started must not
        // show as changes of the card.
        val forkPoint = list(worktree, "merge-base", base, "HEAD").singleOrNull()?.trim()
            ?: throw ChangedPathsException("no common ancestor between $base and HEAD in $worktree")
        return list(worktree, "diff", "--name-only", "--no-renames", "-z", forkPoint, "--")
    }

    override fun untracked(worktree: Path): List<String> =
        list(worktree, "ls-files", "--others", "--exclude-standard", "-z")

    private fun list(worktree: Path, vararg args: String): List<String> {
        val output = Files.createTempFile("changed-paths", ".out")
        try {
            val process = ProcessBuilder(listOf("git", "-C", worktree.toString()) + args)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw ChangedPathsException("git diff timed out after ${timeoutSeconds}s in $worktree")
            }
            if (process.exitValue() != 0) {
                throw ChangedPathsException("git diff failed with exit ${process.exitValue()} in $worktree")
            }
            if (Files.size(output) > MAX_BYTES) {
                throw ChangedPathsException("git diff output exceeds $MAX_BYTES bytes in $worktree")
            }
            return String(Files.readAllBytes(output), StandardCharsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024L
    }
}
