package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

object ScopeFixtures {
    /** Registers a project the way the daemon does: from factory.yml text (the `main` version). */
    fun aProject(forbidden: List<String> = emptyList()): Project {
        val repo = Files.createTempDirectory("scope-fixture")
        Files.createDirectories(repo.resolve(".git"))
        val forbiddenYaml = forbidden.joinToString("") { "  - $it\n" }.let { if (it.isEmpty()) "" else "forbidden:\n$it" }
        val yaml = "project:\n  name: alpha\nlayers:\n  - name: core\n    path: core/\n" +
            "  - name: daemon\n    path: daemon/\n  - name: cli\n    path: cli/\n" + forbiddenYaml
        val source = object : ProjectConfigSource {
            override fun read(repo: Path): String = yaml
        }
        return ProjectRegistry(source).register(repo)
    }
}

class ScopeGateTest {

    @TempDir
    lateinit var dir: Path

    private val daemonOnly = setOf("daemon")

    private fun violations(verdict: ScopeVerdict): ScopeVerdict.Violations =
        verdict as? ScopeVerdict.Violations ?: throw AssertionError("expected Violations but was $verdict")

    @Test
    fun `AC-1 a diff touching the protected paths fails even when the project lists no forbidden path`() {
        val changed = listOf(".shipfloor/factory.yml", ".github/workflows/ci.yml", ".claude/settings.json", "daemon/A.kt")

        val result = violations(ScopeGate.check(ScopeFixtures.aProject(), changed, daemonOnly))

        assertThat(result.forbidden)
            .containsExactly(".claude/settings.json", ".github/workflows/ci.yml", ".shipfloor/factory.yml")
        assertThat(result.outsideLayers).isEmpty()
    }

    @Test
    fun `AC-2 a path under the project forbidden list fails and the violating files are named`() {
        val project = ScopeFixtures.aProject(forbidden = listOf("gradle/wrapper/", "daemon/secrets"))
        val changed = listOf("gradle/wrapper/gradle-wrapper.jar", "daemon/secrets/key.txt", "gradle/wrapperx/ok.txt", "daemon/B.kt")

        val result = violations(ScopeGate.check(project, changed, daemonOnly))

        assertThat(result.forbidden).containsExactly("daemon/secrets/key.txt", "gradle/wrapper/gradle-wrapper.jar")
        // gradle/wrapperx is not under gradle/wrapper/ (segment prefix) but it is outside the daemon layer.
        assertThat(result.outsideLayers).containsExactly("gradle/wrapperx/ok.txt")
    }

    @Test
    fun `AC-3 files outside the stage layers fail and are named, sorted`() {
        val changed = listOf("daemon/A.kt", "core/Z.kt", "cli/Main.kt", "daemonx/Sneaky.kt", "README.md")

        val result = violations(ScopeGate.check(ScopeFixtures.aProject(), changed, daemonOnly))

        assertThat(result.outsideLayers).containsExactly("README.md", "cli/Main.kt", "core/Z.kt", "daemonx/Sneaky.kt")
        assertThat(result.forbidden).isEmpty()
    }

    @Test
    fun `AC-3 a path that is both protected and outside the layers is reported as forbidden only`() {
        val result = violations(ScopeGate.check(ScopeFixtures.aProject(), listOf(".github/ci.yml"), daemonOnly))

        assertThat(result.forbidden).containsExactly(".github/ci.yml")
        assertThat(result.outsideLayers).isEmpty()
    }

    @Test
    fun `AC-4 a diff only inside the stage layers and outside forbidden paths passes`() {
        val project = ScopeFixtures.aProject(forbidden = listOf("gradle/wrapper/"))

        assertThat(ScopeGate.check(project, listOf("daemon/A.kt", "core/B.kt", "daemon/src/test/T.kt"), setOf("daemon", "core")))
            .isEqualTo(ScopeVerdict.Pass)
        assertThat(ScopeGate.check(project, emptyList(), daemonOnly)).isEqualTo(ScopeVerdict.Pass)
    }

    @Test
    fun `AC-6 both paths of a rename count`() {
        val project = ScopeFixtures.aProject()

        val intoProtected = violations(ScopeGate.check(project, listOf("daemon/old.sh", ".github/new.sh"), daemonOnly))
        val outOfProtected = violations(ScopeGate.check(project, listOf(".claude/old.md", "daemon/new.md"), daemonOnly))
        val outOfLayer = violations(ScopeGate.check(project, listOf("core/old.kt", "daemon/new.kt"), daemonOnly))

        assertThat(intoProtected.forbidden).containsExactly(".github/new.sh")
        assertThat(outOfProtected.forbidden).containsExactly(".claude/old.md")
        assertThat(outOfLayer.outsideLayers).containsExactly("core/old.kt")
    }

