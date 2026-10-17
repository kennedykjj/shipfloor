package shipfloor.app

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import shipfloor.core.Column
import shipfloor.core.EventType

/**
 * Shape the implementation must follow (in `BoardMonitor.kt`, no UI, no sleeps):
 *
 * - `BoardMonitor(source: BoardSource, connection: StateFlow<ConnectionState>, io: CoroutineDispatcher)`;
 *   `val view: StateFlow<BoardView?>` is `null` until the first snapshot; `BoardView(board: Board, live: Boolean)`.
 * - `suspend fun run()` loops until cancelled: on `Connected` it subscribes (`after = board.lastSeq`), reads the
 *   snapshot once, applies events (`CARD_CREATED` through `source.fetchCard`); when `subscribe` throws `IOException`
 *   it sets `live = false`, waits for the next `Connected` and subscribes again with `after = lastSeq`, without
 *   reloading the snapshot. The blocking source calls run on `io`.
 */
class BoardMonitorTest {
    @Test
    fun `AC-7 resubscribes after the drop from the last seen seq without reloading or duplicating`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.VALIDATION))
        fx.source.sessions += listOf(BoardFixtures.aMove(3, "a", Column.VALIDATION))
        fx.source.sessions += listOf(
            BoardFixtures.aMove(3, "a", Column.DONE),
            BoardFixtures.aMove(4, "a", Column.STAGED),
        )

        fx.connect()
        fx.drop()
        fx.connect()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(fx.source.afters).containsExactly(null, 3L)
        assertThat(fx.source.snapshotCalls).isEqualTo(1)
        assertThat(board.cardsIn(Column.STAGED).map { it.id }).containsExactly("a")
        assertThat(board.cardsIn(Column.VALIDATION)).isEmpty()
        assertThat(board.cards).hasSize(1)
    }

    @Test
    fun `AC-8 shows no board before the first snapshot and marks the kept board as not live after a drop`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.SPEC_REVIEW))
        assertThat(fx.monitor.view.value).isNull()

        fx.connect()
        fx.drop()

        val view = requireNotNull(fx.monitor.view.value)
        assertThat(view.live).isFalse()
        assertThat(view.board.cardsIn(Column.SPEC_REVIEW).map { it.id }).containsExactly("a")
    }

    @Test
    fun `AC-9 the owner approval moves the card out of Spec Review and drops its highlight`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.SPEC_REVIEW))
        fx.source.sessions += emptyList<BoardEvent>()
        fx.source.sessions += listOf(BoardFixtures.aMove(2, "a", Column.READY_FOR_DEV, EventType.OWNER_APPROVED))

        fx.connect()
        fx.drop()
        fx.connect()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(board.cardsIn(Column.SPEC_REVIEW)).isEmpty()
        assertThat(board.cardsIn(Column.READY_FOR_DEV).map { it.id }).containsExactly("a")
        assertThat(isWaitingForOwner(Column.READY_FOR_DEV)).isFalse()
    }

    @Test
    fun `AC-10 a created card is placed in the column fetched with card show, not in Inbox`() = withMonitor { fx ->
        fx.source.snapshot = emptyList()
        fx.source.known["n"] = BoardFixtures.aCard("n", Column.CODING, "Imported")
        fx.source.sessions += emptyList<BoardEvent>()
        fx.source.sessions += listOf(BoardFixtures.anEvent(2, "n", EventType.CARD_CREATED))

        fx.connect()
        fx.drop()
        fx.connect()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(board.cardsIn(Column.CODING).map { it.id }).containsExactly("n")
        assertThat(board.cardsIn(Column.INBOX)).isEmpty()
        assertThat(fx.source.fetched).containsExactly("n")
    }

    @Test
    fun `AC-1 resync reads the snapshot again and the board shows exactly the returned cards`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.CODING), BoardFixtures.aCard("b", Column.INBOX))
        fx.connect()
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.DONE), BoardFixtures.aCard("c", Column.INBOX))

        val result = fx.monitor.resync()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(result).isEqualTo(ResyncResult.Reloaded)
        assertThat(fx.source.snapshotCalls).isEqualTo(2)
        assertThat(board.cards.map { it.id }).containsExactlyInAnyOrder("a", "c")
        assertThat(board.cardsIn(Column.DONE).map { it.id }).containsExactly("a")
        assertThat(board.cardsIn(Column.CODING)).isEmpty()
        assertThat(board.cardsIn(Column.INBOX).map { it.id }).containsExactly("c")
    }

    @Test
    fun `AC-2 an event that arrives while the resync snapshot is read is applied after it`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.CODING))
        fx.connect()
        fx.source.onSnapshot = { fx.source.deliver(BoardFixtures.aMove(5, "a", Column.DONE)) }

        val result = fx.monitor.resync()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(result).isEqualTo(ResyncResult.Reloaded)
        assertThat(board.cards).hasSize(1)
        assertThat(board.cardsIn(Column.DONE).map { it.id }).containsExactly("a")
        assertThat(board.cardsIn(Column.CODING)).isEmpty()
        assertThat(board.lastSeq).isEqualTo(5L)
    }

    @Test
    fun `AC-3 a failed snapshot read keeps the board, applies the held events and reports Failed`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.CODING), BoardFixtures.aCard("b", Column.INBOX))
        fx.connect()
        fx.source.snapshotFailure = IOException("daemon not responding")
        fx.source.onSnapshot = { fx.source.deliver(BoardFixtures.aMove(5, "b", Column.SPEC_REVIEW)) }

        val result = fx.monitor.resync()

        val board = requireNotNull(fx.monitor.view.value).board
        assertThat(result).isEqualTo(ResyncResult.Failed)
        assertThat(board.cards.map { it.id }).containsExactlyInAnyOrder("a", "b")
        assertThat(board.cardsIn(Column.CODING).map { it.id }).containsExactly("a")
        assertThat(board.cardsIn(Column.SPEC_REVIEW).map { it.id }).containsExactly("b")
    }

    @Test
    fun `AC-4 a resync while another is running starts no second snapshot read`() = withMonitor { fx ->
        fx.source.snapshot = listOf(BoardFixtures.aCard("a", Column.CODING))
        fx.connect()
        var nested: ResyncResult? = null
        fx.source.onSnapshot = {
            fx.source.onSnapshot = {}
            nested = runBlocking { fx.monitor.resync() }
        }

        val outer = fx.monitor.resync()
        val afterwards = fx.monitor.resync()

        assertThat(nested).isEqualTo(ResyncResult.Busy)
        assertThat(outer).isEqualTo(ResyncResult.Reloaded)
        assertThat(afterwards).isEqualTo(ResyncResult.Reloaded)
        assertThat(fx.source.snapshotCalls).isEqualTo(3)
    }

    private fun withMonitor(body: suspend (MonitorFixtures) -> Unit) = runBlocking {
        val fx = MonitorFixtures()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { fx.monitor.run() }
        try {
            body(fx)
        } finally {
            job.cancelAndJoin()
        }
    }
}

