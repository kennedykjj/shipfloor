package shipfloor.daemon

import java.time.Clock
import java.time.Instant
import java.util.UUID
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore

data class RunLock(val cardId: String, val pid: Long, val startedAt: Instant)

sealed interface StartDecision {
    data class Started(val lock: RunLock) : StartDecision
    data class PoolFull(val capacity: Int) : StartDecision
    data class CardLocked(val holder: RunLock) : StartDecision
}

/** Bounded set of run slots with one lock per card; locks of dead owners are released by [sweep]. */
class RunPool(
    private val capacity: () -> Int,
    private val store: CardEventStore,
    private val clock: Clock,
    private val probe: ProcessProbe,
    private val log: DaemonLog = StderrDaemonLog,
    private val recorder: EventRecorder,
) {
    /** A pool with a fixed [capacity]. */
    constructor(
        capacity: Int,
        store: CardEventStore,
        clock: Clock,
        probe: ProcessProbe,
        log: DaemonLog = StderrDaemonLog,
        recorder: EventRecorder,
    ) : this({ capacity }, store, clock, probe, log, recorder)

    private val locks = linkedMapOf<String, RunLock>()

    init {
        require(capacity() > 0) { "pool capacity must be positive, got ${capacity()}" }
    }

    @Synchronized
    fun tryStart(cardId: String, pid: Long, startedAt: Instant): StartDecision {
        locks[cardId]?.let { return StartDecision.CardLocked(it) }
        val limit = capacity()
        if (locks.size >= limit) return StartDecision.PoolFull(limit)
        val lock = RunLock(cardId, pid, startedAt)
        locks[cardId] = lock
        return StartDecision.Started(lock)
    }

    @Synchronized
    fun release(cardId: String) {
        locks.remove(cardId)
    }

    @Synchronized
    fun sweep(): List<RunLock> {
        val dead = locks.values.filter { !probe.isAlive(it.pid, it.startedAt) }
        return dead.filter { lock ->
            try {
                recordRelease(lock)
            } catch (e: RuntimeException) {
                log.log("lock_release_failed", mapOf("card" to lock.cardId, "pid" to lock.pid.toString()))
                return@filter false
            }
            locks.remove(lock.cardId)
            true
        }
    }

    @Synchronized
    fun isLocked(cardId: String): Boolean = cardId in locks

    @Synchronized
    fun hasRoom(): Boolean = locks.size < capacity()

    @Synchronized
    fun active(): List<RunLock> = locks.values.toList()

    private fun recordRelease(lock: RunLock) {
        val card = store.card(lock.cardId)
        if (card == null) {
            log.log("lock_release_orphan_card", mapOf("card" to lock.cardId, "pid" to lock.pid.toString()))
            return
        }
        val event = Event(
            id = EventId(UUID.randomUUID().toString()),
            at = clock.instant(),
            actor = Actor.Factory("pool"),
            type = EventType.LOCK_RELEASED,
            payload = mapOf("reason" to "owner_dead", "pid" to lock.pid.toString()),
        )
        recorder.record(lock.cardId, card, event)
        log.log("lock_released", mapOf("card" to lock.cardId, "pid" to lock.pid.toString()))
    }
}
