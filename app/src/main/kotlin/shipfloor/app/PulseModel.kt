package shipfloor.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import shipfloor.core.AssistantState
import shipfloor.core.ForecastGap
import shipfloor.core.QueueForecast

/**
 * The daemon's `factory.pulse` reading. [active] holds card labels (`#n` or 8 hex), never titles. [forecast] is
 * null when the daemon sent none (an older daemon).
 */
data class Pulse(
    val state: AssistantState,
    val phrase: String,
    val poolCapacity: Int,
    val active: List<String>,
    val forecast: QueueForecast? = null,
)

/** The "Previsão" cell: [headline], an optional [secondary] line and the full sentence for accessibility. */
data class ForecastCell(val headline: String, val secondary: String?, val description: String)

object ForecastCells {
    private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

    fun of(forecast: QueueForecast?, now: Instant, zone: ZoneId): ForecastCell =
        when (forecast) {
            null -> cell(PulseTexts.NO_FORECAST, null)
            QueueForecast.Empty -> cell(PulseTexts.QUEUE_EMPTY, null)
            is QueueForecast.Unknown -> cell(PulseTexts.NO_FORECAST, PulseTexts.gap(forecast.gap))
            is QueueForecast.FinishesAt -> {
                val at = forecast.at.atZone(zone)
                val hour = HOUR.format(at)
                val moment = if (at.toLocalDate().isAfter(now.atZone(zone).toLocalDate())) "${PulseTexts.TOMORROW} $hour" else hour
                cell("${PulseTexts.QUEUE_EMPTY} ~$moment", PulseTexts.ownerSteps(forecast.ownerSteps))
            }
        }

    private fun cell(headline: String, secondary: String?): ForecastCell =
        ForecastCell(headline, secondary, listOfNotNull(PulseTexts.FORECAST_TITLE, headline, secondary).joinToString(", "))
}

enum class PulseFreshness { Loading, Live, Stale }

sealed interface PulseCeiling {
    data class Unlimited(val text: String) : PulseCeiling

    /** [percent] is spent over the day limit, rounded; it may pass 100. */
    data class Meter(val spent: String, val percent: Int, val limitState: LimitState) : PulseCeiling
}

internal object PulseTexts {
    const val TITLE = "A fábrica diz"
    const val NOW = "Agora"
    const val CEILING_TITLE = "Teto do dia"
    const val LOADING = "Lendo a fábrica…"
    const val STALE = "Leitura desatualizada: o daemon não respondeu."
    const val NO_CEILING = "sem teto"
    const val NO_CARDS = "nenhum card em execução"

    fun state(state: AssistantState): String =
        when (state) {
            AssistantState.IDLE -> "▸ ocioso"
            AssistantState.WORKING -> "▸ trabalhando"
            AssistantState.WAITING_OWNER -> "▸ esperando você"
            AssistantState.BLOCKED -> "▸ bloqueado"
            AssistantState.NIGHT -> "▸ descansando"
            AssistantState.TIRED -> "▸ cansado"
        }

    const val FORECAST_TITLE = "Previsão"
    const val FORECAST_BASELINE = "baseline"
    const val QUEUE_EMPTY = "Fila vazia"
    const val TOMORROW = "amanhã"
    const val NO_FORECAST = "sem previsão"

    fun gap(gap: ForecastGap): String =
        when (gap) {
            ForecastGap.NO_POOL -> "sem sessões no pool"
            ForecastGap.NO_HISTORY -> "sem histórico de tempo"
        }

    fun ownerSteps(steps: Int): String? =
        when {
            steps <= 0 -> null
            steps == 1 -> "O dono segura 1 passo"
            else -> "O dono segura $steps passos"
        }

    fun sessions(used: Int, total: Int): String = "$used de $total sessões"

    fun meter(percent: Int): String = "$percent% do teto do dia"
}

/**
 * What the strip shows. Words, squares (`true` = in use, one per pool slot) and the meter come straight from the
 * daemon's numbers; nothing is invented before a reading arrives.
 */
data class PulseView(
    val freshness: PulseFreshness,
    val stateText: String?,
    val phrase: String?,
    val sessionsText: String?,
    val squares: List<Boolean>,
    val activeCards: List<String>,
    val ceiling: PulseCeiling?,
    val notice: String?,
    val forecast: ForecastCell? = null,
) {
    /** Keeps the last reading and says it is out of date. */
    fun markStale(): PulseView = copy(freshness = PulseFreshness.Stale, notice = PulseTexts.STALE)

    companion object {
        fun loading(): PulseView = PulseView(PulseFreshness.Loading, null, null, null, emptyList(), emptyList(), null, null)

        fun of(pulse: Pulse, cost: CostSummary, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): PulseView {
            val capacity = pulse.poolCapacity.coerceAtLeast(0)
            val used = pulse.active.size.coerceAtMost(capacity)
            return PulseView(
                freshness = PulseFreshness.Live,
                stateText = PulseTexts.state(pulse.state),
                phrase = pulse.phrase,
                sessionsText = PulseTexts.sessions(pulse.active.size, capacity),
                squares = List(capacity) { it < used },
                activeCards = pulse.active,
                ceiling = ceilingOf(cost),
                notice = null,
                forecast = ForecastCells.of(pulse.forecast, now, zone),
            )
        }

        private fun ceilingOf(cost: CostSummary): PulseCeiling {
            val limit = cost.limits.perDayUsd?.takeIf { it.signum() > 0 } ?: return PulseCeiling.Unlimited(PulseTexts.NO_CEILING)
            val spent = cost.totalUsd
            val percent = spent?.multiply(HUNDRED)?.divide(limit, 0, RoundingMode.HALF_UP)?.toInt() ?: 0
            val text = spent?.let { "${Texts.ESTIMATE_SUFFIX} ${CostSummaryView.money(it)}" } ?: "${Texts.ESTIMATE_SUFFIX} US$ ?"
            return PulseCeiling.Meter(text, percent, if (cost.dayReached) LimitState.Reached else LimitState.Within)
        }

        private val HUNDRED = BigDecimal(100)
    }
}