class FakeBoardSource : BoardSource {
    var snapshot: List<BoardCard> = emptyList()
    val known = mutableMapOf<String, BoardCard>()
    val sessions = ArrayDeque<List<BoardEvent>>()
    val afters = mutableListOf<Long?>()
    val fetched = mutableListOf<String>()
    var snapshotCalls = 0
        private set
    var onSnapshot: () -> Unit = {}
    var snapshotFailure: IOException? = null
    private var listener: ((BoardEvent) -> Unit)? = null

    /** Delivers an event through the listener the monitor registered on its last subscription. */
    fun deliver(event: BoardEvent) {
        requireNotNull(listener) { "no subscription yet" }(event)
    }

    override fun snapshot(): List<BoardCard> {
        snapshotCalls += 1
        onSnapshot()
        snapshotFailure?.let { throw it }
        return snapshot
    }

    override fun fetchCard(id: String): BoardCard? {
        fetched += id
        return known[id]
    }

    override fun subscribe(after: Long?, onEvent: (BoardEvent) -> Unit) {
        afters += after
        listener = onEvent
        sessions.removeFirstOrNull().orEmpty().forEach(onEvent)
        throw IOException("connection closed")
    }
}

class MonitorFixtures {
    val source = FakeBoardSource()
    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val monitor = BoardMonitor(source, connection, Dispatchers.Unconfined)

    suspend fun connect() {
        connection.value = ConnectionState.Connected
        settle()
    }

    suspend fun drop() {
        connection.value = ConnectionState.Disconnected(DisconnectReason.NOT_RESPONDING)
        settle()
    }

    private suspend fun settle() = repeat(10) { yield() }
}
