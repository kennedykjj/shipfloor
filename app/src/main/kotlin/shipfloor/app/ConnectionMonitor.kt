package shipfloor.app

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Keeps [state] in step with the daemon. Each [tick] is one synchronous step driven by [clock]:
 * the first one pings and, if nobody answers, starts the service once; while it is starting every tick pings
 * until [START_DEADLINE]; afterwards it pings every [PING_INTERVAL].
 */
class ConnectionMonitor(
    private val probe: DaemonProbe,
    private val starter: DaemonStarter,
    private val clock: Clock,
) {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = mutableState

    private var opened = false
    private var startedAt: Instant? = null
    private var lastPing: Instant? = null

    fun tick() {
        val now = clock.instant()
        when {
            !opened -> open(now)
            mutableState.value == ConnectionState.Connecting -> waitForStart(now)
            else -> poll(now)
        }
    }

    /** Runs [tick] every [TICK_INTERVAL] until the coroutine is cancelled; the probes block, so they run off the caller. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            withContext(Dispatchers.IO) { tick() }
            delay(TICK_INTERVAL.toMillis())
        }
    }

    private fun open(now: Instant) {
        opened = true
        lastPing = now
        if (probe.ping()) {
            mutableState.value = ConnectionState.Connected
            return
        }
        when (starter.start()) {
            StartOutcome.Started -> startedAt = now
            StartOutcome.NotInstalled -> mutableState.value = ConnectionState.Disconnected(DisconnectReason.SERVICE_NOT_INSTALLED)
            StartOutcome.Failed -> mutableState.value = ConnectionState.Disconnected(DisconnectReason.START_FAILED)
        }
    }

    private fun waitForStart(now: Instant) {
        lastPing = now
        if (probe.ping()) {
            mutableState.value = ConnectionState.Connected
            return
        }
        val since = startedAt ?: now
        if (Duration.between(since, now) >= START_DEADLINE) {
            mutableState.value = ConnectionState.Disconnected(DisconnectReason.START_TIMED_OUT)
        }
    }

    private fun poll(now: Instant) {
        val last = lastPing
        if (last != null && Duration.between(last, now) < PING_INTERVAL) return
        lastPing = now
        val current = mutableState.value
        when {
            probe.ping() -> mutableState.value = ConnectionState.Connected
            current == ConnectionState.Connected -> mutableState.value = ConnectionState.Disconnected(DisconnectReason.NOT_RESPONDING)
        }
    }

    companion object {
        val START_DEADLINE: Duration = Duration.ofSeconds(10)
        val PING_INTERVAL: Duration = Duration.ofSeconds(3)
        private val TICK_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
