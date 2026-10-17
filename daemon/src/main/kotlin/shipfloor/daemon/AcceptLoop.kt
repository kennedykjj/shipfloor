package shipfloor.daemon

import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel

private const val INITIAL_BACKOFF_MILLIS = 10L
private const val MAX_BACKOFF_MILLIS = 1000L

/** Pause before retry number [attempt] (1-based): 10 ms doubling up to 1000 ms. */
internal fun acceptBackoffMillis(attempt: Int): Long {
    val shift = (attempt - 1).coerceIn(0, 20)
    return (INITIAL_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
}

/** Accepts clients until the channel closes, backing off when accept fails. */
internal class AcceptLoop(
    private val accept: () -> SocketChannel,
    private val log: DaemonLog,
    private val pause: (Long) -> Unit,
    private val onClient: (SocketChannel) -> Unit,
) {
    fun run() {
        var attempt = 0
        while (true) {
            val client = try {
                accept()
            } catch (e: ClosedChannelException) {
                return
            } catch (e: IOException) {
                attempt++
                log.log("accept_failed", mapOf("error" to e.javaClass.simpleName, "attempt" to attempt.toString()))
                pause(acceptBackoffMillis(attempt))
                continue
            }
            attempt = 0
            onClient(client)
        }
    }
}
