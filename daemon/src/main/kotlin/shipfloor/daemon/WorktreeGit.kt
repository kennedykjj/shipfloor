package shipfloor.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The git operations the worktree manager needs; the fake in the tests stands in for a real repository. */
interface WorktreeGit {
    /** Adds a worktree at [dir] on [branch], reusing the branch from `origin` when it exists, else from `origin/main`. */
    fun addWorktree(repo: Path, dir: Path, branch: String)

    fun hasWorktree(repo: Path, dir: Path): Boolean

    fun isDirty(dir: Path): Boolean

    /** Removes the registered worktree at [dir]; refuses a directory git does not know and never forces. */
    fun removeWorktree(repo: Path, dir: Path)

    fun deleteBranch(repo: Path, branch: String)
}

/** Runs `git -C` without a shell, with a timeout and without any prompt. */
class SystemWorktreeGit(private val timeoutSeconds: Long = TIMEOUT_SECONDS) : WorktreeGit {

    override fun addWorktree(repo: Path, dir: Path, branch: String) {
        val args = when {
            succeeds(repo, "rev-parse", "--verify", "--quiet", "refs/heads/$branch") ->
                listOf("worktree", "add", dir.toString(), branch)
            succeeds(repo, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/$branch") ->
                listOf("worktree", "add", "-b", branch, dir.toString(), "origin/$branch")
            else -> listOf("worktree", "add", "-b", branch, dir.toString(), "origin/main")
        }
        run(repo, *args.toTypedArray())
    }

    override fun hasWorktree(repo: Path, dir: Path): Boolean {
        val listed = run(repo, "worktree", "list", "--porcelain").lines()
        val wanted = dir.toAbsolutePath().normalize()
        return listed.any { line ->
            line.startsWith("worktree ") && Path.of(line.removePrefix("worktree ")).normalize() == wanted
        }
    }

    override fun isDirty(dir: Path): Boolean = run(dir, "status", "--porcelain").isNotBlank()

    override fun removeWorktree(repo: Path, dir: Path) {
        run(repo, "worktree", "remove", dir.toString())
    }

    override fun deleteBranch(repo: Path, branch: String) {
        run(repo, "branch", "-D", branch)
    }

    private fun succeeds(repo: Path, vararg args: String): Boolean = exec(repo, *args).first == 0

    private fun run(repo: Path, vararg args: String): String {
        val (code, output) = exec(repo, *args)
        if (code != 0) throw WorktreeException("git ${args.first()} failed with exit code $code")
        return output
    }

    private fun exec(repo: Path, vararg args: String): Pair<Int, String> {
        // Output goes to a file so the wait bounds the whole run: a pipe read would block until EOF.
        val output = Files.createTempFile("shipfloor-worktree", ".txt")
        try {
            val process = try {
                ProcessBuilder(listOf("git", "-C", repo.toString(), "-c", "core.fsmonitor=false") + args)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                    .start()
            } catch (e: IOException) {
                throw WorktreeException("cannot run git ${args.first()}")
            }
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw WorktreeException("git ${args.first()} timed out")
                }
            } finally {
                process.destroyForcibly()
            }
            return process.exitValue() to Files.readAllBytes(output).toString(Charsets.UTF_8)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}

sealed interface MergeResult {
    data object Merged : MergeResult

    data object Conflict : MergeResult

    /** Any git failure that is not a conflict: no network or credential, no identity, non-zero exit. */
    data object Failed : MergeResult
}

/** The result of a merge of `origin/main` that leaves its conflicts open in the worktree for an agent to resolve. */
sealed interface ConflictAttempt {
    data object Merged : ConflictAttempt

    /** [files] conflicting paths and [hunks] conflict markers; a size the daemon would not read counts as more than any limit. */
    data class Conflicted(val files: Int, val hunks: Int) : ConflictAttempt

    data object Failed : ConflictAttempt
}

/** Brings the card's branch up to date with `origin/main`. */
fun interface BranchSync {
    fun mergeMain(worktree: Path): MergeResult

    /** Like [mergeMain] but a conflict stays open in the worktree, measured. */
    fun mergeMainKeepingConflicts(worktree: Path): ConflictAttempt = ConflictAttempt.Failed

    /** Drops an open merge, leaving the branch as it was. */
    fun abortMerge(worktree: Path) = Unit

    /** Commits the open merge; false (merge left open) when a conflict marker or an unresolved path remains. */
    fun concludeMerge(worktree: Path): Boolean = false
}

/**
 * `git fetch` and `git merge` without a shell. Hooks and fsmonitor are switched off and the commit identity is fixed,
 * so nothing from the repository's own config runs inside the daemon process.
 */
class SystemBranchSync(private val timeoutSeconds: Long = TIMEOUT_SECONDS) : BranchSync {

    override fun mergeMain(worktree: Path): MergeResult {
        if (git(worktree, "fetch", "origin") != 0) return MergeResult.Failed
        return when (git(worktree, "merge", "--no-edit", "origin/main")) {
            0 -> MergeResult.Merged
            else -> if (hasUnmergedPaths(worktree)) abortAsConflict(worktree) else MergeResult.Failed
        }
    }

