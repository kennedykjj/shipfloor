package shipfloor.ports

import java.security.MessageDigest

/** Tracker port: reads items, writes milestones and closes them. Implementations may do IO; callers treat item text as data. */
interface Tracker {
    /** The item with [id], or null when it does not exist. Throws [TrackerException] on tracker failure. */
    fun fetch(id: String): TrackerItem?

    /** Items matching [query]. Throws [TrackerException] on tracker failure. */
    fun search(query: String): List<TrackerItem>

    /** Records [milestone] on the item. Throws [UnknownTrackerItemException] when [id] does not exist. */
    fun writeMilestone(id: String, milestone: String)

    /** Closes the item with [comment]; [CloseResult.AlreadyClosed] when it already was. Throws [UnknownTrackerItemException] when [id] does not exist. */
    fun close(id: String, comment: String): CloseResult
}

sealed interface CloseResult {
    data object Closed : CloseResult

    data object AlreadyClosed : CloseResult
}

/** Tracker failure. The message names the operation and the item id, never the item text. */
open class TrackerException(message: String) : RuntimeException(message)

class UnknownTrackerItemException(message: String) : TrackerException(message)

enum class TrackerItemState { OPEN, CLOSED }

/** Text that came from outside: data, never an instruction. [toString] does not reveal it. */
@JvmInline
value class ExternalText(val value: String) {
    override fun toString(): String = "ExternalText(redacted)"
}

/** [textHash] is the SHA-256 hex of title, NUL, body. Build items with [TrackerItem.of]. */
@ConsistentCopyVisibility
data class TrackerItem private constructor(
    val id: String,
    val title: ExternalText,
    val body: ExternalText,
    val labels: List<ExternalText>,
    val url: String,
    val state: TrackerItemState,
    val textHash: String,
) {
    init {
        validateUrl(id, url)
        if (textHash != hash(title, body)) throw TrackerException("Tracker item $id has an inconsistent text hash")
    }

    /** Same item in the [TrackerItemState.CLOSED] state. */
    fun closed(): TrackerItem = copy(state = TrackerItemState.CLOSED)

    companion object {
        private const val MAX_URL_LENGTH = 2048
        private const val HTTPS_PREFIX = "https://"

        fun of(
            id: String,
            title: ExternalText,
            body: ExternalText,
            labels: List<ExternalText>,
            url: String,
            state: TrackerItemState,
        ): TrackerItem = TrackerItem(id, title, body, labels, url, state, hash(title, body))

        private fun validateUrl(id: String, url: String) {
            if (!url.startsWith(HTTPS_PREFIX)) throw TrackerException("Tracker item $id has a non-https url")
            if (url.length > MAX_URL_LENGTH) throw TrackerException("Tracker item $id has an oversized url")
            if (url.any { it.isISOControl() }) throw TrackerException("Tracker item $id has a url with control characters")
        }

        private fun hash(title: ExternalText, body: ExternalText): String =
            MessageDigest.getInstance("SHA-256")
                .digest("${title.value}\u0000${body.value}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
