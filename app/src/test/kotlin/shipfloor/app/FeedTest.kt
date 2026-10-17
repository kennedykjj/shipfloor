package shipfloor.app

import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Shape the implementation must follow (deterministic, no UI, no sleeps, no real clock):
 *
 * - `FeedTransport` (the daemon seam; production wraps the socket): `recent(limit): RecentEvents` is `events.recent`
 *   (the last events in seq order plus `truncated`); `subscribe(after, listener): AutoCloseable` is `events.subscribe`
 *   and pushes events to `FeedListener.onEvent`, `onClosed()` when the connection drops.
 * - `CostSummarySource.summary(from, to): CostSummary` is `cost.summary` for the instants [from, to).
 * - `EventFeedClient(transport, costSource, clock, zone)` with `state: StateFlow<FeedState>`:
 *   `open()` asks `recent(2000)`, subscribes with `after = last seq of the window` (null when empty) and asks the
 *   cost source for the selected day (today in `zone`, from local midnight to next local midnight); a seq already seen
 *   is dropped; `onClosed` -> `FeedConnection.Reconnecting` keeping the rows; `reconnect()` subscribes again with
 *   `after = last seq seen` (no new `recent`) and goes back to `Live`; `selectDay(day)` filters the same window by
 *   local day and refreshes the cost; a `RUN_COST_RECORDED` event refreshes the cost.
 * - `FeedState.rows` are the selected day's events in seq order; `time` is `HH:mm:ss` in `zone`;
 *   `earlierHistoryUnavailable` is true when the window was truncated and its oldest event is after the day start.
 * - `CostSummaryView.of(summary)` only formats the daemon's numbers: `US$ 1.234,56`, total with the suffix `est.`,
 *   PARTIAL with `≥` and tag Partial, UNKNOWN as `US$ ?` with tag Unknown, cards sorted from the largest to the smallest,
 *   limit text and `LimitState` taken from the daemon's `limits`/`dayReached`/`cardsReached` (never recomputed).
 * - `CostSummaryView.stages` ("Por estágio") is one `CostLine` per `stages` entry of the daemon, labeled with the stage
 *   name, sorted from the largest to the smallest like the cards (UNKNOWN last), formatted like a card line, with no limit.
 *   `CostSummaryView.days` ("Por dia") is one `CostLine` per `days` entry, labeled `dd/MM`, in the daemon's chronological
 *   order, and is empty unless the period covers more than one day. A daemon without `stages`/`days` yields empty lists.
 */
