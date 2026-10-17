package shipfloor.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import shipfloor.adapters.AcliTracker
import shipfloor.adapters.GhForge
import shipfloor.core.TrackerConfig
import shipfloor.adapters.GhTracker
import shipfloor.ports.CloseResult
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItem
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.MergeResult
import shipfloor.ports.MergedPullRequest
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.PullRequestState
import shipfloor.ports.ChecksState

/** Builds the production [PullRequestDelivery] of a project: GitHub through `gh`, the push through plain git. */
object ProjectDelivery {
    private val GH_TIMEOUT: Duration = Duration.ofSeconds(60)
    private val GITHUB_REMOTE = Regex("""github\.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?$""")

    fun create(project: Project, tmpDir: Path): PullRequestDelivery {
        return PullRequestDelivery(forgeOf(project, tmpDir), SystemBranchPublisher(), GitWorktreeHead(), ParkingCiPause())
    }

    /** The project's [Forge]: GitHub through `gh` when origin is on GitHub, otherwise one that fails every call. */
    fun forgeOf(project: Project, tmpDir: Path): Forge {
        val repo = Path.of(project.repo)
        val slug = originSlug(repo) ?: return UnavailableForge
        Files.createDirectories(tmpDir)
        return GhForge(repo = slug, workDir = repo, tmpDir = tmpDir, timeout = GH_TIMEOUT)
    }

    /** The project's [Tracker]: GitHub issues through `gh` when origin is on GitHub, otherwise one that fails every call. */
    fun trackerOf(project: Project, tmpDir: Path): Tracker {
        val repo = Path.of(project.repo)
        val config = project.policies.tracker
        if (config is TrackerConfig.Jira) {
            Files.createDirectories(tmpDir)
            return AcliTracker(site = config.site, project = config.project, workDir = repo, tmpDir = tmpDir, timeout = GH_TIMEOUT)
        }
        val slug = originSlug(repo) ?: return UnavailableTracker
        Files.createDirectories(tmpDir)
        return GhTracker(repo = slug, workDir = repo, tmpDir = tmpDir, timeout = GH_TIMEOUT)
    }

    /** The web link of pull request [pr] on the project's GitHub origin; null without a GitHub origin. */
    fun pullRequestUrl(project: Project, pr: String): String? =
        originSlug(Path.of(project.repo))?.let { "https://github.com/$it/pull/$pr" }

    private fun originSlug(repo: Path): String? {
        val output = Files.createTempFile("shipfloor-origin", ".txt")
        try {
            val process = try {
                ProcessBuilder("git", "-C", repo.toString(), "config", "--get", "remote.origin.url")
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                return null
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            } finally {
                process.destroyForcibly()
            }
            return GITHUB_REMOTE.find(Files.readString(output).trim())?.groupValues?.get(1)
        } finally {
            Files.deleteIfExists(output)
        }
    }
}

/** Stands in when the project has no GitHub origin: every call fails. */
private object UnavailableTracker : Tracker {
    private fun unavailable(operation: String): Nothing = throw TrackerException("$operation: project has no GitHub origin")

    override fun fetch(id: String): TrackerItem? = unavailable("fetch")

    override fun search(query: String): List<TrackerItem> = unavailable("search")

    override fun writeMilestone(id: String, milestone: String) = unavailable("writeMilestone")

    override fun close(id: String, comment: String): CloseResult = unavailable("close")
}

/** Stands in when the project has no GitHub origin: every call fails, so the card is blocked and never staged. */
private object UnavailableForge : Forge {
    private fun unavailable(operation: String): Nothing = throw ForgeException("$operation: project has no GitHub origin")

    override fun openPullRequest(request: PullRequestRequest): PullRequestRef = unavailable("openPullRequest")

    override fun checks(head: String): ChecksState = unavailable("checks")

    override fun merge(pr: PullRequestRef, expectedHead: String): MergeResult = unavailable("merge")

    override fun findOpenPullRequest(branch: String): PullRequestRef? = unavailable("findOpenPullRequest")

    override fun state(pr: PullRequestRef): PullRequestState = unavailable("state")

    override fun rerunChecks(head: String) = unavailable("rerunChecks")

    override fun revert(pr: PullRequestRef): PullRequestRef = unavailable("revert")

    override fun findMergedPullRequest(branch: String): MergedPullRequest? = unavailable("findMergedPullRequest")
}
