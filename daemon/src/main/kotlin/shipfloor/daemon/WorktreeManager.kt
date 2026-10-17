package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer

data class CardWorktree(val cardId: String, val path: Path, val branch: String)

sealed interface CleanupResult {
    data object Removed : CleanupResult
    data class KeptDirty(val path: Path) : CleanupResult
    data object NothingToClean : CleanupResult
}

class WorktreeException(message: String) : RuntimeException(message)

/** Owns the lifecycle of one worktree per card under `<root>/<project.name>/<cardId>`. */
class WorktreeManager(
    private val root: Path,
    private val git: WorktreeGit,
    private val log: DaemonLog = StderrDaemonLog,
) {
    /** Creates the card's worktree and branch, or returns the existing one untouched. */
    @Synchronized
    fun ensure(project: Project, cardId: String, title: String): CardWorktree {
        val dir = directoryFor(project, cardId)
        val branch = branchFor(project, cardId, title)
        val repo = Path.of(project.repo)
        if (git.hasWorktree(repo, dir)) {
            log.log("worktree_reused", fields(cardId, dir, branch))
        } else {
            git.addWorktree(repo, dir, branch)
            log.log("worktree_created", fields(cardId, dir, branch))
        }
        return CardWorktree(cardId, dir, branch)
    }

    /** The card's worktree directory when it already exists; never creates anything. */
    fun existing(project: Project, cardId: String): Path? =
        directoryFor(project, cardId).takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

    /** Removes the worktree and local branch; keeps both when the worktree has uncommitted changes. */
    @Synchronized
    fun cleanup(project: Project, cardId: String, title: String): CleanupResult {
        val dir = directoryFor(project, cardId)
        val branch = branchFor(project, cardId, title)
        val repo = Path.of(project.repo)
        if (!git.hasWorktree(repo, dir)) return CleanupResult.NothingToClean
        if (git.isDirty(dir)) {
            log.log("worktree_kept_dirty", fields(cardId, dir, branch))
            return CleanupResult.KeptDirty(dir)
        }
        git.removeWorktree(repo, dir)
        git.deleteBranch(repo, branch)
        log.log("worktree_removed", fields(cardId, dir, branch))
        return CleanupResult.Removed
    }

    private fun directoryFor(project: Project, cardId: String): Path {
        requireSafeSegment("project.name", project.name, cardId)
        requireSafeSegment("cardId", cardId, cardId)
        val base = root.toAbsolutePath().normalize()
        val dir = base.resolve(project.name).resolve(cardId).normalize()
        if (!dir.startsWith(base)) throw WorktreeException("cardId $cardId resolves outside the worktree root")
        return dir
    }

    private fun requireSafeSegment(field: String, value: String, cardId: String) {
        if (!SAFE_SEGMENT.matches(value) || value.contains("..")) {
            throw WorktreeException("$field is not a safe path segment (card $cardId)")
        }
    }

    private fun branchFor(project: Project, cardId: String, title: String): String =
        branchName(project.policies.conventions.branchFormat, cardId, title)

    private fun fields(cardId: String, dir: Path, branch: String) =
        mapOf("card" to cardId, "path" to dir.toString(), "branch" to branch)

    companion object {
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private const val MAX_SLUG = 40

        /** The card's branch name from a `branchFormat` with `{id}` and `{slug}` placeholders. */
        fun branchName(branchFormat: String, cardId: String, title: String): String =
            branchFormat.replace("{id}", cardId).replace("{slug}", slugOf(title))

        private fun slugOf(title: String): String {
            val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val slug = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_SLUG).trim('-')
            return slug.ifEmpty { "card" }
        }
    }
}
