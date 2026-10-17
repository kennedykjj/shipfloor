package shipfloor.app

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Shape the implementation must follow (deterministic, no sleeps, no real clock):
 *
 * - `ConnectionMonitor(probe: DaemonProbe, starter: DaemonStarter, clock: Clock)`.
 * - `val state: StateFlow<ConnectionState>` starts as `Connecting`.
 * - `fun tick()` performs one step of work based on `clock.instant()`; the production loop
 *   just calls `tick()` repeatedly. The first `tick()` is "the app opened": it pings; if the ping fails it
 *   calls `starter.start()` exactly once (NotInstalled -> SERVICE_NOT_INSTALLED, Failed -> START_FAILED)
 *   and stays `Connecting`, pinging on each `tick()` until 10 s after the start; no answer by then ->
 *   `Disconnected(START_TIMED_OUT)`. Once running, a `tick()` pings only when at least 3 s passed
 *   since the last ping; a failed ping after being connected -> `Disconnected(NOT_RESPONDING)`;
 *   a later successful ping -> `Connected` again, without a new `start()`.
 */
class ConnectionMonitorTest {
    @Test
    fun `AC-1 shows connected once the first ping succeeds`() {
        val fx = ConnectionFixtures()
        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connecting)

        fx.monitor.tick()

        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(fx.starter.starts).isZero()
    }

    @Test
    fun `AC-2 goes disconnected when the daemon stops and keeps running`() {
        val fx = ConnectionFixtures()
        fx.monitor.tick()

        fx.probe.alive = false
        fx.advance(Duration.ofSeconds(3))
        fx.monitor.tick()

        assertThat(fx.monitor.state.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.NOT_RESPONDING))
    }

    @Test
    fun `AC-3 returns to connected when the daemon comes back`() {
        val fx = ConnectionFixtures()
        fx.monitor.tick()
        fx.probe.alive = false
        fx.advance(Duration.ofSeconds(3))
        fx.monitor.tick()

        fx.probe.alive = true
        fx.advance(Duration.ofSeconds(3))
        fx.monitor.tick()

        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(fx.starter.starts).isZero()
    }

    @Test
    fun `AC-4 starts the service once and connects when the daemon answers`() {
        val fx = ConnectionFixtures(alive = false, outcome = StartOutcome.Started)

        fx.monitor.tick()
        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connecting)
        assertThat(fx.starter.starts).isEqualTo(1)

        fx.advance(Duration.ofSeconds(2))
        fx.monitor.tick()
        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connecting)

        fx.probe.alive = true
        fx.advance(Duration.ofSeconds(2))
        fx.monitor.tick()

        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(fx.starter.starts).isEqualTo(1)
    }

    @Test
    fun `AC-5 explains a service that is not installed`() {
        val fx = ConnectionFixtures(alive = false, outcome = StartOutcome.NotInstalled)

        fx.monitor.tick()

        assertThat(fx.monitor.state.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.SERVICE_NOT_INSTALLED))
    }

    @Test
    fun `AC-5 explains a start that failed`() {
        val fx = ConnectionFixtures(alive = false, outcome = StartOutcome.Failed)

        fx.monitor.tick()

        assertThat(fx.monitor.state.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.START_FAILED))
    }

    @Test
    fun `AC-5 explains a start that never answers within the deadline`() {
        val fx = ConnectionFixtures(alive = false, outcome = StartOutcome.Started)
        fx.monitor.tick()
        fx.advance(Duration.ofSeconds(5))
        fx.monitor.tick()
        assertThat(fx.monitor.state.value).isEqualTo(ConnectionState.Connecting)

        fx.advance(Duration.ofSeconds(6))
        fx.monitor.tick()

        assertThat(fx.monitor.state.value)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.START_TIMED_OUT))
        assertThat(fx.starter.starts).isEqualTo(1)
    }
}

class MutableClock(private var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

class FakeProbe(var alive: Boolean) : DaemonProbe {
    override fun ping(): Boolean = alive
}

class FakeStarter(private val outcome: StartOutcome) : DaemonStarter {
    var starts = 0
        private set

    override fun start(): StartOutcome {
        starts += 1
        return outcome
    }
}

class ConnectionFixtures(alive: Boolean = true, outcome: StartOutcome = StartOutcome.Started) {
    val clock = MutableClock(Instant.parse("2026-10-16T12:00:00Z"))
    val probe = FakeProbe(alive)
    val starter = FakeStarter(outcome)
    val monitor = ConnectionMonitor(probe, starter, clock)

    fun advance(by: Duration) = clock.advance(by)
}
