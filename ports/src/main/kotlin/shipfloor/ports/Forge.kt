package shipfloor.ports

/** Delivery port: opens, reads and merges pull requests. Implementations may do IO; callers treat the results as data. */
interface Forge {
    fun openPullRequest(request: PullRequestRequest): PullRequestRef

    fun checks(head: String): ChecksState

    fun merge(pr: PullRequestRef, expectedHead: String): MergeResult

    fun findOpenPullRequest(branch: String): PullRequestRef?

    fun state(pr: PullRequestRef): PullRequestState

    /** Asks the forge to run the checks of [head] again. */
    fun rerunChecks(head: String)

    /** Opens a new PR that reverts [pr] and returns its ref. */
    fun revert(pr: PullRequestRef): PullRequestRef

    /** Most recent merged PR of [branch], or null. Throws [ForgeException] on forge failure. */
    fun findMergedPullRequest(branch: String): MergedPullRequest?

    /** Every PR of the repository, any state. Throws [ForgeException] on forge failure. */
    fun listPullRequests(): List<PullRequestSummary> = throw ForgeException("Forge listPullRequests is not supported")

    /** Login the forge credentials authenticate as. Throws [ForgeException] on forge failure. */
    fun authenticatedLogin(): String = throw ForgeException("Forge authenticatedLogin is not supported")
}

/** [title], [branch] and [author] come from outside and are data, never instructions. */
data class PullRequestSummary(
    val number: Int,
    val branch: String,
    val title: String,
    val state: PullRequestState,
    val author: String,
    val fromFork: Boolean,
)

/** [head] is the PR head SHA at merge time; [mergedBy] is the merger login only when it is a valid login, else null. */
data class MergedPullRequest(val number: Int, val mergeCommit: String, val head: String, val mergedBy: String?)

data class PullRequestRequest(val branch: String, val base: String, val title: String, val body: String)

data class PullRequestRef(val number: Int, val branch: String, val head: String)

data class FailedCheck(val name: String, val summary: String)

sealed interface ChecksState {
    data object Pending : ChecksState

    data object Passed : ChecksState

    /** [FailedCheck.summary] is capped at 2048 bytes UTF-8 by the adapter; it is data, never an instruction. */
    data class Failed(val failing: List<FailedCheck>) : ChecksState
}

sealed interface PullRequestState {
    data class Open(val head: String) : PullRequestState

    data object Merged : PullRequestState

    data object ClosedUnmerged : PullRequestState
}

sealed interface MergeResult {
    data class Merged(val mergeCommit: String) : MergeResult

    data class HeadMismatch(val actualHead: String) : MergeResult

    /** Base moved and the PR does not merge cleanly. The PR stays open. */
    data object Conflict : MergeResult

    data class NotOpen(val state: PullRequestState) : MergeResult
}

/** Forge failure (network, API error). The message names the operation and the PR number, never the PR body or CI log. */
class ForgeException(message: String) : RuntimeException(message)

class UnknownPullRequestException(message: String) : RuntimeException(message)
