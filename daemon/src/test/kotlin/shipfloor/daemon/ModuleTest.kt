package shipfloor.daemon

import org.assertj.core.api.Assertions.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModuleTest {

    @TempDir
    lateinit var runtimeDir: Path

    private fun launch(env: Map<String, String>): Pair<DaemonLaunch, String> {
        val buffer = ByteArrayOutputStream()
        val launch = PrintStream(buffer, true, Charsets.UTF_8).use { launchDaemon(env, it) }
        return launch to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `AC-5 hardening launch with a daemon already running fails with code 1 and a stderr message`() {
        val env = mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString())
        val (first, _) = launch(env)
        val running = (first as DaemonLaunch.Started).server
        try {
            val (second, err) = launch(env)

            assertThat(second).isEqualTo(DaemonLaunch.Failed(exitCode = 1))
            assertThat(err).isNotBlank()
        } finally {
            running.close()
        }
    }

    @Test
    fun `AC-6 hardening launch without XDG_RUNTIME_DIR fails with code 1 and a stderr message`() {
        val (launch, err) = launch(emptyMap())

        assertThat(launch).isEqualTo(DaemonLaunch.Failed(exitCode = 1))
        assertThat(err).contains("XDG_RUNTIME_DIR")
    }

    @Test
    fun `AC-7 hardening launch logic returns a started server instead of exiting the process`() {
        val (launch, err) = launch(mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString()))

        val started = launch as DaemonLaunch.Started
        try {
            assertThat(Files.exists(resolveSocketPath(mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString())))).isTrue()
            assertThat(err).isEmpty()
        } finally {
            started.server.close()
        }
    }

    @Test
    fun `module is named after its Gradle project`() {
        assertThat(MODULE_NAME).isEqualTo("daemon")
    }

    @Test
    fun `AC-7 tcp without SHIPFLOOR_TCP_PORT opens no listener and creates neither tcp token nor tcp port`() {
        val withoutTcp = runtimeDir.resolve("plain")
        val withTcp = runtimeDir.resolve("opt-in")
        val plainEnv = mapOf("XDG_RUNTIME_DIR" to withoutTcp.toString())
        val tcpEnv = mapOf("XDG_RUNTIME_DIR" to withTcp.toString(), "SHIPFLOOR_TCP_PORT" to "0")

        val (plain, plainErr) = launch(plainEnv)
        val (optIn, optInErr) = launch(tcpEnv)

        val plainServer = (plain as DaemonLaunch.Started).server
        val tcpServer = (optIn as DaemonLaunch.Started).server
        try {
            val plainDir = resolveSocketPath(plainEnv).parent
            val tcpDir = resolveSocketPath(tcpEnv).parent
            assertThat(plainErr).isEmpty()
            assertThat(optInErr).isEmpty()
            assertThat(Files.exists(resolveSocketPath(plainEnv))).isTrue()
            assertThat(Files.exists(plainDir.resolve("tcp.token"))).isFalse()
            assertThat(Files.exists(plainDir.resolve("tcp.port"))).isFalse()
            // Control: the same launch with the variable set does create both files.
            assertThat(Files.exists(tcpDir.resolve("tcp.token"))).isTrue()
            assertThat(Files.readString(tcpDir.resolve("tcp.port")).trim().toInt()).isGreaterThan(0)
        } finally {
            plainServer.close()
            tcpServer.close()
        }
    }

    @Test
    fun `AC-1 tcp a SHIPFLOOR_TCP_PORT that is not a port fails the launch naming the variable`() {
        for (value in listOf("abc", "-1", "65536", "80.5")) {
            val env = mapOf("XDG_RUNTIME_DIR" to runtimeDir.resolve("bad-$value").toString(), "SHIPFLOOR_TCP_PORT" to value)

            val (launch, err) = launch(env)

            assertThat(launch).isEqualTo(DaemonLaunch.Failed(exitCode = 1))
            assertThat(err).contains("SHIPFLOOR_TCP_PORT")
            assertThat(Files.exists(resolveSocketPath(env).resolveSibling("tcp.token"))).isFalse()
        }
    }
}
