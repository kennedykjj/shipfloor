package shipfloor.app

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One event of the daemon's log, as `events.recent` and `events.subscribe` deliver it. */
data class FeedEvent(
    val seq: Long,
    val card: String,
    val id: String,
    val at: Instant,
    val actor: String,
    val type: String,
    val payload: Map<String, String> = emptyMap(),
)

data class RecentEvents(val events: List<FeedEvent>, val truncated: Boolean)

interface FeedListener {
    fun onEvent(event: FeedEvent)
    fun onClosed()
}

/** The daemon seam of the feed; production wraps the socket. */
interface FeedTransport {
    fun recent(limit: Int): RecentEvents
    fun subscribe(after: Long?, listener: FeedListener): AutoCloseable
}

data class CardCost(val card: String, val costUsd: BigDecimal?, val status: CostStatus)

data class StageCost(val stage: String, val costUsd: BigDecimal?, val status: CostStatus)

data class DayCost(val day: LocalDate, val costUsd: BigDecimal?, val status: CostStatus)

data class CostLimitsInfo(val perCardUsd: BigDecimal?, val perDayUsd: BigDecimal?)

/** [stages] and [days] stay empty when the daemon predates them. */
data class CostSummary(
    val totalUsd: BigDecimal?,
    val status: CostStatus,
    val cards: List<CardCost>,
    val limits: CostLimitsInfo,
    val dayReached: Boolean,
    val cardsReached: Set<String>,
    val stages: List<StageCost> = emptyList(),
    val days: List<DayCost> = emptyList(),
)

/** `cost.summary` for the instants [from, to). */
fun interface CostSummarySource {
    fun summary(from: Instant, to: Instant): CostSummary
}

enum class LimitState { NoLimit, Within, Reached }

enum class CostTag { Partial, Unknown }

/** [fraction] only sizes the bar (value over limit); whether the limit was reached is [limitState], from the daemon. */
data class CostLine(
    val label: String,
    val value: String,
    val tag: CostTag?,
    val limit: String?,
    val limitState: LimitState,
    val fraction: Double? = null,
)

/** The daemon's numbers, formatted. Nothing is computed here beyond ordering and the bar size. */
data class CostSummaryView(
    val total: CostLine,
    val cards: List<CostLine>,
    val explainsPartial: Boolean,
    val stages: List<CostLine> = emptyList(),
    val days: List<CostLine> = emptyList(),
) {
    companion object {
        private val symbols = DecimalFormatSymbols().apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
        private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

        fun of(summary: CostSummary): CostSummaryView {
            val limits = summary.limits
            val total = CostLine(
                label = Texts.COST_TOTAL,
                value = totalText(summary),
                tag = tagOf(summary.status),
                limit = limits.perDayUsd?.let(::money),
                limitState = stateOf(limits.perDayUsd, summary.dayReached),
                fraction = fractionOf(summary.totalUsd, limits.perDayUsd),
            )
            val cards = summary.cards
                .sortedByDescending { it.costUsd ?: BigDecimal.ONE.negate() }
                .map { card ->
                    CostLine(
                        label = "#${card.card}",
                        value = amountText(card.costUsd, card.status),
                        tag = tagOf(card.status),
                        limit = limits.perCardUsd?.let(::money),
                        limitState = stateOf(limits.perCardUsd, card.card in summary.cardsReached),
                        fraction = fractionOf(card.costUsd, limits.perCardUsd),
                    )
                }
            val stages = summary.stages
                .sortedByDescending { rankOf(it.costUsd, it.status) }
                .map { plainLine(it.stage, it.costUsd, it.status) }
            val days = if (summary.days.size > 1) {
                summary.days.map { plainLine(DAY_LABEL.format(it.day), it.costUsd, it.status) }
            } else {
                emptyList()
            }
            val statuses = listOf(summary.status) + summary.cards.map { it.status } +
                summary.stages.map { it.status } + summary.days.map { it.status }
            val partial = summary.status == CostStatus.PARTIAL ||
                statuses.any { it == CostStatus.PARTIAL || it == CostStatus.UNKNOWN }
            return CostSummaryView(total, cards, partial, stages, days)
        }

        fun loading(): CostSummaryView =
            of(CostSummary(null, CostStatus.NONE, emptyList(), CostLimitsInfo(null, null), false, emptySet()))

        private fun plainLine(label: String, usd: BigDecimal?, status: CostStatus) = CostLine(
            label = label,
            value = amountText(usd, status),
            tag = tagOf(status),
            limit = null,
            limitState = LimitState.NoLimit,
        )

        private fun rankOf(usd: BigDecimal?, status: CostStatus): BigDecimal =
            if (usd == null || status == CostStatus.UNKNOWN) BigDecimal.ONE.negate() else usd

        private fun totalText(summary: CostSummary): String {
            val amount = amountText(summary.totalUsd, summary.status)
            return if (summary.totalUsd == null || summary.status == CostStatus.UNKNOWN) amount else "$amount ${Texts.ESTIMATE_SUFFIX}"
        }

        internal fun amountText(usd: BigDecimal?, status: CostStatus): String =
            when {
                usd == null || status == CostStatus.UNKNOWN -> "US$ ?"
                status == CostStatus.PARTIAL -> "≥ ${money(usd)}"
                else -> money(usd)
            }

        internal fun tagOf(status: CostStatus): CostTag? =
            when (status) {
                CostStatus.PARTIAL -> CostTag.Partial
                CostStatus.UNKNOWN -> CostTag.Unknown
                CostStatus.KNOWN, CostStatus.NONE -> null
            }

        private fun stateOf(limit: BigDecimal?, reached: Boolean): LimitState =
            when {
                limit == null -> LimitState.NoLimit
                reached -> LimitState.Reached
                else -> LimitState.Within
            }

        private fun fractionOf(value: BigDecimal?, limit: BigDecimal?): Double? =
            if (value == null || limit == null || limit.signum() <= 0) null else value.divide(limit, 4, RoundingMode.HALF_UP).toDouble()

        fun money(usd: BigDecimal): String = "US$ " + DecimalFormat("#,##0.00", symbols).format(usd.setScale(2, RoundingMode.HALF_UP))
    }
}

