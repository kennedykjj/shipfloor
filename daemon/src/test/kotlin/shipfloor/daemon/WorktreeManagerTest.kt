package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.ProjectPolicies

object WorktreeFixtures {
    fun aProject(name: String = "alpha", repo: String = "/repos/alpha") =
        Project(name, repo, listOf(ProjectLayer("daemon", "daemon/")), ProjectPolicies.DEFAULT)

    class FakeWorktreeGit : WorktreeGit {
        val worktrees = mutableSetOf<Path>()
        val branches = mutableMapOf<Path, String>()
        val dirty = mutableSetOf<Path>()
        val calls = mutableListOf<String>()

        override fun addWorktree(repo: Path, dir: Path, branch: String) {
            calls.add("add")
            worktrees.add(dir)
            branches[dir] = branch
        }

        override fun hasWorktree(repo: Path, dir: Path): Boolean {
            calls.add("has")
            return dir in worktrees
        }

        override fun isDirty(dir: Path): Boolean {
            calls.add("dirty")
            return dir in dirty
        }

        override fun removeWorktree(repo: Path, dir: Path) {
            calls.add("remove")
            worktrees.remove(dir)
        }

        override fun deleteBranch(repo: Path, branch: String) {
            calls.add("deleteBranch:$branch")
            branches.values.remove(branch)
        }
    }

    class RecordingLog : DaemonLog {
        val lines = mutableListOf<Pair<String, Map<String, String>>>()
        override fun log(event: String, fields: Map<String, String>) {
            lines.add(event to fields)
        }
    }
}

class WorktreeManagerTest {

    @TempDir
    lateinit var root: Path

    private val git = WorktreeFixtures.FakeWorktreeGit()
    private val log = WorktreeFixtures.RecordingLog()
    private val project = WorktreeFixtures.aProject()

    private fun manager() = WorktreeManager(root, git, log)

    @Test
    fun `AC-1 creates a worktree under root with a branch from the project convention`() {
        val worktree = manager().ensure(project, "c1", "Fix the Login Bug!")

        assertThat(worktree.cardId).isEqualTo("c1")
        assertThat(worktree.path).isEqualTo(root.resolve("alpha").resolve("c1"))
        assertThat(worktree.branch).isEqualTo("feat/c1-fix-the-login-bug")
        assertThat(git.worktrees).containsExactly(worktree.path)
        assertThat(git.branches[worktree.path]).isEqualTo(worktree.branch)
    }

    @Test
    fun `AC-2 the branch follows the configured branch_format with id and slug`() {
        val configured = project.copy(
            policies = ProjectPolicies.from(mapOf("conventions" to mapOf("branch_format" to "work/{id}/{slug}"))),
        )

        val worktree = manager().ensure(configured, "c1", "Fix the Login Bug!")

        assertThat(worktree.branch).isEqualTo("work/c1/fix-the-login-bug")
        assertThat(git.branches[worktree.path]).isEqualTo("work/c1/fix-the-login-bug")
    }

    @Test
    fun `AC-1 slug is limited to 40 safe characters`() {
        val worktree = manager().ensure(project, "c1", "Ação Única ".repeat(10))

        val slug = worktree.branch.removePrefix("feat/c1-")
        assertThat(slug).matches("[a-z0-9-]{1,40}")
    }

    @Test
    fun `AC-2 repeated ensure reuses the worktree without recreating it`() {
        val first = manager().ensure(project, "c1", "Some title")
        val second = manager().ensure(project, "c1", "Some title")

        assertThat(second).isEqualTo(first)
        assertThat(git.calls.count { it == "add" }).isEqualTo(1)
    }

    @Test
    fun `AC-2 an existing worktree is adopted and never added again`() {
        val dir = root.resolve("alpha").resolve("c1")
        git.worktrees.add(dir)

        val worktree = manager().ensure(project, "c1", "Some title")

        assertThat(worktree.path).isEqualTo(dir)
        assertThat(git.calls).doesNotContain("add")
    }

    @Test
    fun `AC-3 cleanup removes the worktree and the local branch`() {
        val worktree = manager().ensure(project, "c1", "Some title")

        val result = manager().cleanup(project, "c1", "Some title")

        assertThat(result).isEqualTo(CleanupResult.Removed)
        assertThat(git.worktrees).doesNotContain(worktree.path)
        assertThat(git.calls).contains("remove", "deleteBranch:${worktree.branch}")
    }

    @Test
    fun `AC-3 cleanup of a card without worktree has nothing to clean`() {
        val result = manager().cleanup(project, "ghost", "Some title")

        assertThat(result).isEqualTo(CleanupResult.NothingToClean)
        assertThat(git.calls).doesNotContain("remove")
    }

    @Test
    fun `AC-4 two cards of the same project get separate worktrees and branches`() {
        val one = manager().ensure(project, "c1", "Same title")
        val two = manager().ensure(project, "c2", "Same title")

        assertThat(one.path).isNotEqualTo(two.path)
        assertThat(one.branch).isNotEqualTo(two.branch)
        assertThat(git.worktrees).containsExactlyInAnyOrder(one.path, two.path)
    }

    @Test
    fun `AC-5 cleanup keeps a dirty worktree and its branch and reports it`() {
        val worktree = manager().ensure(project, "c1", "Some title")
        git.dirty.add(worktree.path)

        val result = manager().cleanup(project, "c1", "Some title")

        assertThat(result).isEqualTo(CleanupResult.KeptDirty(worktree.path))
        assertThat(git.worktrees).contains(worktree.path)
        assertThat(git.calls).doesNotContain("remove")
        assertThat(git.calls.none { it.startsWith("deleteBranch") }).isTrue()
    }

    @Test
    fun `AC-6 unsafe cardId is refused naming the field without touching git or disk`() {
        listOf("..", "../x", "a/b", "a\\b", "", ".hidden", "a..b/c").forEach { id ->
            val ensureError = assertThrows<WorktreeException> { manager().ensure(project, id, "t") }
            val cleanupError = assertThrows<WorktreeException> { manager().cleanup(project, id, "t") }
            assertThat(ensureError.message).contains("cardId")
            assertThat(cleanupError.message).contains("cardId")
        }
        assertThat(git.calls).isEmpty()
        assertThat(root.listDirectoryEntries()).isEmpty()
    }

    @Test
    fun `AC-6 unsafe project name is refused naming the field without touching git or disk`() {
        listOf("..", "../escape", "a/b", "a\\b", "", "-x").forEach { name ->
            val bad = WorktreeFixtures.aProject(name = name)
            val ensureError = assertThrows<WorktreeException> { manager().ensure(bad, "c1", "t") }
            val cleanupError = assertThrows<WorktreeException> { manager().cleanup(bad, "c1", "t") }
            assertThat(ensureError.message).contains("project.name")
            assertThat(cleanupError.message).contains("project.name")
        }
        assertThat(git.calls).isEmpty()
        assertThat(Files.list(root).use { it.count() }).isZero()
    }
}
