package shipfloor.app

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject

/** Returns the daemon's `card.detail` reply line; throws [IOException] when the daemon cannot be reached. */
fun interface CardDetailSource {
    fun fetch(id: String): String
}

enum class CostStatus { KNOWN, PARTIAL, UNKNOWN, NONE }

data class SpecView(val status: String, val text: String)

data class TimelineEvent(val id: String, val at: Instant, val actor: String, val type: String, val payload: Map<String, String>)

data class RunRow(
    val runId: String,
    val stage: String,
    val runtime: String,
    val status: CostStatus,
    val costUsd: BigDecimal?,
    val reason: String?,
)

data class CostTotal(val status: CostStatus, val costUsd: BigDecimal?)

/** Latest result of one gate; [durationMs] is null when the gate ran no process. */
data class GateRow(val name: String, val verdict: String, val reason: String?, val durationMs: Long?, val at: Instant)

/** One acceptance criterion linked to its layer's test file; [gate] is `PASS`, `FAIL`, `none` or `manual`. */
data class CriterionRow(val ac: String, val layer: String, val testFile: String, val gate: String)

data class CardDetail(
    val id: String,
    val title: String,
    val column: String,
    val spec: SpecView,
    val events: List<TimelineEvent>,
    val eventsTruncated: Boolean,
    val runs: List<RunRow>,
    val total: CostTotal,
    val gates: List<GateRow> = emptyList(),
    val criteria: List<CriterionRow> = emptyList(),
)

sealed interface CardDetailState {
    data object Loading : CardDetailState
    data class Loaded(val detail: CardDetail) : CardDetailState
    data object NotFound : CardDetailState
    data object Disconnected : CardDetailState
}

/** `US$ 0,42 est.`; `US$ ?` when the cost is unknown (never a silent zero). */
fun formatCost(costUsd: BigDecimal?): String {
    if (costUsd == null) return "US$ ?"
    val rounded = costUsd.setScale(2, RoundingMode.HALF_UP)
    return "US$ " + String.format(Locale.forLanguageTag("pt-BR"), "%.2f", rounded) + " est."
}

private val PR_EVENTS = setOf("CARD_MOVED", "OWNER_APPROVED", "PR_MERGED")
private val SECONDS_FORMAT_LOCALE = Locale.forLanguageTag("pt-BR")

/** One gate line of the Summary tab: verdict as text, duration text and the failure reason (only on FAIL). */
data class GateLine(val name: String, val verdict: String, val duration: String, val reason: String?)

fun gateLine(gate: GateRow): GateLine {
    val duration = when (val ms = gate.durationMs) {
        null -> Texts.NO_DURATION
        in 0 until 1_000 -> "$ms ms"
        else -> String.format(SECONDS_FORMAT_LOCALE, "%.1f s", ms / 1_000.0)
    }
    return GateLine(gate.name, gate.verdict, duration, gate.reason.takeIf { gate.verdict == "FAIL" })
}

enum class PrState { OPEN, MERGED }

data class PullRequestView(val number: Int, val state: PrState)

/** The PR of the last event that carries one; open until that event is `PR_MERGED`; null when no event has a PR. */
fun cardPullRequest(events: List<TimelineEvent>): PullRequestView? {
    val last = events.lastOrNull { it.type in PR_EVENTS && it.payload["pr"]?.toIntOrNull() != null } ?: return null
    val number = requireNotNull(last.payload["pr"]?.toIntOrNull()) { "pr missing in event ${last.id}" }
    return PullRequestView(number, if (last.type == "PR_MERGED") PrState.MERGED else PrState.OPEN)
}

data class OpenBlock(val reason: String, val at: Instant)

/** The last `CARD_BLOCKED` when no `CARD_UNBLOCKED` follows it; null otherwise. */
fun openBlock(events: List<TimelineEvent>): OpenBlock? {
    val last = events.lastOrNull { it.type == "CARD_BLOCKED" || it.type == "CARD_UNBLOCKED" } ?: return null
    if (last.type == "CARD_UNBLOCKED") return null
    return OpenBlock(last.payload["reason"]?.takeIf { it.isNotBlank() } ?: Texts.BLOCK_NO_REASON, last.at)
}

/** Runs of one stage with the stage subtotal; [status] is PARTIAL when only some runs have a known cost. */
data class StageRunCost(val stage: String, val status: CostStatus, val costUsd: BigDecimal?, val runs: List<RunRow>)

