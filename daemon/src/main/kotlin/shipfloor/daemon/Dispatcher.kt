package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import shipfloor.core.Column
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.StoredCard
import shipfloor.store.StoredEvent

enum class DispatchStage(val column: Column) {
    REFINE(Column.READY_FOR_REFINEMENT),
    DEVELOP(Column.READY_FOR_DEV),
    VALIDATE(Column.VALIDATION),
}

/** A started run: its pid and the start instant of the process itself (what [ProcessProbe] compares). */
data class LaunchedRun(val pid: Long, val startedAt: Instant)

fun interface StageLauncher {
    fun launch(stage: DispatchStage, cardId: String): LaunchedRun
}

/** Delegates each stage to its own launcher; a stage without one is a wiring error. */
class StageRouter(private val launchers: Map<DispatchStage, StageLauncher>) : StageLauncher {
    override fun launch(stage: DispatchStage, cardId: String): LaunchedRun {
        val launcher = requireNotNull(launchers[stage]) { "no launcher for stage $stage (card $cardId)" }
        return launcher.launch(stage, cardId)
    }
}

fun interface PauseSwitch {
    fun isPaused(): Boolean
}

/** Paused while the file exists. */
class FilePauseSwitch(private val path: Path) : PauseSwitch {
    override fun isPaused(): Boolean = Files.exists(path)
}

fun interface WorkSchedule {
    fun isWorkTime(now: Instant): Boolean
}

object AlwaysWorkTime : WorkSchedule {
    override fun isWorkTime(now: Instant): Boolean = true
}

/** WIP per stage; absent or 0 means no limit. */
data class DispatchLimits(val wip: Map<DispatchStage, Int>)

data class DispatchedRun(val stage: DispatchStage, val cardId: String)

class Dispatcher(
    private val store: CardEventStore,
    private val pool: RunPool,
    private val launcher: StageLauncher,
    private val pause: PauseSwitch,
    private val schedule: WorkSchedule,
    private val limits: DispatchLimits,
    private val clock: Clock,
    private val tick: Duration,
    private val log: DaemonLog = StderrDaemonLog,
    private val launchGuard: LaunchGuard = LaunchGuard.Open,
    private val stages: Set<DispatchStage> = DispatchStage.entries.toSet(),
    private val deliveries: DeliveryTrigger = DeliveryTrigger { },
) : AutoCloseable {
    private data class Backoff(val retryAt: Instant, val delay: Duration)

    private val running = mutableMapOf<String, DispatchStage>()
    private val backoffs = mutableMapOf<String, Backoff>()
    private val signal = ArrayBlockingQueue<Boolean>(1)

    @Volatile
    private var closed = false
    private var thread: Thread? = null

    @Synchronized
    fun runCycle(): List<DispatchedRun> {
        // Frees the locks of runs that ended before anything else: a dead run must not hold a slot, paused or not.
        pool.sweep()
        if (pause.isPaused()) return emptyList()
        val now = clock.instant()
        if (!schedule.isWorkTime(now)) return emptyList()
        if (launchGuard.dayLimitReached()) return emptyList()
        val active = pool.active().map { it.cardId }.toSet()
        running.keys.retainAll(active)
        store.cards().filter { it.card.column == Column.READY_FOR_PROD }.forEach { deliveries.trigger(it.id) }
        val started = mutableListOf<DispatchedRun>()
        for (stage in DispatchStage.entries.filter { it in stages }) {
            val limit = limits.wip[stage] ?: 0
            for (stored in store.cards().filter { waitsFor(stage, it) }) {
                if (!pool.hasRoom()) return started
                if (limit > 0 && running.values.count { it == stage } >= limit) break
                val cardId = stored.id
                if (pool.isLocked(cardId)) continue
                if (!launchGuard.allows(cardId)) continue
                if (backoffs[cardId]?.let { now.isBefore(it.retryAt) } == true) continue
                if (launch(stage, cardId, now)) started.add(DispatchedRun(stage, cardId))
            }
        }
        return started
    }

    private fun waitsFor(stage: DispatchStage, stored: StoredCard): Boolean =
        stored.card.column == stage.column || (stage == DispatchStage.DEVELOP && returnedForRework(stored))

    /**
     * A card the validation sent back to Coding waits for a new develop run. It is picked until a session of that run
     * records its cost, so a run that cannot even start does not loop on the card.
     */
    private fun returnedForRework(stored: StoredCard): Boolean {
        if (stored.card.column != Column.CODING) return false
        val events = store.events(stored.id)
        val moveIndex = events.indexOfLast { it.type == EventType.CARD_MOVED }
        if (moveIndex < 0) return false
        val move = events[moveIndex]
        if (move.payload["to"] != Column.CODING.name || move.payload["from"] !in REWORK_ORIGINS) return false
        return events.drop(moveIndex + 1).none { it.type == EventType.RUN_COST_RECORDED }
    }

    private fun launch(stage: DispatchStage, cardId: String, now: Instant): Boolean {
        val run = try {
            launcher.launch(stage, cardId)
        } catch (e: RuntimeException) {
            val delay = backoffs[cardId]?.let { minOf(it.delay.multipliedBy(2), MAX_BACKOFF) } ?: MIN_BACKOFF
            backoffs[cardId] = Backoff(now.plus(delay), delay)
            log.log("stage_launch_failed", mapOf("card" to cardId, "stage" to stage.name))
            return false
        }
        val decision = pool.tryStart(cardId, run.pid, run.startedAt)
        if (decision !is StartDecision.Started) {
            log.log("stage_lock_refused", mapOf("card" to cardId, "stage" to stage.name, "pid" to run.pid.toString()))
            return false
        }
        backoffs.remove(cardId)
        running[cardId] = stage
        log.log("stage_started", mapOf("card" to cardId, "stage" to stage.name, "pid" to run.pid.toString()))
        return true
    }

    /** Only signals the loop; takes no lock. */
    fun wake() {
        signal.offer(true)
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread(::loop, "dispatcher").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        closed = true
        wake()
        val t = synchronized(this) { thread.also { thread = null } }
        t?.join(JOIN_MILLIS)
    }

    fun onEvent(event: StoredEvent) {
        if (event.event.type == EventType.CARD_MOVED) wake()
    }

    private fun loop() {
        while (!closed) {
            try {
                runCycle()
            } catch (e: RuntimeException) {
                log.log("dispatch_cycle_failed", emptyMap())
            }
            try {
                signal.poll(tick.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        val MIN_BACKOFF: Duration = Duration.ofSeconds(60)
        val MAX_BACKOFF: Duration = Duration.ofMinutes(30)
        const val JOIN_MILLIS = 5_000L
        val REWORK_ORIGINS = setOf(Column.VALIDATION.name, Column.STAGED.name)
    }
}
