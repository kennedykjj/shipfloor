package shipfloor.e2e

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.cli.runCli
import shipfloor.daemon.DaemonLaunch
import shipfloor.daemon.launchDaemon

class CardsCliTest {
    @TempDir
    lateinit var home: Path

    private val env: Map<String, String>
        get() = mapOf(
            "XDG_RUNTIME_DIR" to home.resolve("run").toString(),
            "XDG_DATA_HOME" to home.resolve("data").toString(),
        )

    private fun startDaemon(): DaemonLaunch.Started {
        Files.createDirectories(home.resolve("run"))
        val launch = launchDaemon(env, PrintStream(ByteArrayOutputStream()))
        return launch as? DaemonLaunch.Started ?: error("daemon did not start: $launch")
    }

    private fun cli(vararg args: String): String {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runCli(args.toList(), "test", { out.appendLine(it) }, { err.appendLine(it) }, env = env)
        assertThat(code).describedAs("exit of ${args.toList()}: $err").isZero()
        return out.toString()
    }

    private fun subscribe(): Pair<SocketChannel, BufferedReader> {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(home.resolve("run/shipfloor/daemon.sock")))
        Channels.newOutputStream(channel).apply {
            write("{\"cmd\":\"events.subscribe\"}\n".toByteArray())
            flush()
        }
        val reader = Channels.newInputStream(channel).bufferedReader()
        assertThat(reader.readLine()).contains("subscribed")
        return channel to reader
    }

    @Test
    fun `AC-3 history survives a daemon restart and two clients get the same event`() {
        val columns = listOf(
            "ready-for-refinement", "refinement", "spec-review", "ready-for-dev",
            "coding", "validation", "staged", "ready-for-prod", "done",
        )
        var daemon = startDaemon()
        val (channelA, readerA) = subscribe()
        val (channelB, readerB) = subscribe()

        val created = cli("card", "create", "Ship it")
        val id = created.trim().substringBefore(" ")
        val lineA = CompletableFuture.supplyAsync { readerA.readLine() }.get(10, TimeUnit.SECONDS)
        val lineB = CompletableFuture.supplyAsync { readerB.readLine() }.get(10, TimeUnit.SECONDS)
        assertThat(lineA).isEqualTo(lineB).contains("CARD_CREATED").contains(id)
        channelA.close()
        channelB.close()

        columns.forEach { cli("card", "move", id, it) }
        val historyBefore = cli("events", "--after", "0", "--count", "10")
        assertThat(historyBefore.lines().filter { it.isNotBlank() }).hasSize(10)

        daemon.server.close()
        daemon = startDaemon()
        try {
            assertThat(cli("events", "--after", "0", "--count", "10")).isEqualTo(historyBefore)
            assertThat(cli("card", "show", id).trim()).isEqualTo("$id  DONE  Ship it")
            assertThat(cli("card", "list").trim()).isEqualTo("$id  DONE  Ship it")
        } finally {
            daemon.server.close()
        }
    }
}
