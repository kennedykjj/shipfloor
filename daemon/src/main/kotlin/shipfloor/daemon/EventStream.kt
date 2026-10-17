package shipfloor.daemon

import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import shipfloor.store.CardEventStore
import shipfloor.store.StoredEvent

/**
 * Fans recorded events out to subscribed connections. Each subscriber has a bounded queue and its own
 * writer thread, so a slow reader never delays [publish] (and so never delays a card command).
 */
class EventStream(
    private val store: CardEventStore,
    private val queueLimit: Int = DEFAULT_QUEUE_LIMIT,
    private val log: DaemonLog = StderrDaemonLog,
    private val clock: Clock = Clock.systemUTC(),
    private val closeGrace: Duration = DEFAULT_CLOSE_GRACE,
) : AutoCloseable {
    private val subscribers = ConcurrentHashMap.newKeySet<Subscriber>()

    /** Subscribers removed for being slow whose connection is still open, waiting for [sweep] to close it. */
    private val slowDropped = ConcurrentHashMap.newKeySet<Subscriber>()

    private val listeners = CopyOnWriteArrayList<(StoredEvent) -> Unit>()

    @Volatile private var closed = false

    init {
        require(queueLimit > 0) { "queueLimit must be positive" }
        require(!closeGrace.isNegative) { "closeGrace must not be negative" }
        Thread.ofVirtual().start { sweepLoop() }
    }

    /** Stops the background sweep. Open subscriptions are left to end on their own. */
    override fun close() {
        closed = true
    }

    /** Closes the connection of every slow-dropped subscriber whose grace period has passed. */
    internal fun sweep() {
        val now = clock.instant()
        slowDropped.forEach { if (!now.isBefore(it.droppedAt.plus(closeGrace))) it.finish() }
    }

    private fun sweepLoop() {
        while (!closed) {
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            sweep()
        }
    }

    /** Hands [event] to every subscriber without blocking. A subscriber whose queue is full is dropped. */
    fun publish(event: StoredEvent) {
        subscribers.forEach { it.offer(event) }
        listeners.forEach {
            try {
                it(event)
            } catch (e: RuntimeException) {
                log.log("stream_listener_failed", mapOf("error" to e.javaClass.simpleName))
            }
        }
    }

    /** Registers an in-process [listener] called on every published event. It must not block. */
    fun addListener(listener: (StoredEvent) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Takes over [connection]: replays the events after [after] (when given), then streams live ones, with no
     * gap and no duplicate. The caller must not touch the connection afterwards; [onClosed] runs when it ends.
     */
    fun subscribe(connection: SocketChannel, after: Long?, onClosed: () -> Unit = {}) {
        val subscriber = Subscriber(connection, onClosed)
        // Registered before the replay is read, so nothing recorded in between is lost.
        subscribers.add(subscriber)
        try {
            val missed = if (after == null) emptyList() else store.eventsAfter(after)
            subscriber.startWith(missed)
        } catch (e: RuntimeException) {
            log.log("stream_subscribe_failed", mapOf("error" to e.javaClass.simpleName))
            subscriber.abort()
            throw e
        }
    }

    private inner class Subscriber(private val connection: SocketChannel, private val onClosed: () -> Unit) {
        private val queue = LinkedBlockingQueue<StoredEvent>()
        private val pending = mutableListOf<StoredEvent>()
        private var replaying = true
        private var lastQueued = Long.MIN_VALUE

        @Volatile private var dropped = false

        @Volatile var droppedAt: Instant = Instant.MAX
            private set

        @Volatile private var stopped = false

        private val finished = AtomicBoolean(false)

        @Volatile private var lastSent = 0L

        @Synchronized
        fun offer(event: StoredEvent) {
            if (dropped || stopped || event.seq <= lastQueued) return
            if (replaying) {
                pending.add(event)
                return
            }
            if (queue.size >= queueLimit) {
                droppedAt = clock.instant()
                slowDropped.add(this)
                dropped = true
                subscribers.remove(this)
                log.log("stream_subscriber_dropped", mapOf("seq" to lastSent.toString()))
                return
            }
            lastQueued = event.seq
            queue.add(event)
        }

        /** Queues the replay, then the live events that arrived meanwhile, and starts the writer. */
        fun startWith(missed: List<StoredEvent>) {
            synchronized(this) {
                missed.forEach { enqueueReplay(it) }
                pending.forEach { enqueueReplay(it) }
                pending.clear()
                replaying = false
            }
            Thread.ofVirtual().start { write() }
        }

        private fun enqueueReplay(event: StoredEvent) {
            if (event.seq <= lastQueued) return
            lastQueued = event.seq
            queue.add(event)
        }

        fun abort() {
            stopped = true
            finish()
        }

        private fun write() {
            try {
                val output = Channels.newOutputStream(connection)
                while (!stopped) {
                    val event = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    if (event != null) {
                        send(output, renderEvent(event))
                        lastSent = event.seq
                    } else if (dropped) {
                        send(output, renderStreamError("subscriber too slow"))
                        return
                    }
                }
            } catch (e: IOException) {
                log.log("stream_subscriber_gone", mapOf("seq" to lastSent.toString()))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                finish()
            }
        }

        private fun send(output: OutputStream, body: String) {
            output.write((body + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        /** Idempotent: the writer and [sweep] may both end the subscriber. */
        fun finish() {
            stopped = true
            subscribers.remove(this)
            slowDropped.remove(this)
            if (!finished.compareAndSet(false, true)) return
            runCatching { connection.close() }
            runCatching { onClosed() }
        }
    }

    companion object {
        const val DEFAULT_QUEUE_LIMIT = 1000
        val DEFAULT_CLOSE_GRACE: Duration = Duration.ofSeconds(5)
        private const val POLL_MILLIS = 50L
    }
}