    override fun mergeMainKeepingConflicts(worktree: Path): ConflictAttempt {
        if (git(worktree, "fetch", "origin") != 0) return ConflictAttempt.Failed
        return when (git(worktree, "merge", "--no-edit", "origin/main")) {
            0 -> ConflictAttempt.Merged
            else -> if (hasUnmergedPaths(worktree)) measure(worktree) else ConflictAttempt.Failed
        }
    }

    override fun abortMerge(worktree: Path) {
        git(worktree, "merge", "--abort")
    }

    override fun concludeMerge(worktree: Path): Boolean {
        val conflicted = unmergedFiles(worktree) ?: return false
        if (conflicted.any { hunksIn(worktree, it) != 0 }) return false
        if (conflicted.isNotEmpty() && git(worktree, "add", "--", *conflicted.toTypedArray()) != 0) return false
        if (unmergedFiles(worktree)?.isEmpty() != true) return false
        return git(worktree, "commit", "--no-edit") == 0
    }

    private fun measure(worktree: Path): ConflictAttempt {
        val files = unmergedFiles(worktree) ?: return ConflictAttempt.Failed
        if (files.size > MAX_FILES_READ) return ConflictAttempt.Conflicted(files.size, Int.MAX_VALUE)
        var hunks = 0L
        for (file in files) {
            hunks += hunksIn(worktree, file)
            if (hunks >= Int.MAX_VALUE) return ConflictAttempt.Conflicted(files.size, Int.MAX_VALUE)
        }
        return ConflictAttempt.Conflicted(files.size, hunks.toInt())
    }

    private fun unmergedFiles(worktree: Path): List<String>? {
        val (code, output) = capture(worktree, "diff", "--name-only", "--diff-filter=U")
        if (code != 0) return null
        return output.lines().filter { it.isNotBlank() }
    }

    /** Conflict markers in [file]; a file the daemon will not read whole counts as [Int.MAX_VALUE]. */
    private fun hunksIn(worktree: Path, file: String): Int {
        val root = worktree.toAbsolutePath().normalize()
        val path = root.resolve(file).normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return 0
        return try {
            if (Files.size(path) > MAX_FILE_BYTES) return Int.MAX_VALUE
            Files.readAllLines(path, Charsets.ISO_8859_1).count { it.startsWith(MARKER) }
        } catch (e: IOException) {
            Int.MAX_VALUE
        }
    }

    private fun abortAsConflict(worktree: Path): MergeResult {
        git(worktree, "merge", "--abort")
        return MergeResult.Conflict
    }

    private fun hasUnmergedPaths(worktree: Path): Boolean = git(worktree, "rev-parse", "-q", "--verify", "MERGE_HEAD") == 0

    /** Exit code of git, or -1 when it could not start or timed out. */
    private fun git(worktree: Path, vararg args: String): Int = capture(worktree, *args).first

    /** Exit code (-1 when git could not start or timed out) and stdout; stdout goes to a file so the wait bounds the run. */
    private fun capture(worktree: Path, vararg args: String): Pair<Int, String> {
        val command = listOf(
            "git", "-C", worktree.toString(),
            "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
            "-c", "user.name=shipfloor factory", "-c", "user.email=factory@shipfloor.invalid",
        ) + args
        val output = Files.createTempFile("shipfloor-sync", ".txt")
        try {
            val process = try {
                ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                    .start()
            } catch (e: IOException) {
                return -1 to ""
            }
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return -1 to ""
                return process.exitValue() to Files.readAllBytes(output).toString(Charsets.UTF_8)
            } finally {
                process.destroyForcibly()
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
        const val MAX_FILES_READ = 100
        const val MAX_FILE_BYTES = 1L shl 20
        const val MARKER = "<<<<<<<"
    }
}

/**
 * Pushes only `refs/heads/<branch>:refs/heads/<branch>` of the card's own branch: no `--force`, no tags, no `--all`, and
 * never the base. Hooks and fsmonitor are switched off so nothing from the repository's config runs in the daemon.
 */
class SystemBranchPublisher(
    private val base: String = "main",
    private val timeoutSeconds: Long = PUSH_TIMEOUT_SECONDS,
) : BranchPublisher {

    override fun push(worktree: Path, branch: String): PushResult {
        if (!acceptable(branch)) return PushResult.Refused
        val refspec = "refs/heads/$branch:refs/heads/$branch"
        return if (git(worktree, "push", "origin", refspec) == 0) PushResult.Pushed else PushResult.Failed
    }

    private fun acceptable(branch: String): Boolean =
        branch.isNotEmpty() && branch != base && !branch.startsWith("-") && !branch.contains("..") &&
            branch.none { it.isWhitespace() || it.isISOControl() || it in FORBIDDEN_CHARS } &&
            !branch.startsWith("/") && !branch.endsWith("/") && !branch.endsWith(".lock") && !branch.contains("@{")

    private fun git(worktree: Path, vararg args: String): Int {
        val process = try {
            ProcessBuilder(listOf("git", "-C", worktree.toString(), "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false") + args)
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
        const val PUSH_TIMEOUT_SECONDS = 120L
        val FORBIDDEN_CHARS = setOf('~', '^', ':', '?', '*', '[', '\\')
    }
}
