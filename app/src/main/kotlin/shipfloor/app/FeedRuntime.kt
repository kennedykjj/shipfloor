package shipfloor.app

import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Drives an [EventFeedClient] against the daemon's socket: opens it, then retries while the feed is reconnecting. */
class FeedRuntime(socket: Path, val clock: Clock, val zone: ZoneId) {
    val client = EventFeedClient(SocketFeedTransport(socket), SocketCostSummarySource(socket, zone), clock, zone)

    /** Runs until the coroutine is cancelled; the socket calls block, so they run off the caller. */
    suspend fun run() {
        try {
            withContext(Dispatchers.IO) { client.open() }
            while (coroutineContext.isActive) {
                delay(RETRY_INTERVAL_MILLIS)
                if (client.state.value.connection == FeedConnection.Reconnecting) {
                    withContext(Dispatchers.IO) { client.reconnect() }
                }
            }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val RETRY_INTERVAL_MILLIS = 2_000L
    }
}
