package shipfloor.app

import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import shipfloor.core.Column
import shipfloor.core.ColumnTime
import shipfloor.core.EventType

/** The time per column for the stays that ended in [from, to). Read only. */
fun interface ColumnTimesSource {
    @Throws(IOException::class)
    fun times(from: Instant, to: Instant): Map<Column, ColumnTime>
}

/** How a [BoardMonitor.resync] ended; Busy means another one was already running. */
enum class ResyncResult { Reloaded, Failed, Busy }

/** The board as the screen shows it; [live] is false while the event stream is not open. */
data class BoardView(val board: Board, val live: Boolean, val columnTimes: Map<Column, ColumnTime> = emptyMap())

/**
 * Keeps the board in step with the daemon while [connection] says Connected. It subscribes first, then reads the
 * snapshot; events that arrive before the snapshot is applied wait and are applied right after it (events are
 * absolute, so the last one per card wins). After a drop it subscribes again from the last seen `seq`, without
 * reloading the snapshot. With a [times] source it also reads the time per column of the current local day after
 * the snapshot and after each move; a failed read hides the subtitles and never touches the board.
 */
class BoardMonitor(
    private val source: BoardSource,
    private val connection: StateFlow<ConnectionState>,
    private val io: CoroutineDispatcher,
    private val observer: (BoardEvent, BoardCard?) -> Unit = { _, _ -> },
    private val snapshotObserver: (List<BoardCard>) -> Unit = {},
    private val times: ColumnTimesSource? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : BoardEdits {
    private val mutableView = MutableStateFlow<BoardView?>(null)
    val view: StateFlow<BoardView?> = mutableView

    private val lock = Any()
    private var board = Board(emptyList(), null)
    private var columnTimes: Map<Column, ColumnTime> = emptyMap()
    private var live = false
    private var hasSnapshot = false
    private var waiting: MutableList<BoardEvent>? = null
    private var resyncing = false

    override fun board(): Board? = synchronized(lock) { board.takeIf { hasSnapshot } }

    /** Applies an owner edit (optimistic move or its reversal) to the board and publishes it. */
    override fun edit(transform: (Board) -> Board) {
        synchronized(lock) {
            board = transform(board)
            publish()
        }
    }

    /**
     * Reads the snapshot again while the subscription stays open. Events that arrive meanwhile wait and are applied
     * after it. On a failed read the board stays as it was and the held events are still applied.
     */
    suspend fun resync(): ResyncResult {
        val started = synchronized(lock) {
            if (!hasSnapshot || resyncing || waiting != null) {
                false
            } else {
                resyncing = true
                waiting = mutableListOf()
                true
            }
        }
        if (!started) return ResyncResult.Busy
        var reloaded = false
        try {
            reloaded = loadSnapshot()
        } finally {
            if (!reloaded) drainWaiting()
            synchronized(lock) { resyncing = false }
        }
        return if (reloaded) ResyncResult.Reloaded else ResyncResult.Failed
    }

    suspend fun run() {
        while (true) {
            connection.first { it == ConnectionState.Connected }
            session()
            // Resubscribe on the next Connected; if the connection never flips, retry after a pause.
            withTimeoutOrNull(RETRY_AFTER.toMillis()) { connection.first { it != ConnectionState.Connected } }
        }
    }

    private suspend fun session() = coroutineScope {
        synchronized(lock) { if (!hasSnapshot) waiting = mutableListOf() }
        val after = synchronized(lock) { board.lastSeq }
        val subscription = launch {
            try {
                runInterruptible(io) { source.subscribe(after, ::receive) }
            } catch (e: IOException) {
                // The stream ended; the session wraps up below.
            }
        }
        val watcher = launch {
            connection.first { it != ConnectionState.Connected }
            subscription.cancelAndJoin()
        }
        try {
            if (!hasSnapshot && !loadSnapshot()) subscription.cancelAndJoin()
            if (subscription.isActive) setLive(true)
            subscription.join()
        } finally {
            watcher.cancel()
            setLive(false)
        }
    }

    /**
     * Reads the snapshot and shows it, then the events that waited, and only then reads the times per column and shows
     * them too: the board does not wait for a statistic. False when the daemon could not be read.
     */
    private suspend fun loadSnapshot(): Boolean {
        val cards = try {
            runInterruptible(io) { source.snapshot() }
        } catch (e: IOException) {
            return false
        }
        synchronized(lock) {
            board = board.applySnapshot(cards)
            hasSnapshot = true
        }
        snapshotObserver(cards)
        drainWaiting()
        val read = runInterruptible(io) { readColumnTimes() }
        synchronized(lock) {
            columnTimes = read
            publish()
        }
        return true
    }

    private fun drainWaiting() {
        while (true) {
            val batch = synchronized(lock) {
                val pending = waiting.orEmpty().toList()
                if (pending.isEmpty()) {
                    waiting = null
                    publish()
                }
                pending.also { waiting?.clear() }
            }
            if (batch.isEmpty()) return
            batch.forEach(::apply)
        }
    }

    /** Called on the io thread for each event of the stream. */
    private fun receive(event: BoardEvent) {
        val held = synchronized(lock) { waiting?.add(event) ?: false }
        if (!held) apply(event)
    }

    private fun apply(event: BoardEvent) {
        val fetched = if (event.type == EventType.CARD_CREATED) fetchOrNull(event.cardId) else null
        val read = if (event.type == EventType.CARD_MOVED || event.type == EventType.OWNER_APPROVED) readColumnTimes() else null
        synchronized(lock) {
            board = board.applyEvent(event, fetched)
            read?.let { columnTimes = it }
            publish()
        }
        observer(event, fetched)
    }

    private fun readColumnTimes(): Map<Column, ColumnTime> {
        val source = times ?: return emptyMap()
        val today = clock.instant().atZone(zone).toLocalDate()
        return try {
            source.times(today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun fetchOrNull(id: String): BoardCard? =
        try {
            source.fetchCard(id)
        } catch (e: IOException) {
            null
        }

    private fun setLive(value: Boolean) {
        synchronized(lock) {
            live = value
            publish()
        }
    }

    private fun publish() {
        if (hasSnapshot && waiting == null) mutableView.value = BoardView(board, live, columnTimes)
    }

    private companion object {
        val RETRY_AFTER: Duration = Duration.ofSeconds(5)
    }
}