class FeedTest {
    private val zone = ZoneOffset.ofHours(-3)
    private val clock = Clock.fixed(Instant.parse("2026-10-16T15:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 16)
    private val dayStart = Instant.parse("2026-10-16T03:00:00Z")
    private val nextDayStart = Instant.parse("2026-10-17T03:00:00Z")

    private val transport = FakeFeedTransport()
    private val source = FakeCostSource()

    private fun client() = EventFeedClient(transport, source, clock, zone).also { it.open() }

    @Test
    fun `AC-1 shows the events of the day in chronological order with type actor card and time`() {
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T02:00:00Z", card = "60") // 23:00 of the day before, local
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:05:09Z", type = "CARD_MOVED", actor = "owner", card = "68")
        transport.log += FeedFixtures.anEvent(3, "2026-10-16T13:00:00Z", type = "RUN_STARTED", actor = "agent:dev", card = "69")

        val rows = client().state.value.rows

        assertThat(rows.map { it.seq }).containsExactly(2L, 3L)
        assertThat(rows[0].time).isEqualTo("09:05:09")
        assertThat(rows[0].type).isEqualTo("CARD_MOVED")
        assertThat(rows[0].actor).isEqualTo("owner")
        assertThat(rows[0].card).contains("68")
        assertThat(rows[1].actor).isEqualTo("agent:dev")
    }

    @Test
    fun `AC-2 a new event appears at the end without reloading`() {
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T12:00:00Z")
        val client = client()

        transport.emit(FeedFixtures.anEvent(2, "2026-10-16T12:01:00Z", card = "70"))

        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 2L)
        assertThat(transport.recentCalls).isEqualTo(1)
    }

    @Test
    fun `AC-3 reconnecting brings the missed events once without duplicates or gaps`() {
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T12:00:00Z")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:01:00Z")
        val client = client()

        transport.drop()
        assertThat(client.state.value.connection).isEqualTo(FeedConnection.Reconnecting)
        assertThat(client.state.value.rows).hasSize(2)

        transport.missWhileDown(FeedFixtures.anEvent(3, "2026-10-16T12:02:00Z"))
        transport.missWhileDown(FeedFixtures.anEvent(4, "2026-10-16T12:03:00Z"))
        client.reconnect()

        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 2L, 3L, 4L)
        assertThat(client.state.value.connection).isEqualTo(FeedConnection.Live)
        assertThat(transport.subscribedAfter.last()).isEqualTo(2L)
        assertThat(transport.recentCalls).isEqualTo(1)
    }

    @Test
    fun `AC-4 shows the day total and the total per card labeled as estimate`() {
        source.next = FeedFixtures.aSummary(
            total = "3.20",
            cards = listOf(FeedFixtures.aCardCost("68", "1.10"), FeedFixtures.aCardCost("70", "2.10")),
        )

        val cost = client().state.value.cost

        assertThat(cost.total.value).isEqualTo("US$ 3,20 est.")
        assertThat(cost.cards.map { it.label }).satisfiesExactly(
            { assertThat(it).contains("70") },
            { assertThat(it).contains("68") },
        )
        assertThat(cost.cards.map { it.value }).satisfiesExactly(
            { assertThat(it).contains("2,10") },
            { assertThat(it).contains("1,10") },
        )
        val big = CostSummaryView.of(FeedFixtures.aSummary(total = "1234.56"))
        assertThat(big.total.value).isEqualTo("US$ 1.234,56 est.")
    }

    @Test
    fun `AC-5 partial and unknown costs are never shown as an exact number`() {
        val view = CostSummaryView.of(
            FeedFixtures.aSummary(
                total = "3.20",
                status = CostStatus.PARTIAL,
                cards = listOf(
                    FeedFixtures.aCardCost("68", "1.10", CostStatus.PARTIAL),
                    FeedFixtures.aCardCost("70", "2.10"),
                    FeedFixtures.aCardCost("71", null, CostStatus.UNKNOWN),
                ),
            ),
        )

        assertThat(view.total.value).startsWith("≥")
        assertThat(view.total.tag).isEqualTo(CostTag.Partial)
        val byCard = view.cards.associateBy { it.label.filter(Char::isDigit) }
        assertThat(byCard.getValue("68").value).startsWith("≥").contains("1,10")
        assertThat(byCard.getValue("68").tag).isEqualTo(CostTag.Partial)
        assertThat(byCard.getValue("70").tag).isNull()
        assertThat(byCard.getValue("70").value).doesNotContain("≥")
        assertThat(byCard.getValue("71").value).contains("US$ ?").doesNotContain("0,00")
        assertThat(byCard.getValue("71").tag).isEqualTo(CostTag.Unknown)
        assertThat(view.explainsPartial).isTrue()

        val unknownDay = CostSummaryView.of(FeedFixtures.aSummary(total = null, status = CostStatus.UNKNOWN))
        assertThat(unknownDay.total.value).contains("US$ ?").doesNotContain("0,00")
        assertThat(unknownDay.total.tag).isEqualTo(CostTag.Unknown)

        val exact = CostSummaryView.of(FeedFixtures.aSummary(total = "2.10", cards = listOf(FeedFixtures.aCardCost("70", "2.10"))))
        assertThat(exact.explainsPartial).isFalse()
        assertThat(exact.total.tag).isNull()
    }

    @Test
    fun `AC-6 each total sits next to its limit and is highlighted when the daemon says it was reached`() {
        val view = CostSummaryView.of(
            FeedFixtures.aSummary(
                total = "3.20",
                cards = listOf(FeedFixtures.aCardCost("70", "2.10"), FeedFixtures.aCardCost("68", "1.10")),
                limits = CostLimitsInfo(perCardUsd = BigDecimal("5.00"), perDayUsd = BigDecimal("10.00")),
                dayReached = true,
                cardsReached = setOf("70"),
            ),
        )

        assertThat(view.total.limit).isEqualTo("US$ 10,00")
        assertThat(view.total.limitState).isEqualTo(LimitState.Reached)
        val byCard = view.cards.associateBy { it.label.filter(Char::isDigit) }
        assertThat(byCard.getValue("70").limit).isEqualTo("US$ 5,00")
        assertThat(byCard.getValue("70").limitState).isEqualTo(LimitState.Reached)
        assertThat(byCard.getValue("68").limit).isEqualTo("US$ 5,00")
        assertThat(byCard.getValue("68").limitState).isEqualTo(LimitState.Within)
    }

    @Test
    fun `AC-7 without limits the totals show no limit and no highlight`() {
        val none = CostSummaryView.of(
            FeedFixtures.aSummary(total = "3.20", cards = listOf(FeedFixtures.aCardCost("68", "3.20"))),
        )

        assertThat(none.total.limit).isNull()
        assertThat(none.total.limitState).isEqualTo(LimitState.NoLimit)
        assertThat(none.cards.single().limit).isNull()
        assertThat(none.cards.single().limitState).isEqualTo(LimitState.NoLimit)

        val dayOnly = CostSummaryView.of(
            FeedFixtures.aSummary(
                total = "3.20",
                cards = listOf(FeedFixtures.aCardCost("68", "3.20")),
                limits = CostLimitsInfo(perCardUsd = null, perDayUsd = BigDecimal("10.00")),
            ),
        )
        assertThat(dayOnly.total.limit).isEqualTo("US$ 10,00")
        assertThat(dayOnly.cards.single().limit).isNull()
        assertThat(dayOnly.cards.single().limitState).isEqualTo(LimitState.NoLimit)
    }

    @Test
    fun `AC-8 a day without events shows an empty state and zero cost`() {
        source.next = FeedFixtures.aSummary(total = "0", status = CostStatus.NONE)

        val state = client().state.value

        assertThat(state.rows).isEmpty()
        assertThat(state.loading).isFalse()
        assertThat(state.earlierHistoryUnavailable).isFalse()
        assertThat(state.cost.total.value).isEqualTo("US$ 0,00 est.")
        assertThat(state.cost.cards).isEmpty()
    }

    @Test
    fun `AC-9 opening the feed reads only the last 2000 events and warns when the window misses the start of the day`() {
        transport.cap = 3
        (1L..5L).forEach { transport.log += FeedFixtures.anEvent(it, "2026-10-16T0${4 + it}:00:00Z") } // 05:00Z.. 09:00Z

        val state = client().state.value

        assertThat(transport.recentLimit).isEqualTo(2000)
        assertThat(state.rows.map { it.seq }).containsExactly(3L, 4L, 5L)
        assertThat(state.earlierHistoryUnavailable).isTrue()
        assertThat(transport.subscribedAfter.last()).isEqualTo(5L)
    }

    @Test
    fun `AC-9 no warning when the window reaches the start of the day or covers all history`() {
        transport.cap = 2
        transport.log += FeedFixtures.anEvent(1, "2026-10-15T20:00:00Z")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T01:00:00Z") // yesterday, local
        transport.log += FeedFixtures.anEvent(3, "2026-10-16T12:00:00Z")
        assertThat(client().state.value.earlierHistoryUnavailable).isFalse()

        val small = FakeFeedTransport().also { it.log += FeedFixtures.anEvent(1, "2026-10-16T12:00:00Z") }
        val fresh = EventFeedClient(small, source, clock, zone).also { it.open() }
        assertThat(fresh.state.value.earlierHistoryUnavailable).isFalse()
    }

    @Test
    fun `AC-9 a chosen day shows only what the window covers and warns`() {
        transport.cap = 1
        transport.log += FeedFixtures.anEvent(1, "2026-10-15T20:00:00Z")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:00:00Z")
        val client = client()

        client.selectDay(today.minusDays(1))

        assertThat(client.state.value.rows).isEmpty()
        assertThat(client.state.value.earlierHistoryUnavailable).isTrue()
    }

    @Test
    fun `AC-10 the app shows the numbers the daemon computed and asks again when a cost is recorded`() {
        source.next = FeedFixtures.aSummary(
            total = "3.20",
            cards = listOf(FeedFixtures.aCardCost("70", "2.10")),
            limits = CostLimitsInfo(perCardUsd = BigDecimal("5.00"), perDayUsd = BigDecimal("10.00")),
            dayReached = false,
            cardsReached = setOf("70"), // the daemon blocked card 70 although 2.10 < 5.00
        )
        val client = client()

        val card = client.state.value.cost.cards.single()
        assertThat(card.limitState).isEqualTo(LimitState.Reached)
        assertThat(client.state.value.cost.total.limitState).isEqualTo(LimitState.Within)

        source.next = FeedFixtures.aSummary(total = "11.00", dayReached = true, limits = CostLimitsInfo(null, BigDecimal("10.00")))
        transport.emit(FeedFixtures.anEvent(9, "2026-10-16T14:00:00Z", type = "RUN_COST_RECORDED", actor = "factory:cost", card = "70"))

        assertThat(client.state.value.cost.total.value).isEqualTo("US$ 11,00 est.")
        assertThat(client.state.value.cost.total.limitState).isEqualTo(LimitState.Reached)
    }

    @Test
    fun `AC-11 the day total comes from the daemon for the whole day even when the window does not cover it`() {
        transport.cap = 1
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T05:00:00Z")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:00:00Z")
        source.next = FeedFixtures.aSummary(total = "42.00")

        val client = client()

        assertThat(client.state.value.earlierHistoryUnavailable).isTrue()
        assertThat(client.state.value.cost.total.value).isEqualTo("US$ 42,00 est.")
        assertThat(source.requests.last()).isEqualTo(dayStart to nextDayStart)

        client.selectDay(today.minusDays(1))
        assertThat(source.requests.last()).isEqualTo(dayStart.minusSeconds(86_400) to dayStart)
    }

    /** The view the app builds from a `cost.summary` reply, read through the real socket source. */
    private fun viewOf(dir: Path, result: String): CostSummaryView {
        val socket = dir.resolve("d.sock")
        val daemon = FakeDaemon(socket) { """{"ok":true,"result":$result}""" }
        try {
            return CostSummaryView.of(SocketCostSummarySource(socket).summary(dayStart, nextDayStart))
        } finally {
            daemon.close()
        }
    }

    private val noLimits = """"limits":{"perCardUsd":null,"perDayUsd":null},"dayReached":false,"cardsReached":[]"""

    @Test
    fun `AC-5 the panel lists cost per stage and per day as the daemon computed, with tags`(@TempDir dir: Path) {
        val view = viewOf(
            dir,
            """{"totalUsd":"7.00","status":"PARTIAL","cards":[],$noLimits,
            "stages":[{"stage":"develop","costUsd":"1.50","status":"KNOWN"},{"stage":"refine","costUsd":"5.50","status":"PARTIAL"},{"stage":"validate","costUsd":"0","status":"UNKNOWN"}],
            "days":[{"day":"2026-10-15","costUsd":"1.00","status":"KNOWN"},{"day":"2026-10-16","costUsd":"6.00","status":"KNOWN"}]}""".replace("\n", ""),
        )

        assertThat(view.stages.map { it.label }).containsExactly("refine", "develop", "validate")
        assertThat(view.stages.map { it.value }).containsExactly("≥ US$ 5,50", "US$ 1,50", "US$ ?")
        assertThat(view.stages.map { it.tag }).containsExactly(CostTag.Partial, null, CostTag.Unknown)
        assertThat(view.stages.map { it.limitState }).containsOnly(LimitState.NoLimit)
        assertThat(view.stages.map { it.limit }).containsOnlyNulls()
        assertThat(view.days.map { it.label }).containsExactly("15/10", "16/10")
        assertThat(view.days.map { it.value }).containsExactly("US$ 1,00", "US$ 6,00")
        assertThat(view.days.map { it.tag }).containsOnlyNulls()
        assertThat(view.explainsPartial).isTrue()
    }

    @Test
    fun `AC-5 a partial stage alone makes the panel explain the estimate, and a single day hides Por dia`(@TempDir dir: Path) {
        val view = viewOf(
            dir,
            """{"totalUsd":"3.00","status":"KNOWN","cards":[],$noLimits,
            "stages":[{"stage":"refine","costUsd":"3.00","status":"PARTIAL"}],
            "days":[{"day":"2026-10-16","costUsd":"3.00","status":"KNOWN"}]}""".replace("\n", ""),
        )

        assertThat(view.stages.map { it.label }).containsExactly("refine")
        assertThat(view.explainsPartial).isTrue()
        assertThat(view.days).isEmpty()
    }

    @Test
    fun `AC-6 a daemon without stages and days leaves the panel as it was, with no error`(@TempDir dir: Path) {
        val view = viewOf(
            dir,
            """{"totalUsd":"3.20","status":"KNOWN","cards":[{"card":"68","costUsd":"1.10","status":"KNOWN"},{"card":"70","costUsd":"2.10","status":"KNOWN"}],
            "limits":{"perCardUsd":"5.00","perDayUsd":"10.00"},"dayReached":true,"cardsReached":["70"]}""".replace("\n", ""),
        )

        assertThat(view.stages).isEmpty()
        assertThat(view.days).isEmpty()
        assertThat(view.total.value).isEqualTo("US$ 3,20 est.")
        assertThat(view.total.limit).isEqualTo("US$ 10,00")
        assertThat(view.total.limitState).isEqualTo(LimitState.Reached)
        assertThat(view.cards.map { it.label }).satisfiesExactly(
            { assertThat(it).contains("70") },
            { assertThat(it).contains("68") },
        )
        assertThat(view.cards.map { it.limitState }).containsExactly(LimitState.Reached, LimitState.Within)
        assertThat(view.explainsPartial).isFalse()
    }

    @Test
    fun `AC-6 the loading and empty views carry no stage or day lines`() {
        assertThat(CostSummaryView.loading().stages).isEmpty()
        assertThat(CostSummaryView.loading().days).isEmpty()
        assertThat(CostSummaryView.of(FeedFixtures.aSummary(total = "0", status = CostStatus.NONE)).stages).isEmpty()
    }

    @Test
    fun `AC-1 the row shows the actor kind in Portuguese and its detail`() {
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T12:00:00Z", actor = "owner")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:01:00Z", actor = "agent:dev")
        transport.log += FeedFixtures.anEvent(3, "2026-10-16T12:02:00Z", actor = "factory:cost")
        transport.log += FeedFixtures.anEvent(4, "2026-10-16T12:03:00Z", actor = "adapter:github")

        val rows = client().state.value.rows

        assertThat(rows.map { it.actorKind }).containsExactly("dono", "agente", "fábrica", "adaptador")
        assertThat(rows.map { it.actorDetail }).containsExactly(null, "dev", "cost", "github")
    }

    @Test
    fun `AC-1 an actor of unknown kind shows its original text without error`() {
        transport.log += FeedFixtures.anEvent(1, "2026-10-16T12:00:00Z", actor = "robot:x")
        transport.log += FeedFixtures.anEvent(2, "2026-10-16T12:01:00Z", actor = "stranger")

        val rows = client().state.value.rows

        assertThat(rows.map { it.actorKind }).containsExactly("robot:x", "stranger")
        assertThat(rows.map { it.actorDetail }).containsOnlyNulls()
    }

    @Test
    fun `AC-2 a cost event shows its own cost labeled as estimate with the tag of its status`() {
        transport.log += FeedFixtures.aCostEvent(1, "2026-10-16T12:00:00Z", "KNOWN", "0.42")
        transport.log += FeedFixtures.aCostEvent(2, "2026-10-16T12:01:00Z", "PARTIAL", "0.42")
        transport.log += FeedFixtures.aCostEvent(3, "2026-10-16T12:02:00Z", "UNKNOWN", null)
        transport.log += FeedFixtures.anEvent(4, "2026-10-16T12:03:00Z", type = "RUN_STARTED")

        val rows = client().state.value.rows

        assertThat(rows.map { it.cost }).containsExactly("US$ 0,42 est.", "≥ US$ 0,42 est.", "US$ ?", null)
        assertThat(rows.map { it.costTag }).containsExactly(null, CostTag.Partial, CostTag.Unknown, null)
    }

    @Test
    fun `AC-3 a cost event with a missing or unreadable cost shows US$ ? and never zero`() {
        transport.log += FeedFixtures.aCostEvent(1, "2026-10-16T12:00:00Z", "KNOWN", null)
        transport.log += FeedFixtures.aCostEvent(2, "2026-10-16T12:01:00Z", "KNOWN", "not-a-number")

        val rows = client().state.value.rows

        assertThat(rows.map { it.cost }).containsExactly("US$ ?", "US$ ?")
    }

    @Test
    fun `AC-4 the feed offers Todos and one chip per stage of the day with its count`() {
        transport.log += FeedFixtures.aStageEvent(1, "2026-10-16T12:00:00Z", "develop")
        transport.log += FeedFixtures.aStageEvent(2, "2026-10-16T12:01:00Z", "develop")
        transport.log += FeedFixtures.aStageEvent(3, "2026-10-16T12:02:00Z", "refine")
        transport.log += FeedFixtures.anEvent(4, "2026-10-16T12:03:00Z")
        transport.log += FeedFixtures.aStageEvent(5, "2026-10-15T12:00:00Z", "validate") // yesterday, not in the day

        val state = client().state.value

        assertThat(state.stage).isNull()
        assertThat(state.stages.first()).isEqualTo(StageChip(null, 4))
        assertThat(state.stages.drop(1)).containsExactlyInAnyOrder(StageChip("develop", 2), StageChip("refine", 1))
        assertThat(state.rows.map { it.stage }).containsExactly("develop", "develop", "refine", null)
    }

    @Test
    fun `AC-5 a chosen stage lists only its events in order and a new event of it enters without reloading`() {
        transport.log += FeedFixtures.aStageEvent(1, "2026-10-16T12:00:00Z", "develop")
        transport.log += FeedFixtures.aStageEvent(2, "2026-10-16T12:01:00Z", "refine")
        transport.log += FeedFixtures.aStageEvent(3, "2026-10-16T12:02:00Z", "develop")
        val client = client()

        client.selectStage("develop")

        assertThat(client.state.value.stage).isEqualTo("develop")
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 3L)

        transport.emit(FeedFixtures.aStageEvent(4, "2026-10-16T12:03:00Z", "develop"))
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 3L, 4L)

        transport.emit(FeedFixtures.aStageEvent(5, "2026-10-16T12:04:00Z", "refine"))
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 3L, 4L)
        assertThat(client.state.value.stages).contains(StageChip(null, 5), StageChip("develop", 3), StageChip("refine", 2))
        assertThat(transport.recentCalls).isEqualTo(1)

        client.selectStage(null)
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `AC-6 changing day keeps the chosen stage when the new day has it and goes back to Todos when it does not`() {
        transport.log += FeedFixtures.aStageEvent(1, "2026-10-15T12:00:00Z", "develop")
        transport.log += FeedFixtures.aStageEvent(2, "2026-10-16T12:00:00Z", "develop")
        transport.log += FeedFixtures.aStageEvent(3, "2026-10-16T12:01:00Z", "refine")
        val client = client()

        client.selectStage("develop")
        client.selectDay(today.minusDays(1))
        assertThat(client.state.value.stage).isEqualTo("develop")
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L)

        client.selectDay(today)
        client.selectStage("refine")
        client.selectDay(today.minusDays(1))
        assertThat(client.state.value.stage).isNull()
        assertThat(client.state.value.rows.map { it.seq }).containsExactly(1L)
        assertThat(client.state.value.stages.first()).isEqualTo(StageChip(null, 1))
    }

    @Test
    fun `AC-7 the day total stays the daemon's number and the app does not add up the row costs`() {
        transport.log += FeedFixtures.aCostEvent(1, "2026-10-16T12:00:00Z", "KNOWN", "0.42")
        source.next = FeedFixtures.aSummary(total = "42.00")
        val client = client()

        transport.emit(FeedFixtures.aCostEvent(2, "2026-10-16T12:01:00Z", "KNOWN", "0.58"))

        assertThat(client.state.value.rows.map { it.cost }).containsExactly("US$ 0,42 est.", "US$ 0,58 est.")
        assertThat(client.state.value.cost.total.value).isEqualTo("US$ 42,00 est.")
        assertThat(source.requests).hasSize(2)
    }
}