enum class FeedConnection { Live, Reconnecting }

/** What the row's mark shows; the mark also has a shape, so color is never the only signal. */
enum class FeedOutcome { None, Ok, Failed }

data class FeedRow(
    val seq: Long,
    val time: String,
    val type: String,
    val actor: String,
    val card: String,
    val outcome: FeedOutcome = FeedOutcome.None,
    val actorKind: String = actor,
    val actorDetail: String? = null,
    val cost: String? = null,
    val costTag: CostTag? = null,
    val stage: String? = null,
)

/** `stage == null` is the "Todos" chip. */
data class StageChip(val stage: String?, val count: Int)

data class FeedState(
    val day: LocalDate,
    val rows: List<FeedRow>,
    val loading: Boolean,
    val connection: FeedConnection,
    val earlierHistoryUnavailable: Boolean,
    val cost: CostSummaryView,
    val failed: Boolean = false,
    val stages: List<StageChip> = listOf(StageChip(null, 0)),
    val stage: String? = null,
)

/**
 * Keeps [state] for the selected day: reads the last [WINDOW] events once, follows the stream from the last seq seen
 * and asks the daemon for the day's cost. Callbacks may arrive from the transport's thread, so every step is locked.
 */
class EventFeedClient(
    private val transport: FeedTransport,
    private val costSource: CostSummarySource,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(
        FeedState(LocalDate.now(clock.withZone(zone)), emptyList(), true, FeedConnection.Live, false, CostSummaryView.loading()),
    )
    val state: StateFlow<FeedState> = mutable

    private var window: List<FeedEvent> = emptyList()
    private var truncated = false
    private var lastSeq: Long? = null
    private var loaded = false
    private var subscription: AutoCloseable? = null
    private var day: LocalDate = mutable.value.day
    private var selectedStage: String? = null

    private val listener = object : FeedListener {
        override fun onEvent(event: FeedEvent) = receive(event)
        override fun onClosed() = dropped()
    }

    /** Reads the window and subscribes. A failed read leaves the feed Reconnecting; [reconnect] tries again. */
    fun open() {
        synchronized(lock) {
            val recent = try {
                transport.recent(WINDOW)
            } catch (e: IOException) {
                fail()
                return
            }
            window = recent.events
            truncated = recent.truncated
            lastSeq = recent.events.lastOrNull()?.seq
            loaded = true
            if (!subscribe()) return
            publish(loading = false, connection = FeedConnection.Live, failed = false)
        }
        refreshCost()
    }

    /** Subscribes again from the last seq seen; reads the window instead when it never loaded. */
    fun reconnect() {
        if (!synchronized(lock) { loaded }) {
            open()
            return
        }
        synchronized(lock) {
            if (!subscribe()) return
            publish(connection = FeedConnection.Live, failed = false)
        }
    }

    fun selectDay(day: LocalDate) {
        synchronized(lock) {
            this.day = day
            publish()
        }
        refreshCost()
    }

    /** Filters the loaded window by [stage] (`null` = Todos); no new read from the daemon. */
    fun selectStage(stage: String?) {
        synchronized(lock) {
            selectedStage = stage
            publish()
        }
    }

    fun close() {
        synchronized(lock) {
            subscription?.let { runCatching { it.close() } }
            subscription = null
        }
    }

    private fun subscribe(): Boolean {
        subscription?.let { runCatching { it.close() } }
        subscription = null
        return try {
            subscription = transport.subscribe(lastSeq, listener)
            true
        } catch (e: IOException) {
            fail()
            false
        }
    }

    private fun fail() {
        publish(loading = false, connection = FeedConnection.Reconnecting, failed = !loaded)
    }

    private fun receive(event: FeedEvent) {
        val refresh = synchronized(lock) {
            if (lastSeq?.let { event.seq <= it } == true) return
            lastSeq = event.seq
            window = window + event
            publish()
            event.type == COST_RECORDED
        }
        if (refresh) refreshCost()
    }

    private fun dropped() {
        synchronized(lock) {
            subscription = null
            publish(connection = FeedConnection.Reconnecting)
        }
    }

    private fun refreshCost() {
        val (from, to, selected) = synchronized(lock) {
            Triple(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(), day)
        }
        val view = try {
            CostSummaryView.of(costSource.summary(from, to))
        } catch (e: IOException) {
            return
        }
        synchronized(lock) {
            if (selected == day) mutable.value = mutable.value.copy(cost = view)
        }
    }

    private fun publish(loading: Boolean? = null, connection: FeedConnection? = null, failed: Boolean? = null) {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val dayRows = window.filter { it.at >= dayStart && it.at < dayEnd }.map(::rowOf)
        val counts = dayRows.mapNotNull { it.stage }.groupingBy { it }.eachCount()
        val chosen = selectedStage?.takeIf { it in counts }
        selectedStage = chosen
        val rows = if (chosen == null) dayRows else dayRows.filter { it.stage == chosen }
        val oldest = window.firstOrNull()
        val current = mutable.value
        mutable.value = current.copy(
            day = day,
            rows = rows,
            stages = listOf(StageChip(null, dayRows.size)) + counts.map { (stage, count) -> StageChip(stage, count) },
            stage = chosen,
            loading = loading ?: current.loading,
            connection = connection ?: current.connection,
            failed = failed ?: current.failed,
            earlierHistoryUnavailable = truncated && oldest != null && oldest.at > dayStart,
        )
    }

    private fun rowOf(event: FeedEvent): FeedRow {
        val kind = event.actor.substringBefore(':')
        val detail = event.actor.substringAfter(':', "").ifEmpty { null }
        val kindText = ACTOR_KINDS[kind]
        val recorded = event.type == COST_RECORDED
        val status = if (recorded) costStatusOf(event.payload["status"]) else CostStatus.NONE
        val amount = if (recorded) event.payload["cost_usd"]?.toBigDecimalOrNull() else null
        val readable = recorded && amount != null && status != CostStatus.NONE
        val costText = when {
            !recorded -> null
            !readable || status == CostStatus.UNKNOWN -> CostSummaryView.amountText(null, CostStatus.UNKNOWN)
            else -> "${CostSummaryView.amountText(amount, status)} ${Texts.ESTIMATE_SUFFIX}"
        }
        return FeedRow(
            seq = event.seq,
            time = TIME.format(event.at.atZone(zone)),
            type = event.type,
            actor = event.actor,
            card = "#${event.card}",
            outcome = outcomeOf(event.type),
            actorKind = kindText ?: event.actor,
            actorDetail = if (kindText != null) detail else null,
            cost = costText,
            costTag = if (recorded) CostSummaryView.tagOf(if (readable) status else CostStatus.UNKNOWN) else null,
            stage = event.payload["stage"],
        )
    }

    private fun costStatusOf(raw: String?): CostStatus =
        CostStatus.entries.firstOrNull { it.name == raw } ?: CostStatus.NONE

    private fun outcomeOf(type: String): FeedOutcome =
        when {
            FAILURE_WORDS.any { it in type } -> FeedOutcome.Failed
            SUCCESS_WORDS.any { type.endsWith(it) } -> FeedOutcome.Ok
            else -> FeedOutcome.None
        }

    private companion object {
        const val WINDOW = 2000
        const val COST_RECORDED = "RUN_COST_RECORDED"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val ACTOR_KINDS = mapOf(
            "owner" to Texts.ACTOR_OWNER,
            "agent" to Texts.ACTOR_AGENT,
            "factory" to Texts.ACTOR_FACTORY,
            "adapter" to Texts.ACTOR_ADAPTER,
        )
        val FAILURE_WORDS = listOf("FAILED", "ERROR", "REJECTED")
        val SUCCESS_WORDS = listOf("COMPLETED", "FINISHED", "PASSED", "APPROVED", "MERGED")
    }
}
