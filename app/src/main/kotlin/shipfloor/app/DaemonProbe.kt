package shipfloor.app

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface DaemonProbe {
    fun ping(): Boolean
}

enum class StartOutcome { Started, NotInstalled, Failed }

interface DaemonStarter {
    fun start(): StartOutcome
}

/** Sends `{"cmd":"ping"}` to the daemon's socket and accepts only an `ok:true` reply. */
class SocketDaemonProbe(private val socket: Path, private val timeoutMillis: Long = 2_000) : DaemonProbe {
    override fun ping(): Boolean {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX).also {
                try {
                    it.connect(UnixDomainSocketAddress.of(socket))
                } catch (e: IOException) {
                    it.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            return false
        }
        // A blocking read has no timeout of its own: closing the channel is what ends a daemon that never answers.
        val reply = CompletableFuture.supplyAsync {
            try {
                Channels.newOutputStream(channel).apply {
                    write("{\"cmd\":\"ping\"}\n".toByteArray())
                    flush()
                }
                Channels.newInputStream(channel).bufferedReader().readLine()
            } catch (e: IOException) {
                null
            }
        }
        return try {
            REPLY_OK.containsMatchIn(reply.get(timeoutMillis, TimeUnit.MILLISECONDS).orEmpty())
        } catch (e: TimeoutException) {
            false
        } catch (e: ExecutionException) {
            false
        } finally {
            channel.close()
        }
    }

    private companion object {
        val REPLY_OK = Regex(""""ok"\s*:\s*true""")
    }
}

/** Starts the user service `shipfloor.service` (the daemon must outlive the app, so it is not a child process). */
class SystemctlDaemonStarter(private val timeoutSeconds: Long = 15) : DaemonStarter {
    override fun start(): StartOutcome {
        val process = try {
            ProcessBuilder("systemctl", "--user", "start", "shipfloor.service").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return StartOutcome.Failed
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return StartOutcome.Failed
        }
        val output = process.inputStream.bufferedReader().readText()
        return when {
            process.exitValue() == 0 -> StartOutcome.Started
            process.exitValue() == EXIT_UNIT_NOT_FOUND || "not found" in output -> StartOutcome.NotInstalled
            else -> StartOutcome.Failed
        }
    }

    private companion object {
        const val EXIT_UNIT_NOT_FOUND = 5
    }
}