object FeedFixtures {
    fun anEvent(
        seq: Long,
        at: String,
        type: String = "CARD_CREATED",
        actor: String = "owner",
        card: String = "68",
        payload: Map<String, String> = emptyMap(),
    ) = FeedEvent(seq, card, "e$seq", Instant.parse(at), actor, type, payload)

    fun aStageEvent(seq: Long, at: String, stage: String, card: String = "68") =
        anEvent(seq, at, type = "RUN_STARTED", actor = "agent:dev", card = card, payload = mapOf("stage" to stage))

    fun aCostEvent(seq: Long, at: String, status: String, costUsd: String?, stage: String = "develop") =
        anEvent(
            seq,
            at,
            type = "RUN_COST_RECORDED",
            actor = "factory:cost",
            payload = buildMap {
                put("stage", stage)
                put("status", status)
                costUsd?.let { put("cost_usd", it) }
            },
        )

    fun aCardCost(card: String, usd: String?, status: CostStatus = CostStatus.KNOWN) =
        CardCost(card, usd?.let(::BigDecimal), status)

    fun aSummary(
        total: String?,
        status: CostStatus = CostStatus.KNOWN,
        cards: List<CardCost> = emptyList(),
        limits: CostLimitsInfo = CostLimitsInfo(null, null),
        dayReached: Boolean = false,
        cardsReached: Set<String> = emptySet(),
    ) = CostSummary(total?.let(::BigDecimal), status, cards, limits, dayReached, cardsReached)
}