/** Groups runs by stage in order of first appearance; an unknown run cost is never counted as zero. */
fun costByStage(runs: List<RunRow>): List<StageRunCost> =
    runs.groupBy { it.stage }.map { (stage, stageRuns) ->
        val known = stageRuns.mapNotNull { it.costUsd }
        val status = when {
            known.isEmpty() -> CostStatus.UNKNOWN
            known.size < stageRuns.size -> CostStatus.PARTIAL
            else -> CostStatus.KNOWN
        }
        StageRunCost(stage, status, known.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add), stageRuns)
    }

/** The gate state of a criterion as text: `PASS`, `FAIL`, `none` or `manual`. */
fun criterionResult(criterion: CriterionRow): String = criterion.gate

class CardDetailModel(private val cardId: String, private val source: CardDetailSource) {
    private val mutableState = MutableStateFlow<CardDetailState>(CardDetailState.Loading)
    val state: StateFlow<CardDetailState> = mutableState

    /** Fetches the detail synchronously; callers run it off the UI thread. */
    fun refresh() {
        mutableState.value = try {
            parseReply(source.fetch(cardId))
        } catch (e: IOException) {
            CardDetailState.Disconnected
        }
    }

    /** Called by the `events.subscribe` feed for each event; refetches only for this model's card. */
    fun onCardEvent(cardId: String) {
        if (cardId == this.cardId) refresh()
    }

    private fun parseReply(line: String): CardDetailState {
        val root = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            return CardDetailState.Disconnected
        }
        if (root.bool("ok") != true) return CardDetailState.NotFound
        val detail = root["detail"] as? JsonObject ?: return CardDetailState.Disconnected
        return try {
            CardDetailState.Loaded(parseDetail(detail))
        } catch (e: IllegalArgumentException) {
            CardDetailState.Disconnected
        }
    }

    private fun parseDetail(detail: JsonObject): CardDetail {
        val card = detail.obj("card")
        val spec = detail.obj("spec")
        val total = detail.obj("total")
        return CardDetail(
            id = card.str("id"),
            title = card.str("title"),
            column = card.str("column"),
            spec = SpecView(spec.str("status"), spec.str("text")),
            events = detail.arr("events").map { parseEvent(it.jsonObject) },
            eventsTruncated = detail.bool("truncated") ?: false,
            runs = detail.arr("runs").map { parseRun(it.jsonObject) },
            total = CostTotal(costStatus(total.str("status")), total.cost()),
            gates = (detail["gates"] as? JsonArray).orEmpty().map { parseGate(it.jsonObject) },
            criteria = (detail["criteria"] as? JsonArray).orEmpty().map { parseCriterion(it.jsonObject) },
        )
    }

    private fun parseGate(gate: JsonObject) = GateRow(
        name = gate.str("name"),
        verdict = gate.str("verdict"),
        reason = gate.optStr("reason"),
        durationMs = gate.optStr("duration_ms")?.toLongOrNull(),
        at = try {
            Instant.parse(gate.str("at"))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("invalid at in card.detail reply", e)
        },
    )

    private fun parseCriterion(criterion: JsonObject) = CriterionRow(
        ac = criterion.str("ac"),
        layer = criterion.str("layer"),
        testFile = criterion.str("test_file"),
        gate = criterion.str("gate"),
    )

    private fun parseEvent(event: JsonObject) = TimelineEvent(
        id = event.str("id"),
        at = Instant.parse(event.str("at")),
        actor = event.str("actor"),
        type = event.str("type"),
        payload = (event["payload"] as? JsonObject).orEmpty().mapValues { (_, value) -> value.asText() },
    )

    private fun parseRun(run: JsonObject) = RunRow(
        runId = run.str("run_id"),
        stage = run.str("stage"),
        runtime = run.str("runtime"),
        status = costStatus(run.str("status")),
        costUsd = run.cost(),
        reason = (run["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )

    private fun costStatus(name: String): CostStatus =
        CostStatus.entries.firstOrNull { it.name == name } ?: CostStatus.UNKNOWN

    private fun JsonObject.cost(): BigDecimal? =
        (this["cost_usd"] as? JsonPrimitive)?.content?.let { raw ->
            try {
                BigDecimal(raw)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("invalid cost_usd in card.detail reply", e)
            }
        }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("missing $key in card.detail reply")

    private fun JsonObject.optStr(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: throw IllegalArgumentException("missing $key in card.detail reply")

    private fun JsonObject.arr(key: String): JsonArray =
        this[key] as? JsonArray ?: throw IllegalArgumentException("missing $key in card.detail reply")

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) null else it.boolean }

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()
}
