package shipfloor.daemon

import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import shipfloor.ports.ChecksState
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.PullRequestRequest

/** What the CI said about the head the daemon pushed. */
sealed interface CiResult {
    /** The CI approved the head of pull request [pr]. */
    data class Green(val pr: Int) : CiResult

    /** [summary] is untrusted text: failed check names and summaries, cut at [MAX_CI_SUMMARY_BYTES] UTF-8 bytes. */
    data class Red(val summary: String) : CiResult

    data object TimedOut : CiResult

    data object ForgeFailed : CiResult
}

const val MAX_CI_SUMMARY_BYTES = 2048

sealed interface PushResult {
    data object Pushed : PushResult

    /** The branch name is not one the daemon pushes; nothing ran. */
    data object Refused : PushResult

    data object Failed : PushResult
}

/** Pushes the card's own branch to its remote. */
fun interface BranchPublisher {
    fun push(worktree: Path, branch: String): PushResult
}

/** The wait between two CI polls; injected so the tests run without a real clock. */
fun interface CiPause {
    fun await()
}

/** The daemon's production pause: parks the polling thread, and returns early when the thread is interrupted. */
class ParkingCiPause(private val seconds: Long = DEFAULT_SECONDS) : CiPause {
    override fun await() = LockSupport.parkNanos(seconds * NANOS_PER_SECOND)

    private companion object {
        const val DEFAULT_SECONDS = 30L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** The push of the card's branch was refused; the card is blocked with `push_refused`. */
class PushRefusedException(branch: String) : RuntimeException("push refused for branch $branch")

/**
 * Pushes the card's branch, opens (or updates) its pull request and waits for the CI of the pushed head. The green of
 * any other head never counts: a head the forge still shows as the old commit is a pending CI.
 */
class PullRequestDelivery(
    private val forge: Forge,
    private val publisher: BranchPublisher,
    private val head: WorktreeHead,
    private val pause: CiPause,
    private val maxPolls: Int = DEFAULT_MAX_POLLS,
    private val base: String = "main",
) {
    init {
        require(maxPolls > 0) { "maxPolls must be positive, got $maxPolls" }
    }

    fun headOf(worktree: Path): String? = head.of(worktree)

    fun deliver(cardId: String, branch: String, title: String, body: String, worktree: Path): CiResult {
        when (publisher.push(worktree, branch)) {
            PushResult.Pushed -> Unit
            PushResult.Refused -> throw PushRefusedException(branch)
            PushResult.Failed -> return CiResult.ForgeFailed
        }
        val local = head.of(worktree) ?: return CiResult.ForgeFailed
        return try {
            val opened = forge.openPullRequest(PullRequestRequest(branch, base, title, body))
            var shown = opened.head
            var result: ChecksState = ChecksState.Pending
            for (poll in 1..maxPolls) {
                if (poll > 1) shown = forge.findOpenPullRequest(branch)?.head ?: shown
                result = if (shown == local) forge.checks(local) else ChecksState.Pending
                if (result != ChecksState.Pending || poll == maxPolls) break
                pause.await()
            }
            when (val state = result) {
                ChecksState.Pending -> CiResult.TimedOut
                ChecksState.Passed -> CiResult.Green(opened.number)
                is ChecksState.Failed -> CiResult.Red(summaryOf(state))
            }
        } catch (e: ForgeException) {
            CiResult.ForgeFailed
        }
    }

    private fun summaryOf(state: ChecksState.Failed): String =
        truncateUtf8(state.failing.joinToString("\n") { "${it.name}: ${it.summary}" }, MAX_CI_SUMMARY_BYTES)

    private companion object {
        const val DEFAULT_MAX_POLLS = 40
    }
}

/** Cuts [text] to at most [limitBytes] UTF-8 bytes without splitting a character. */
internal fun truncateUtf8(text: String, limitBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val point = text.codePointAt(end)
        val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
        if (bytes + size > limitBytes) break
        bytes += size
        end += Character.charCount(point)
    }
    return text.substring(0, end)
}