/** The daemon's event log. Resubscribing replays from `after` inclusive, so the client must drop what it already saw. */
class FakeFeedTransport : FeedTransport {
    val log = mutableListOf<FeedEvent>()
    var cap = 2000
    var recentCalls = 0
    var recentLimit: Int? = null
    val subscribedAfter = mutableListOf<Long?>()
    private var listener: FeedListener? = null

    override fun recent(limit: Int): RecentEvents {
        recentCalls += 1
        recentLimit = limit
        val size = minOf(limit, cap)
        return RecentEvents(log.takeLast(size), truncated = log.size > size)
    }

    override fun subscribe(after: Long?, listener: FeedListener): AutoCloseable {
        subscribedAfter += after
        this.listener = listener
        if (after != null) log.filter { it.seq >= after }.forEach(listener::onEvent)
        return AutoCloseable { this.listener = null }
    }

    fun emit(event: FeedEvent) {
        log += event
        listener?.onEvent(event)
    }

    fun missWhileDown(event: FeedEvent) {
        log += event
    }

    fun drop() {
        val current = listener ?: return
        listener = null
        current.onClosed()
    }
}

class FakeCostSource : CostSummarySource {
    var next = FeedFixtures.aSummary(total = "0", status = CostStatus.NONE)
    val requests = mutableListOf<Pair<Instant, Instant>>()

    override fun summary(from: Instant, to: Instant): CostSummary {
        requests += from to to
        return next
    }
}