    @Test
    fun `AC-6 GitChangedPaths lists origin and destination of a renamed file`() {
        val repo = gitRepo()
        Files.createDirectories(repo.resolve("daemon"))
        Files.writeString(repo.resolve("daemon/old.txt"), "content that is long enough to be detected as a rename\n".repeat(5))
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "base")
        Files.createDirectories(repo.resolve(".github"))
        git(repo, "mv", "daemon/old.txt", ".github/new.txt")

        val changed = GitChangedPaths().between(repo, "HEAD")

        assertThat(changed).containsExactlyInAnyOrder("daemon/old.txt", ".github/new.txt")
    }

    @Test
    fun `AC-6 GitChangedPaths ignores what the base gained after the branch started`() {
        val repo = gitRepo()
        Files.writeString(repo.resolve("a.txt"), "a")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "base")
        git(repo, "branch", "-M", "main")
        git(repo, "switch", "-q", "-c", "feat/x")
        Files.createDirectories(repo.resolve("daemon"))
        Files.writeString(repo.resolve("daemon/A.kt"), "class A")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "card work")
        git(repo, "switch", "-q", "main")
        Files.createDirectories(repo.resolve("app"))
        Files.writeString(repo.resolve("app/Other.kt"), "class Other")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "main moved on")
        git(repo, "switch", "-q", "feat/x")
        Files.writeString(repo.resolve("daemon/B.kt"), "class B")

        val changed = GitChangedPaths().between(repo, "main")

        assertThat(changed).containsExactlyInAnyOrder("daemon/A.kt")
    }

    @Test
    fun `AC-6 GitChangedPaths refuses a base that starts with a dash`() {
        val repo = gitRepo()
        Files.writeString(repo.resolve("a.txt"), "a")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "base")

        assertThrows(RuntimeException::class.java) { GitChangedPaths().between(repo, "--output=evil.txt") }
        assertThat(Files.exists(repo.resolve("evil.txt"))).isFalse()
    }

    @Test
    fun `AC-7 the shared paths pass in any stage alongside the stage layers`() {
        val changed = listOf(
            "gradle/libs.versions.toml",
            "docs/superpowers/specs/2026-10-16-x-design.md",
            "docs/superpowers/plans/2026-10-16-x.md",
            "daemon/A.kt",
        )

        assertThat(ScopeGate.check(ScopeFixtures.aProject(), changed, daemonOnly)).isEqualTo(ScopeVerdict.Pass)
        assertThat(ScopeGate.check(ScopeFixtures.aProject(), changed.take(3), emptySet())).isEqualTo(ScopeVerdict.Pass)
    }

    @Test
    fun `AC-7 the shared paths are matched by segment and CLAUDE md at the root is protected`() {
        val changed = listOf("CLAUDE.md", "gradle/libs.versions.toml.bak", "docs/superpowersx/a.md", "docs/other.md", "daemon/CLAUDE.md")

        val result = violations(ScopeGate.check(ScopeFixtures.aProject(), changed, daemonOnly))

        assertThat(result.forbidden).containsExactly("CLAUDE.md")
        assertThat(result.outsideLayers)
            .containsExactly("docs/other.md", "docs/superpowersx/a.md", "gradle/libs.versions.toml.bak")
    }

    @Test
    fun `paths are normalized and a parent traversal or absolute path is a violation`() {
        val project = ScopeFixtures.aProject()

        val dotted = violations(ScopeGate.check(project, listOf("./.shipfloor/x", ".//github-not/../.github/y"), daemonOnly))
        val sneaky = violations(ScopeGate.check(project, listOf("daemon/../.github/ci.yml", "/etc/passwd", "daemon/ok.kt"), daemonOnly))

        assertThat(dotted.forbidden + dotted.outsideLayers).hasSize(2)
        assertThat(sneaky.forbidden + sneaky.outsideLayers)
            .containsExactlyInAnyOrder("daemon/../.github/ci.yml", "/etc/passwd")
    }

    private fun gitRepo(): Path {
        val repo = Files.createDirectories(dir.resolve("repo"))
        git(repo, "init", "-q", "-b", "main")
        return repo
    }

    private fun git(repo: Path, vararg args: String) {
        val command = listOf(
            "git", "-C", repo.toString(),
            "-c", "user.name=QA", "-c", "user.email=qa@example.invalid", "-c", "commit.gpgsign=false",
        ) + args
        val log = Files.createTempFile(dir, "git", ".log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).describedAs(Files.readString(log)).isZero()
    }
}
