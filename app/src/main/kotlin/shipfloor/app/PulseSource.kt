package shipfloor.app

import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import shipfloor.core.AssistantState
import shipfloor.core.ForecastGap
import shipfloor.core.QueueForecast

/** `factory.pulse`: the daemon's reading of the factory. */
fun interface PulseSource {
    fun pulse(): Pulse
}

/** `factory.pulse` over the daemon's socket; a reply that lacks a field is an error, never a made-up value. */
class SocketPulseSource(private val socket: Path) : PulseSource {
    override fun pulse(): Pulse {
        val reply = call(socket, buildJsonObject { put("cmd", "factory.pulse") })
        val result = reply["result"] as? JsonObject ?: throw IOException("daemon sent a pulse without result")
        val state = AssistantState.entries.firstOrNull { it.name == text(result, "state") }
            ?: throw IOException("daemon sent a pulse with an unknown state")
        val capacity = (result["poolCapacity"] as? JsonPrimitive)?.intOrNull
            ?: throw IOException("daemon sent a pulse without pool capacity")
        val active = (result["active"] as? JsonArray ?: throw IOException("daemon sent a pulse without active cards"))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val phrase = text(result, "phrase") ?: throw IOException("daemon sent a pulse without phrase")
        return Pulse(state, phrase, capacity, active, forecastOf(result["forecast"]))
    }

    /** An absent or unreadable forecast (an older daemon) is null, never a made-up time. */
    private fun forecastOf(element: JsonElement?): QueueForecast? {
        val forecast = element as? JsonObject ?: return null
        return when (text(forecast, "kind")) {
            "EMPTY" -> QueueForecast.Empty
            "FINISHES_AT" -> {
                val at = text(forecast, "at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
                val steps = (forecast["ownerSteps"] as? JsonPrimitive)?.intOrNull ?: 0
                QueueForecast.FinishesAt(at, steps)
            }
            "UNKNOWN" -> {
                val gap = ForecastGap.entries.firstOrNull { it.name == text(forecast, "gap") } ?: return null
                QueueForecast.Unknown(gap)
            }
            else -> null
        }
    }
}

/**
 * Keeps [view] for the strip: reads the pulse and today's cost; when either read fails the last reading stays,
 * marked stale. [refresh] is also called on every board event.
 */
class PulseMonitor(
    private val source: PulseSource,
    private val costSource: CostSummarySource,
    private val clock: Clock,
    private val zone: ZoneId,
    private val io: CoroutineDispatcher,
) {
    private val mutable = MutableStateFlow(PulseView.loading())
    val view: StateFlow<PulseView> = mutable

    suspend fun run() {
        while (true) {
            refresh()
            delay(EVERY_MILLIS)
        }
    }

    suspend fun refresh() {
        val next = withContext(io) {
            try {
                val today = clock.instant().atZone(zone).toLocalDate()
                val cost = costSource.summary(today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant())
                PulseView.of(source.pulse(), cost, clock.instant(), zone)
            } catch (e: IOException) {
                null
            }
        }
        mutable.value = next ?: mutable.value.markStale()
    }

    private companion object {
        const val EVERY_MILLIS = 5_000L
    }
}
