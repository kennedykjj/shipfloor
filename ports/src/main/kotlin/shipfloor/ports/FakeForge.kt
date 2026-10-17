package shipfloor.ports

private const val SUMMARY_LIMIT_BYTES = 2048

/**
 * Scripted forge for tests: PRs live in memory; no IO, no clock, no network.
 * [failNext] fails the next call of that operation name, before any state changes.
 */
class FakeForge : Forge {
    private enum class Status { OPEN, MERGED, CLOSED }

    private class Entry(
        val number: Int,
        var request: PullRequestRequest,
        var head: String,
        var status: Status = Status.OPEN,
        var conflictNextMerge: Boolean = false,
        var author: String = "",
        var fromFork: Boolean = false,
    )

    private val entries = mutableListOf<Entry>()
    private val requests = mutableListOf<PullRequestRequest>()
    private val mergeRefs = mutableListOf<PullRequestRef>()
    private val rerunHeads = mutableListOf<String>()
    private val revertRefs = mutableListOf<PullRequestRef>()
    private val checksByHead = mutableMapOf<String, ChecksState>()
    private var pendingFailure: String? = null

    /** Requests opened, in order; a re-open of an open branch replaces that branch's entry. */
    val opened: List<PullRequestRequest> get() = requests.toList()

    val merges: List<PullRequestRef> get() = mergeRefs.toList()

    /** Heads passed to [rerunChecks], in call order; failed calls are not recorded. */
    val reruns: List<String> get() = rerunHeads.toList()

    /** PRs passed to [revert], in call order; failed calls are not recorded. */
    val reverts: List<PullRequestRef> get() = revertRefs.toList()

    fun scriptChecks(head: String, state: ChecksState) {
        checksByHead[head] = when (state) {
            is ChecksState.Failed -> ChecksState.Failed(
                state.failing.map { it.copy(summary = truncateUtf8(it.summary, SUMMARY_LIMIT_BYTES)) },
            )
            else -> state
        }
    }

    fun mergedOutside(pr: PullRequestRef) {
        find(pr.number).status = Status.MERGED
    }

    fun closeOutside(pr: PullRequestRef) {
        find(pr.number).status = Status.CLOSED
    }

    fun pushHead(pr: PullRequestRef, head: String) {
        find(pr.number).head = head
    }

    fun scriptConflict(pr: PullRequestRef) {
        find(pr.number).conflictNextMerge = true
    }

    fun failNext(operation: String) {
        pendingFailure = operation
    }

    /** Login the fake authenticates as; PRs opened afterwards are authored by it. */
    var login: String = "factory-owner"

    fun setAuthor(pr: PullRequestRef, author: String) {
        find(pr.number).author = author
    }

    fun setFromFork(pr: PullRequestRef, fromFork: Boolean) {
        find(pr.number).fromFork = fromFork
    }

    override fun authenticatedLogin(): String {
        failIf("authenticatedLogin", "authenticated user")
        return login
    }

    override fun listPullRequests(): List<PullRequestSummary> {
        failIf("listPullRequests", "repository")
        return entries.map {
            PullRequestSummary(it.number, it.request.branch, it.request.title, stateOf(it), it.author, it.fromFork)
        }
    }

    override fun openPullRequest(request: PullRequestRequest): PullRequestRef {
        failIf("openPullRequest", "branch ${request.branch}")
        val existing = entries.firstOrNull { it.status == Status.OPEN && it.request.branch == request.branch }
        if (existing != null) {
            existing.request = request
            requests[requests.indexOfLast { it.branch == request.branch }] = request
            return refOf(existing)
        }
        val number = entries.size + 1
        val entry = Entry(number = number, request = request, head = "sha-$number", author = login)
        entries += entry
        requests += request
        return refOf(entry)
    }

    override fun checks(head: String): ChecksState {
        failIf("checks", "head $head")
        return checksByHead[head] ?: ChecksState.Pending
    }

    override fun merge(pr: PullRequestRef, expectedHead: String): MergeResult {
        failIf("merge", "PR #${pr.number}")
        val entry = find(pr.number)
        if (entry.status != Status.OPEN) return MergeResult.NotOpen(stateOf(entry))
        if (entry.head != expectedHead) return MergeResult.HeadMismatch(entry.head)
        if (entry.conflictNextMerge) {
            entry.conflictNextMerge = false
            return MergeResult.Conflict
        }
        entry.status = Status.MERGED
        mergeRefs += refOf(entry)
        return MergeResult.Merged("merge-${entry.number}")
    }

    override fun findOpenPullRequest(branch: String): PullRequestRef? {
        failIf("findOpenPullRequest", "branch $branch")
        return entries.firstOrNull { it.status == Status.OPEN && it.request.branch == branch }?.let { refOf(it) }
    }

    override fun findMergedPullRequest(branch: String): MergedPullRequest? {
        failIf("findMergedPullRequest", "branch $branch")
        return entries.lastOrNull { it.status == Status.MERGED && it.request.branch == branch }
            ?.let { MergedPullRequest(it.number, "merge-${it.number}", it.head, null) }
    }

    override fun state(pr: PullRequestRef): PullRequestState {
        failIf("state", "PR #${pr.number}")
        return stateOf(find(pr.number))
    }

    override fun rerunChecks(head: String) {
        failIf("rerunChecks", "head $head")
        rerunHeads += head
    }

    override fun revert(pr: PullRequestRef): PullRequestRef {
        failIf("revert", "PR #${pr.number}")
        find(pr.number)
        val number = entries.size + 1
        val entry = Entry(
            number = number,
            request = PullRequestRequest("revert-${pr.number}", "main", "Revert PR #${pr.number}", ""),
            head = "sha-$number",
        )
        entries += entry
        revertRefs += pr
        return refOf(entry)
    }

    private fun find(number: Int): Entry =
        entries.firstOrNull { it.number == number }
            ?: throw UnknownPullRequestException("Unknown PR #$number")

    private fun failIf(operation: String, subject: String) {
        if (pendingFailure != operation) return
        pendingFailure = null
        throw ForgeException("Forge $operation failed for $subject")
    }

    private fun refOf(entry: Entry) = PullRequestRef(entry.number, entry.request.branch, entry.head)

    private fun stateOf(entry: Entry): PullRequestState = when (entry.status) {
        Status.OPEN -> PullRequestState.Open(entry.head)
        Status.MERGED -> PullRequestState.Merged
        Status.CLOSED -> PullRequestState.ClosedUnmerged
    }
}

/** Cuts [text] to at most [limitBytes] UTF-8 bytes without splitting a code point. */
private fun truncateUtf8(text: String, limitBytes: Int): String {
    val out = StringBuilder()
    var bytes = 0
    for (codePoint in text.codePoints().toArray()) {
        val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > limitBytes) break
        bytes += size
        out.appendCodePoint(codePoint)
    }
    return out.toString()
}
