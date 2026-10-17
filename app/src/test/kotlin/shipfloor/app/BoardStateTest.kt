package shipfloor.app

import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import shipfloor.core.AssistantState
import shipfloor.core.Column
import shipfloor.core.ColumnTime
import shipfloor.core.ColumnTimeKind
import shipfloor.core.EventType
import shipfloor.core.ForecastGap
import shipfloor.core.OWNER_WAIT_COLUMNS
import shipfloor.core.QueueForecast

/**
 * Shape the implementation must follow (pure, in `BoardState.kt` and `BoardSource.kt`):
 *
 * - `BoardCard(id: String, title: String, column: Column)`; `Board(cards: List<BoardCard>, lastSeq: Long?)`.
 * - `BoardEvent(seq: Long, cardId: String, type: EventType, payload: Map<String, String>)`.
 * - `Board.applySnapshot(cards): Board`, `Board.cardsIn(column): List<BoardCard>` (insertion order).
 * - `Board.applyEvent(event: BoardEvent, fetched: BoardCard? = null): Board`: for `CARD_CREATED`, `fetched` is the
 *   card as `BoardSource.fetchCard` reported it (real column); without it the created card is not placed (never Inbox).
 *   `CARD_MOVED`/`OWNER_APPROVED` put the card in `payload["to"]` (a `Column` name), at the end of that column.
 * - `isWaitingForOwner(column)` and `OWNER_DECISION_COLUMNS`.
 * - `ColumnTimeLabel.of(time: ColumnTime): String`: the column subtitle, "mediana <duração>" for `MEDIAN` and
 *   "espera média <duração>" for `MEAN_WAIT`; duration is "< 1 min", "N min" up to 59 min, "N h MM min" from 1 h.
 *
 * Pulse strip (pure, in `PulseModel.kt`):
 *
 * - `Pulse(state: AssistantState, phrase: String, poolCapacity: Int, active: List<String>)`: the daemon's reading.
 * - `PulseView.of(pulse, cost: CostSummary)` is the live strip; `PulseView.loading()` is the strip before any reading;
 *   `PulseView.markStale()` keeps the last reading and says it is out of date. Nothing is computed beyond words,
 *   squares (`true` = in use, one per slot) and the meter (`percent` = spent over the day limit, rounded).
 * - `ceiling` is `PulseCeiling.Meter` when the day has a limit, `PulseCeiling.Unlimited` otherwise, null before a reading.
 *
 * Forecast cell (pure, in `PulseModel.kt` or a sibling file): `ForecastCells.of(forecast: QueueForecast?, now, zone):
 * ForecastCell(headline, secondary, description)`; `null` is a daemon that sent no forecast. `Pulse` and `PulseView`
 * carry the forecast and build the cell with it; `markStale()` keeps it through `copy`.
 *
 * Command palette (pure, in `PaletteModel.kt`):
 *
 * - `PaletteView(label)` with `BOARD("Quadro")`, `FEED("Feed")`, `SETTINGS("Configurações")`; `Direction { UP, DOWN, FIRST, LAST }`.
 * - `PaletteEntry.Card(card, waiting)` and `PaletteEntry.View(view)`.
 * - `paletteEntries(cards, views, query)`: waiting cards, then views, then the other cards; filtered by label, title,
 *   `id` prefix or column display name (case-insensitive); at most `PALETTE_ROWS` (9) rows, cut after filtering.
 * - `PaletteState(open, query, active)`: `toggle()`, `close()` (both drop query and active), `withQuery(query)`,
 *   `move(direction, size)` (UP/DOWN wrap, FIRST/LAST jump, result always in `0 until size`, 0 when `size` is 0),
 *   `select(entries): PaletteChoice(state, entry)` where `state` is closed and `entry` is the active row or null.
 */
class BoardStateTest {
    @Test
    fun `the two lanes hold the ten columns once each, in product order`() {
        assertThat(LANE_ONE + LANE_TWO).containsExactlyElementsOf(Column.entries)
        assertThat(LANE_ONE).hasSize(5)
    }

    @Test
    fun `a card shows the glyph of what is happening to it, and blocked wins over the column`() {
        val glyphs = Column.entries.associateWith { glyphOf(it, blocked = false) }

        assertThat(glyphs[Column.INBOX]).isEqualTo(CardGlyph.QUEUED)
        assertThat(glyphs[Column.READY_FOR_DEV]).isEqualTo(CardGlyph.QUEUED)
        assertThat(glyphs[Column.CODING]).isEqualTo(CardGlyph.RUNNING)
        assertThat(glyphs[Column.SPEC_REVIEW]).isEqualTo(CardGlyph.WAITING)
        assertThat(glyphs[Column.STAGED]).isEqualTo(CardGlyph.WAITING)
        assertThat(glyphs[Column.DONE]).isEqualTo(CardGlyph.DONE)
        assertThat(glyphOf(Column.CODING, blocked = true)).isEqualTo(CardGlyph.BLOCKED)
    }

    @Test
    fun `the strip names the two Ready queues and Ready for Prod shortly, and every other column in full`() {
        assertThat(stripLabel(Column.READY_FOR_REFINEMENT)).isEqualTo("Ready")
        assertThat(stripLabel(Column.READY_FOR_DEV)).isEqualTo("Ready")
        assertThat(stripLabel(Column.READY_FOR_PROD)).isEqualTo("Prod")
        assertThat(stripLabel(Column.SPEC_REVIEW)).isEqualTo(Column.SPEC_REVIEW.displayName)
    }

    @Test
    fun `the waiting count is the cards in the owner's decision columns`() {
        val view = BoardView(
            BoardFixtures.empty().applySnapshot(
                listOf(
                    BoardFixtures.aCard("a", Column.SPEC_REVIEW),
                    BoardFixtures.aCard("b", Column.STAGED),
                    BoardFixtures.aCard("c", Column.CODING),
                ),
            ),
            live = true,
        )

        assertThat(waitingCount(view)).isEqualTo(2)
        assertThat(waitingCount(null)).isEqualTo(0)
    }

    @Test
    fun `AC-1 lists every column in product order with the snapshot cards in the right one`() {
        val board = BoardFixtures.empty().applySnapshot(
            listOf(
                BoardFixtures.aCard("a", Column.INBOX),
                BoardFixtures.aCard("b", Column.CODING),
                BoardFixtures.aCard("c", Column.CODING),
                BoardFixtures.aCard("d", Column.DONE),
            ),
        )

        assertThat(Column.entries).hasSize(10)
        assertThat(board.cardsIn(Column.INBOX).map { it.id }).containsExactly("a")
        assertThat(board.cardsIn(Column.CODING).map { it.id }).containsExactly("b", "c")
        assertThat(board.cardsIn(Column.DONE).map { it.id }).containsExactly("d")
        assertThat(board.cardsIn(Column.SPEC_REVIEW)).isEmpty()
    }

    @Test
    fun `AC-2 a created card appears in the column the daemon reports, not in Inbox by assumption`() {
        val board = BoardFixtures.empty().applySnapshot(listOf(BoardFixtures.aCard("a", Column.INBOX)))
        val created = BoardFixtures.aCard("n", Column.REFINEMENT)

        val after = board.applyEvent(BoardFixtures.anEvent(1, "n", EventType.CARD_CREATED), fetched = created)

        assertThat(after.cardsIn(Column.REFINEMENT)).containsExactly(created)
        assertThat(after.cardsIn(Column.INBOX).map { it.id }).containsExactly("a")
        assertThat(after.lastSeq).isEqualTo(1L)
    }

    @Test
    fun `AC-3 a moved card leaves its column and joins the destination at the end`() {
        val board = BoardFixtures.empty().applySnapshot(
            listOf(
                BoardFixtures.aCard("a", Column.CODING),
                BoardFixtures.aCard("b", Column.VALIDATION),
                BoardFixtures.aCard("c", Column.CODING),
            ),
        )

        val after = board.applyEvent(BoardFixtures.aMove(2, "a", Column.VALIDATION))

        assertThat(after.cardsIn(Column.CODING).map { it.id }).containsExactly("c")
        assertThat(after.cardsIn(Column.VALIDATION).map { it.id }).containsExactly("b", "a")
    }

    @Test
    fun `AC-3 events are idempotent, unknown cards are ignored and unrelated events only advance the cursor`() {
        val board = BoardFixtures.empty()
            .applySnapshot(listOf(BoardFixtures.aCard("a", Column.CODING)))
            .applyEvent(BoardFixtures.aMove(5, "a", Column.VALIDATION))

        val replayed = board.applyEvent(BoardFixtures.aMove(5, "a", Column.DONE))
        val older = board.applyEvent(BoardFixtures.aMove(4, "a", Column.DONE))
        val unknown = board.applyEvent(BoardFixtures.aMove(6, "ghost", Column.DONE))
        val other = board.applyEvent(BoardFixtures.anEvent(7, "a", EventType.GATE_FINISHED))

        assertThat(replayed).isEqualTo(board)
        assertThat(older).isEqualTo(board)
        assertThat(unknown.cardsIn(Column.DONE)).isEmpty()
        assertThat(unknown.lastSeq).isEqualTo(6L)
        assertThat(other.cardsIn(Column.VALIDATION).map { it.id }).containsExactly("a")
        assertThat(other.lastSeq).isEqualTo(7L)
    }

    @Test
    fun `AC-4 an empty snapshot gives ten empty columns`() {
        val board = BoardFixtures.empty().applySnapshot(emptyList())

        assertThat(Column.entries.map { board.cardsIn(it) }).hasSize(10).allMatch { it.isEmpty() }
    }

    @Test
    fun `AC-4 reverting a refused optimistic move puts the card back in its origin column`() {
        val board = BoardFixtures.empty().applySnapshot(
            listOf(BoardFixtures.aCard("a", Column.STAGED), BoardFixtures.aCard("b", Column.READY_FOR_PROD)),
        )

        val moved = board.withOptimisticMove("a", Column.READY_FOR_PROD)
        val reverted = moved.revertMove("a", Column.STAGED, Column.READY_FOR_PROD)

        assertThat(moved.cardsIn(Column.READY_FOR_PROD).map { it.id }).containsExactly("b", "a")
        assertThat(moved.cardsIn(Column.STAGED)).isEmpty()
        assertThat(reverted.cardsIn(Column.STAGED).map { it.id }).containsExactly("a")
        assertThat(reverted.cardsIn(Column.READY_FOR_PROD).map { it.id }).containsExactly("b")
    }

    @Test
    fun `AC-4 reverting leaves the card where a daemon event already took it`() {
        val board = BoardFixtures.empty()
            .applySnapshot(listOf(BoardFixtures.aCard("a", Column.STAGED)))
            .withOptimisticMove("a", Column.READY_FOR_PROD)
            .applyEvent(BoardFixtures.aMove(1, "a", Column.DONE))

        val reverted = board.revertMove("a", Column.STAGED, Column.READY_FOR_PROD)

        assertThat(reverted).isEqualTo(board)
        assertThat(reverted.cardsIn(Column.DONE).map { it.id }).containsExactly("a")
    }

    @Test
    fun `AC-5 only Spec Review and Staged wait for the owner`() {
        assertThat(OWNER_DECISION_COLUMNS).containsExactlyInAnyOrder(Column.SPEC_REVIEW, Column.STAGED)
        assertThat(Column.entries.filter { isWaitingForOwner(it) })
            .containsExactlyInAnyOrder(Column.SPEC_REVIEW, Column.STAGED)
    }

    @Test
    fun `AC-3 the board owner decision columns are the core owner wait set`() {
        assertThat(OWNER_DECISION_COLUMNS).isEqualTo(OWNER_WAIT_COLUMNS)
    }

    @Test
    fun `AC-3 isWaitingForOwner is true for exactly the core owner wait columns`() {
        assertThat(OWNER_WAIT_COLUMNS).isNotEmpty()
        assertThat(Column.entries.filter { isWaitingForOwner(it) })
            .containsExactlyInAnyOrderElementsOf(OWNER_WAIT_COLUMNS)
    }

    @Test
    fun `AC-6 a card moved out of a decision column is no longer in a waiting column`() {
        val board = BoardFixtures.empty()
            .applySnapshot(listOf(BoardFixtures.aCard("a", Column.STAGED)))

        val after = board.applyEvent(BoardFixtures.aMove(1, "a", Column.READY_FOR_PROD))

        assertThat(board.cardsIn(Column.STAGED)).hasSize(1)
        assertThat(isWaitingForOwner(Column.STAGED)).isTrue()
        assertThat(after.cardsIn(Column.STAGED)).isEmpty()
        assertThat(isWaitingForOwner(Column.READY_FOR_PROD)).isFalse()
        assertThat(after.cardsIn(Column.READY_FOR_PROD).map { it.id }).containsExactly("a")
    }

    @Test
    fun `AC-1 flow bar has one segment per column with statistics in flow order with the seconds`() {
        val times = mapOf(
            Column.STAGED to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 200L),
            Column.CODING to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 600L),
            Column.REFINEMENT to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 300L),
            Column.VALIDATION to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 100L),
            Column.SPEC_REVIEW to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 800L),
        )

        val split = TimeSplit.of(times)

        assertThat(split?.segments).containsExactly(
            TimeSegment(Column.REFINEMENT, 300L),
            TimeSegment(Column.SPEC_REVIEW, 800L),
            TimeSegment(Column.CODING, 600L),
            TimeSegment(Column.VALIDATION, 100L),
            TimeSegment(Column.STAGED, 200L),
        )
    }

    @Test
    fun `AC-1 flow bar gives no segment to a column without statistics`() {
        val times = mapOf(
            Column.REFINEMENT to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 300L),
            Column.STAGED to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 200L),
        )

        val split = TimeSplit.of(times)

        assertThat(split?.segments?.map { it.column }).containsExactly(Column.REFINEMENT, Column.STAGED)
    }

    @Test
    fun `AC-2 wait percent is the wait segments over all segments`() {
        val times = mapOf(
            Column.REFINEMENT to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 600L),
            Column.SPEC_REVIEW to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 300L),
            Column.CODING to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 600L),
            Column.VALIDATION to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 300L),
            Column.STAGED to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 200L),
        )

        assertThat(TimeSplit.of(times)?.waitPercent).isEqualTo(25)
    }

    @Test
    fun `AC-2 wait percent rounds to the nearest integer and a half goes up`() {
        fun percent(work: Long, wait: Long): Int? = TimeSplit.of(
            mapOf(
                Column.CODING to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, work),
                Column.STAGED to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, wait),
            ),
        )?.waitPercent

        assertThat(percent(work = 7L, wait = 1L)).isEqualTo(13)
        assertThat(percent(work = 2L, wait = 1L)).isEqualTo(33)
        assertThat(percent(work = 1L, wait = 2L)).isEqualTo(67)
        assertThat(percent(work = 0L, wait = 5L)).isEqualTo(100)
        assertThat(percent(work = 5L, wait = 0L)).isEqualTo(0)
    }

    @Test
    fun `AC-3 flow bar is absent when no column has statistics`() {
        assertThat(TimeSplit.of(emptyMap())).isNull()
    }

    @Test
    fun `AC-3 flow bar is absent when the total seconds is zero instead of showing 0 percent`() {
        val times = mapOf(
            Column.CODING to BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 0L),
            Column.STAGED to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 0L),
        )

        assertThat(TimeSplit.of(times)).isNull()
    }

    @Test
    fun `AC-3 flow bar ignores statistics of columns outside the flow stages`() {
        val times = mapOf(Column.INBOX to BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 900L))

        assertThat(TimeSplit.of(times)).isNull()
    }

    @Test
    fun `AC-6 flow bar model carries only column names and numbers, never card text`() {
        fun instanceFields(type: Class<*>) =
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }

        assertThat(instanceFields(TimeSegment::class.java)).containsExactlyInAnyOrder("column", "seconds")
        assertThat(instanceFields(TimeSplit::class.java)).containsExactlyInAnyOrder("segments", "waitPercent")
    }

    @Test
    fun `AC-6 a work column subtitle says median and the minutes`() {
        val label = ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 360L))

        assertThat(label).isEqualTo("mediana 6 min")
    }

    @Test
    fun `AC-6 a gate column subtitle says average wait and the minutes`() {
        val label = ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 2_460L))

        assertThat(label).isEqualTo("espera média 41 min")
    }

    @Test
    fun `AC-6 less than one minute reads as under one minute`() {
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 59L))).isEqualTo("mediana < 1 min")
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 0L))).isEqualTo("mediana < 1 min")
    }

    @Test
    fun `AC-6 the minute scale holds up to 59 minutes and hours start at 1 h with two-digit minutes`() {
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 60L))).isEqualTo("mediana 1 min")
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 3_599L))).isEqualTo("mediana 59 min")
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEDIAN, 3_900L))).isEqualTo("mediana 1 h 05 min")
        assertThat(ColumnTimeLabel.of(BoardFixtures.aColumnTime(ColumnTimeKind.MEAN_WAIT, 7_200L))).isEqualTo("espera média 2 h 00 min")
    }

    @Test
    fun `AC-3 pulse strip shows the state in words with the daemon's phrase`() {
        val view = PulseView.of(PulseFixtures.aPulse(), PulseFixtures.dayWithLimit("3.20"))

        assertThat(view.freshness).isEqualTo(PulseFreshness.Live)
        assertThat(view.stateText).isEqualTo("▸ trabalhando")
        assertThat(view.phrase).isEqualTo("Em andamento: #235, #240.")
        assertThat(view.notice).isNull()
    }

    @Test
    fun `AC-3 pulse strip shows the pool as used and total squares with the active cards`() {
        val view = PulseView.of(PulseFixtures.aPulse(capacity = 4, active = listOf("#235", "#240")), PulseFixtures.dayWithLimit("3.20"))

        assertThat(view.sessionsText).isEqualTo("2 de 4 sessões")
        assertThat(view.squares).containsExactly(true, true, false, false)
        assertThat(view.activeCards).containsExactly("#235", "#240")
    }

    @Test
    fun `AC-3 pulse strip shows the day ceiling as a meter with the spend marked as estimate`() {
        val view = PulseView.of(PulseFixtures.aPulse(), PulseFixtures.dayWithLimit("3.20"))

        val meter = view.ceiling as? PulseCeiling.Meter
        assertThat(meter).isNotNull()
        assertThat(meter?.spent).contains("est.").contains("US$ 3,20")
        assertThat(meter?.percent).isEqualTo(32)
        assertThat(meter?.limitState).isEqualTo(LimitState.Within)
    }

    @Test
    fun `AC-3 pulse strip meter follows the limit state the daemon reports`() {
        val view = PulseView.of(PulseFixtures.aPulse(), PulseFixtures.dayWithLimit("10.00", dayReached = true))

        val meter = view.ceiling as? PulseCeiling.Meter
        assertThat(meter?.limitState).isEqualTo(LimitState.Reached)
        assertThat(meter?.percent).isEqualTo(100)
    }

    @Test
    fun `AC-3 pulse strip without a day limit says no ceiling and has no meter`() {
        val view = PulseView.of(PulseFixtures.aPulse(), FeedFixtures.aSummary("3.20"))

        assertThat(view.ceiling).isEqualTo(PulseCeiling.Unlimited("sem teto"))
    }

    @Test
    fun `AC-4 a stale pulse strip keeps the last reading dimmed with the out of date notice`() {
        val live = PulseView.of(PulseFixtures.aPulse(), PulseFixtures.dayWithLimit("3.20"))

        val stale = live.markStale()

        assertThat(stale.freshness).isEqualTo(PulseFreshness.Stale)
        assertThat(stale.notice).containsIgnoringCase("desatualizad")
        assertThat(stale.copy(freshness = PulseFreshness.Live, notice = null)).isEqualTo(live)
    }

    @Test
    fun `AC-4 a pulse strip that never read the daemon invents no state, phrase or number`() {
        val loading = PulseView.loading()
        val staleBeforeAnyReading = loading.markStale()

        assertThat(loading.freshness).isEqualTo(PulseFreshness.Loading)
        for (view in listOf(loading, staleBeforeAnyReading)) {
            assertThat(view.stateText).isNull()
            assertThat(view.phrase).isNull()
            assertThat(view.sessionsText).isNull()
            assertThat(view.squares).isEmpty()
            assertThat(view.activeCards).isEmpty()
            assertThat(view.ceiling).isNull()
        }
        assertThat(staleBeforeAnyReading.freshness).isEqualTo(PulseFreshness.Stale)
    }

    @Test
    fun `AC-5 forecast cell shows the finish time in the local hour`() {
        val at = Instant.parse("2026-10-16T19:15:00Z")

        val cell = ForecastCells.of(QueueForecast.FinishesAt(at, 0), ForecastFixtures.now, ZoneId.of("America/Sao_Paulo"))

        assertThat(cell.headline).isEqualTo("Fila vazia ~16:15")
        assertThat(cell.secondary).isNull()
    }

    @Test
    fun `AC-5 forecast cell says tomorrow when the finish passes midnight`() {
        val at = Instant.parse("2026-10-17T02:30:00Z")

        val cell = ForecastCells.of(QueueForecast.FinishesAt(at, 0), ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(cell.headline).startsWith("Fila vazia").contains("amanhã 02:30")
    }

    @Test
    fun `AC-5 forecast cell shows how many steps the owner holds, in singular and plural`() {
        val at = Instant.parse("2026-10-16T16:15:00Z")

        val two = ForecastCells.of(QueueForecast.FinishesAt(at, 2), ForecastFixtures.now, ZoneOffset.UTC)
        val one = ForecastCells.of(QueueForecast.FinishesAt(at, 1), ForecastFixtures.now, ZoneOffset.UTC)
        val none = ForecastCells.of(QueueForecast.FinishesAt(at, 0), ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(two.secondary).isEqualTo("O dono segura 2 passos")
        assertThat(one.secondary).isEqualTo("O dono segura 1 passo")
        assertThat(none.secondary).isNull()
    }

    @Test
    fun `AC-5 forecast cell of an empty queue says so without a time`() {
        val cell = ForecastCells.of(QueueForecast.Empty, ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(cell.headline).isEqualTo("Fila vazia")
        assertThat(cell.secondary).isNull()
    }

    @Test
    fun `AC-5 forecast cell without a forecast says no forecast with the reason`() {
        val noPool = ForecastCells.of(QueueForecast.Unknown(ForecastGap.NO_POOL), ForecastFixtures.now, ZoneOffset.UTC)
        val noHistory = ForecastCells.of(QueueForecast.Unknown(ForecastGap.NO_HISTORY), ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(noPool.headline).isEqualTo("sem previsão")
        assertThat(noPool.secondary).isEqualTo("sem sessões no pool")
        assertThat(noHistory.headline).isEqualTo("sem previsão")
        assertThat(noHistory.secondary).isEqualTo("sem histórico de tempo")
    }

    @Test
    fun `AC-5 forecast cell of an old daemon that sent no forecast says no forecast`() {
        val cell = ForecastCells.of(null, ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(cell.headline).isEqualTo("sem previsão")
    }

    @Test
    fun `AC-5 forecast cell reads the full sentence for accessibility`() {
        val at = Instant.parse("2026-10-16T16:15:00Z")

        val cell = ForecastCells.of(QueueForecast.FinishesAt(at, 2), ForecastFixtures.now, ZoneOffset.UTC)

        assertThat(cell.description).contains("Fila vazia ~16:15").contains("O dono segura 2 passos")
    }

    @Test
    fun `AC-1 palette with an empty query lists waiting cards, then views, then the other cards`() {
        val entries = paletteEntries(PaletteFixtures.mixedCards(), PaletteView.entries, "")

        assertThat(entries.keys()).containsExactly(
            "card:b", "card:d", "view:BOARD", "view:FEED", "view:SETTINGS", "card:abc123", "card:c",
        )
        assertThat(entries.filterIsInstance<PaletteEntry.Card>().associate { it.card.id to it.waiting })
            .containsEntry("b", true)
            .containsEntry("d", true)
            .containsEntry("abc123", false)
            .containsEntry("c", false)
    }

    @Test
    fun `AC-1 palette shows at most nine rows and keeps the waiting cards first`() {
        val cards = (1..12).map { BoardFixtures.aCard("c$it", Column.CODING, "Plain $it") } +
            BoardFixtures.aCard("w", Column.STAGED, "Waiting")

        val entries = paletteEntries(cards, PaletteView.entries, "")

        assertThat(PALETTE_ROWS).isEqualTo(9)
        assertThat(entries).hasSize(9)
        assertThat(entries.keys().take(4)).containsExactly("card:w", "view:BOARD", "view:FEED", "view:SETTINGS")
    }

    @Test
    fun `AC-2 palette filters by title, id prefix and column name without caring about case`() {
        val cards = PaletteFixtures.mixedCards()

        assertThat(paletteEntries(cards, PaletteView.entries, "LOGIN").keys()).containsExactly("card:c")
        assertThat(paletteEntries(cards, PaletteView.entries, "abc").keys()).containsExactly("card:abc123")
        assertThat(paletteEntries(cards, PaletteView.entries, "spec rev").keys()).containsExactly("card:b")
        assertThat(paletteEntries(cards, PaletteView.entries, "staged").keys()).containsExactly("card:d")
    }

    @Test
    fun `AC-2 palette filters views by label and keeps the order of the empty query`() {
        val cards = PaletteFixtures.mixedCards()

        assertThat(paletteEntries(cards, PaletteView.entries, "quad").keys()).containsExactly("view:BOARD")
        assertThat(paletteEntries(cards, PaletteView.entries, "FEED").keys()).containsExactly("view:FEED")
        assertThat(paletteEntries(cards, PaletteView.entries, "configura").keys()).containsExactly("view:SETTINGS")
        assertThat(paletteEntries(cards, PaletteView.entries, "e").keys())
            .containsSubsequence("card:b", "card:d", "view:BOARD", "view:FEED", "view:SETTINGS")
    }

    @Test
    fun `AC-2 palette without a match is empty and nothing can be chosen`() {
        val entries = paletteEntries(PaletteFixtures.mixedCards(), PaletteView.entries, "zzz-no-match")
        val choice = PaletteState(open = true, query = "zzz-no-match", active = 0).select(entries)

        assertThat(entries).isEmpty()
        assertThat(choice.entry).isNull()
    }

    @Test
    fun `AC-3 arrows move the active row and wrap at both ends`() {
        val state = PaletteState(open = true)

        assertThat(state.move(Direction.DOWN, 3).active).isEqualTo(1)
        assertThat(state.move(Direction.DOWN, 3).move(Direction.DOWN, 3).move(Direction.DOWN, 3).active).isEqualTo(0)
        assertThat(state.move(Direction.UP, 3).active).isEqualTo(2)
        assertThat(state.move(Direction.DOWN, 3).move(Direction.UP, 3).active).isEqualTo(0)
    }

    @Test
    fun `AC-3 Home and End jump to the first and last row`() {
        val middle = PaletteState(open = true, active = 2)

        assertThat(middle.move(Direction.FIRST, 5).active).isEqualTo(0)
        assertThat(middle.move(Direction.LAST, 5).active).isEqualTo(4)
    }

    @Test
    fun `AC-3 the active row stays inside the list after the filter shrinks it`() {
        val deep = PaletteState(open = true, active = 7)

        assertThat(deep.move(Direction.DOWN, 3).active).isBetween(0, 2)
        assertThat(deep.move(Direction.UP, 3).active).isBetween(0, 2)
        assertThat(deep.move(Direction.LAST, 3).active).isEqualTo(2)
        assertThat(deep.move(Direction.DOWN, 0).active).isEqualTo(0)
    }

    @Test
    fun `AC-4 choosing a card row returns that card and closes the palette`() {
        val entries = paletteEntries(PaletteFixtures.mixedCards(), PaletteView.entries, "")
        val state = PaletteState(open = true, query = "", active = 1)

        val choice = state.select(entries)

        assertThat(choice.entry)
            .isEqualTo(PaletteEntry.Card(BoardFixtures.aCard("d", Column.STAGED, "Approve release"), true))
        assertThat(choice.state.open).isFalse()
        assertThat(choice.state.query).isEmpty()
    }

    @Test
    fun `AC-4 choosing a view row returns that view and closes the palette`() {
        val entries = paletteEntries(PaletteFixtures.mixedCards(), PaletteView.entries, "feed")
        val state = PaletteState(open = true, query = "feed", active = 0)

        val choice = state.select(entries)

        assertThat(choice.entry).isEqualTo(PaletteEntry.View(PaletteView.FEED))
        assertThat(choice.state.open).isFalse()
    }

    @Test
    fun `AC-5 the shortcut opens the palette and pressing it again closes it dropping the text`() {
        val closed = PaletteState()

        val opened = closed.toggle()
        val typed = opened.withQuery("abc")
        val toggledOff = typed.toggle()

        assertThat(opened.open).isTrue()
        assertThat(typed.query).isEqualTo("abc")
        assertThat(toggledOff.open).isFalse()
        assertThat(toggledOff.query).isEmpty()
    }

    @Test
    fun `AC-5 Esc or a click on the backdrop closes the palette and discards the text`() {
        val typed = PaletteState(open = true, query = "abc", active = 2)

        val closed = typed.close()

        assertThat(closed.open).isFalse()
        assertThat(closed.query).isEmpty()
        assertThat(closed.active).isEqualTo(0)
        assertThat(closed.toggle().query).isEmpty()
    }

    @Test
    fun `AC-2 a card list row from an old daemon becomes a card that is not blocked, with no time and no cost`() {
        val card = CardFaceFixtures.parse("""{"id":"a1","title":"Old row","column":"CODING"}""")

        val face = CardFace.of(requireNotNull(card) { "the old row was dropped" }, CardFaceFixtures.clock)

        assertThat(face.blocked).isFalse()
        assertThat(face.blockedLabel).isNull()
        assertThat(face.age).isNull()
        assertThat(face.cost).isEqualTo("custo desconhecido")
    }

    @Test
    fun `AC-2 no row is dropped for lacking the new fields or for carrying unreadable ones`() {
        val rows = listOf(
            """{"id":"a","title":"Old","column":"INBOX"}""",
            """{"id":"b","title":"Bad instant","column":"CODING","blocked":false,"enteredAt":"yesterday"}""",
            """{"id":"c","title":"Bad cost","column":"DONE","cost":{"status":"SOMETHING","costUsd":"x"}}""",
            """{"id":"d","title":"Cost without value","column":"DONE","cost":{"status":"KNOWN"}}""",
        )

        val cards = rows.map { CardFaceFixtures.parse(it) }

        assertThat(cards).doesNotContainNull()
        cards.filterNotNull().forEach { card ->
            val face = CardFace.of(card, CardFaceFixtures.clock)
            assertThat(face.age).isNull()
            assertThat(face.cost).isEqualTo("custo desconhecido")
        }
    }

    @Test
    fun `AC-2 a row without id, title or a known column is still not a card`() {
        assertThat(CardFaceFixtures.parse("""{"title":"x","column":"CODING"}""")).isNull()
        assertThat(CardFaceFixtures.parse("""{"id":"a","title":"x","column":"NOWHERE"}""")).isNull()
    }

    @Test
    fun `AC-3 the card says the stage verbatim, the time in it and the estimated cost`() {
        val face = CardFaceFixtures.faceOf(
            column = "READY_FOR_PROD",
            enteredAgo = 360L,
            cost = """{"status":"KNOWN","costUsd":"0.42"}""",
        )

        assertThat(face.stage).isEqualTo("Ready for Prod")
        assertThat(face.age).isEqualTo("há 6 min")
        assertThat(face.cost).isEqualTo("US$ 0,42 est.")
        assertThat(face.costNote).isNull()
    }

    @Test
    fun `AC-3 the time in the stage reads under a minute, minutes and hours with two-digit minutes`() {
        assertThat(CardFaceFixtures.faceOf(enteredAgo = 30L).age).isEqualTo("há < 1 min")
        assertThat(CardFaceFixtures.faceOf(enteredAgo = 0L).age).isEqualTo("há < 1 min")
        assertThat(CardFaceFixtures.faceOf(enteredAgo = 3_599L).age).isEqualTo("há 59 min")
        assertThat(CardFaceFixtures.faceOf(enteredAgo = 3_900L).age).isEqualTo("há 1 h 05 min")
        assertThat(CardFaceFixtures.faceOf(enteredAgo = -45L).age).isEqualTo("há < 1 min")
    }

    @Test
    fun `AC-3 the time is measured against the clock handed in, not the system one`() {
        val card = requireNotNull(CardFaceFixtures.parse(CardFaceFixtures.row(enteredAgo = 600L)))
        val later = Clock.fixed(CardFaceFixtures.now.plusSeconds(3_000), ZoneOffset.UTC)

        assertThat(CardFace.of(card, CardFaceFixtures.clock).age).isEqualTo("há 10 min")
        assertThat(CardFace.of(card, later).age).isEqualTo("há 1 h 00 min")
    }

    @Test
    fun `AC-3 a partial cost keeps the estimate and carries the partial notice`() {
        val face = CardFaceFixtures.faceOf(cost = """{"status":"PARTIAL","costUsd":"1.50"}""")

        assertThat(face.cost).isEqualTo("US$ 1,50 est.")
        assertThat(face.costNote).isEqualTo(Texts.PARTIAL)
    }

    @Test
    fun `AC-3 an unknown, missing or empty cost says unknown and is never shown as zero`() {
        val faces = listOf(
            CardFaceFixtures.faceOf(cost = """{"status":"UNKNOWN"}"""),
            CardFaceFixtures.faceOf(cost = """{"status":"NONE"}"""),
            CardFaceFixtures.faceOf(cost = null),
        )

        faces.forEach { face ->
            assertThat(face.cost).isEqualTo("custo desconhecido")
            assertThat(face.costNote).isNull()
            assertThat(face.description).doesNotContain("0,00")
        }
    }

    @Test
    fun `AC-3 the cost keeps BigDecimal precision and rounds half up to cents`() {
        assertThat(CardFaceFixtures.faceOf(cost = """{"status":"KNOWN","costUsd":"12.345"}""").cost).isEqualTo("US$ 12,35 est.")
        assertThat(CardFaceFixtures.faceOf(cost = """{"status":"KNOWN","costUsd":"0.004"}""").cost).isEqualTo("US$ 0,00 est.")
    }

    @Test
    fun `AC-4 a blocked card says blocked, in the accessibility description too, and stays in its column`() {
        val card = requireNotNull(CardFaceFixtures.parse(CardFaceFixtures.row(blocked = true, column = "CODING")))
        val board = BoardFixtures.empty().applySnapshot(listOf(card))

        val face = CardFace.of(card, CardFaceFixtures.clock)

        assertThat(face.blocked).isTrue()
        assertThat(face.blockedLabel).isEqualTo("bloqueado")
        assertThat(face.description).contains("bloqueado")
        assertThat(board.cardsIn(Column.CODING).map { it.id }).containsExactly("a1")
    }

    @Test
    fun `AC-4 a card that is not blocked has no blocked label and its description does not say blocked`() {
        val face = CardFaceFixtures.faceOf(blocked = false)

        assertThat(face.blocked).isFalse()
        assertThat(face.blockedLabel).isNull()
        assertThat(face.description).doesNotContain("bloqueado")
    }

    @Test
    fun `AC-4 the description holds title, id, stage, time, cost and block`() {
        val face = CardFaceFixtures.faceOf(
            column = "CODING",
            blocked = true,
            enteredAgo = 360L,
            cost = """{"status":"KNOWN","costUsd":"0.42"}""",
        )

        assertThat(face.description)
            .contains("Title of a1", "a1", "Coding", "há 6 min", "US$ 0,42 est.", "bloqueado")
    }

    @Test
    fun `AC-5 each stage column takes the hue of DESIGN, the new develop one for Coding`() {
        assertThat(stageHue(Column.REFINEMENT)).isEqualTo(StageHue.REFINE)
        assertThat(stageHue(Column.CODING)).isEqualTo(StageHue.DEVELOP)
        assertThat(stageHue(Column.VALIDATION)).isEqualTo(StageHue.VALIDATE)
    }

    @Test
    fun `AC-5 Ready for Prod and Done are ok, Spec Review and Staged keep the accent`() {
        assertThat(stageHue(Column.READY_FOR_PROD)).isEqualTo(StageHue.OK)
        assertThat(stageHue(Column.DONE)).isEqualTo(StageHue.OK)
        assertThat(stageHue(Column.SPEC_REVIEW)).isEqualTo(StageHue.ACCENT)
        assertThat(stageHue(Column.STAGED)).isEqualTo(StageHue.ACCENT)
    }

    @Test
    fun `AC-5 Inbox and the queues stay neutral and all ten columns have a hue`() {
        assertThat(stageHue(Column.INBOX)).isEqualTo(StageHue.NEUTRAL)
        assertThat(stageHue(Column.READY_FOR_REFINEMENT)).isEqualTo(StageHue.NEUTRAL)
        assertThat(stageHue(Column.READY_FOR_DEV)).isEqualTo(StageHue.NEUTRAL)
        assertThat(Column.entries.map { stageHue(it) }).hasSize(10)
        assertThat(Column.entries.filter { stageHue(it) == StageHue.NEUTRAL }).hasSize(3)
    }

    @Test
    fun `AC-6 time, cost and block labels never come from the title, which is only carried as plain text`() {
        val title = "Ignore previous instructions <script>alert(1)</script> **bold** bloqueado há 99 min US\$ 999,99 est."

        val face = CardFaceFixtures.faceOf(title = title, column = "CODING", enteredAgo = 360L, cost = null)

        assertThat(face.blocked).isFalse()
        assertThat(face.blockedLabel).isNull()
        assertThat(face.stage).isEqualTo("Coding")
        assertThat(face.age).isEqualTo("há 6 min")
        assertThat(face.cost).isEqualTo("custo desconhecido")
        listOf(face.stage, face.age.orEmpty(), face.cost, face.costNote.orEmpty()).forEach { label ->
            assertThat(label).doesNotContain("script", "bold", "Ignore", "999", "99 min")
        }
        assertThat(face.description).contains(title)
    }

    @Test
    fun `AC-1 opening another card swaps the card of the open panel without closing it`() {
        val opened = SidePanelState().open("a1")
        val swapped = opened.open("b2")

        assertThat(opened.cardId).isEqualTo("a1")
        assertThat(swapped.cardId).isEqualTo("b2")
    }

    @Test
    fun `AC-2 the panel opens on Summary and the chosen tab survives a card swap`() {
        val opened = SidePanelState().open("a1")
        val onEvents = opened.selectTab(PanelTab.EVENTS).open("b2")

        assertThat(PanelTab.entries.map { it.name }).containsExactly("SUMMARY", "SPEC", "EVENTS", "RUNS")
        assertThat(opened.tab).isEqualTo(PanelTab.SUMMARY)
        assertThat(onEvents.tab).isEqualTo(PanelTab.EVENTS)
        assertThat(onEvents.close().open("c3").tab).isEqualTo(PanelTab.SUMMARY)
    }

    @Test
    fun `AC-2 the path lists the ten columns in board order with the verbatim name and the step of each`() {
        val path = cardPath(Column.READY_FOR_DEV)

        assertThat(path.map { it.column }).isEqualTo(Column.entries.toList())
        assertThat(path.map { it.name }).isEqualTo(Column.entries.map { it.displayName })
        assertThat(path.map { it.step }).containsExactly(
            PathStep.TRAVELED, PathStep.TRAVELED, PathStep.TRAVELED, PathStep.TRAVELED,
            PathStep.CURRENT,
            PathStep.NEXT, PathStep.NEXT, PathStep.NEXT, PathStep.NEXT, PathStep.NEXT,
        )
    }

    @Test
    fun `AC-2 the path of the first and last column has no traveled or no next marks and the description names the position`() {
        assertThat(cardPath(Column.INBOX).map { it.step }.count { it == PathStep.TRAVELED }).isEqualTo(0)
        assertThat(cardPath(Column.DONE).map { it.step }.count { it == PathStep.NEXT }).isEqualTo(0)
        assertThat(cardPath(Column.DONE).last().step).isEqualTo(PathStep.CURRENT)
        assertThat(cardPathDescription(Column.READY_FOR_DEV)).isEqualTo("etapa 5 de 10: Ready for Dev")
        assertThat(cardPathDescription(Column.INBOX)).isEqualTo("etapa 1 de 10: Inbox")
    }

    @Test
    fun `AC-3 the width is clamped between 360 and 680 and starts at 480`() {
        val panel = SidePanelState().open("a1")

        assertThat(SidePanelState.MIN_WIDTH).isEqualTo(360)
        assertThat(SidePanelState.MAX_WIDTH).isEqualTo(680)
        assertThat(panel.width).isEqualTo(480)
        assertThat(panel.resize(500).width).isEqualTo(500)
        assertThat(panel.resize(100).width).isEqualTo(360)
        assertThat(panel.resize(5000).width).isEqualTo(680)
        assertThat(panel.resize(360).width).isEqualTo(360)
        assertThat(panel.resize(680).width).isEqualTo(680)
    }

    @Test
    fun `AC-3 the width is kept when the panel closes and reopens`() {
        val reopened = SidePanelState().open("a1").resize(600).close().open("b2")

        assertThat(reopened.width).isEqualTo(600)
    }

    @Test
    fun `AC-4 below 880 px the panel overlays the board and from 880 px it shares the row`() {
        assertThat(panelPlacement(879)).isEqualTo(PanelPlacement.OVERLAY)
        assertThat(panelPlacement(600)).isEqualTo(PanelPlacement.OVERLAY)
        assertThat(panelPlacement(880)).isEqualTo(PanelPlacement.SIDE_BY_SIDE)
        assertThat(panelPlacement(1600)).isEqualTo(PanelPlacement.SIDE_BY_SIDE)
    }

    @Test
    fun `AC-5 Esc outside a text field closes the panel and hands the focus back to the card that opened it`() {
        val opened = SidePanelState().open("a1").open("b2")

        val closed = opened.escape(paletteOpen = false, textFieldFocused = false)

        assertThat(closed.cardId).isNull()
        assertThat(closed.focusReturnCardId).isEqualTo("b2")
    }

    @Test
    fun `AC-5 Esc inside a text field or with the palette open leaves the panel open`() {
        val opened = SidePanelState().open("a1")

        assertThat(opened.escape(paletteOpen = false, textFieldFocused = true)).isEqualTo(opened)
        assertThat(opened.escape(paletteOpen = true, textFieldFocused = false)).isEqualTo(opened)
    }

    @Test
    fun `AC-6 the footer decides only in the owner columns and names where the approval goes`() {
        assertThat(panelFooter(Column.SPEC_REVIEW)).isEqualTo(PanelFooter.Decision(Column.READY_FOR_DEV))
        assertThat(panelFooter(Column.STAGED)).isEqualTo(PanelFooter.Decision(Column.READY_FOR_PROD))
        Column.entries.filter { it !in OWNER_DECISION_COLUMNS }.forEach { column ->
            assertThat(panelFooter(column)).isEqualTo(PanelFooter.None)
        }
    }

    private companion object {
        const val EPSILON = 1e-5f
        val FULL_COUNTS: Map<Column, Int> = Column.entries.associateWith { 1 }
    }
}

object CardFaceFixtures {
    val now: Instant = Instant.parse("2026-10-16T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** One `card.list` row; a null [enteredAgo] or [cost] leaves the field out, like a daemon that does not send it. */
    fun row(
        title: String = "Title of a1",
        column: String = "CODING",
        blocked: Boolean? = null,
        enteredAgo: Long? = null,
        cost: String? = null,
    ): String = buildList {
        add("\"id\":\"a1\"")
        add("\"title\":\"$title\"")
        add("\"column\":\"$column\"")
        blocked?.let { add("\"blocked\":$it") }
        enteredAgo?.let { add("\"enteredAt\":\"${now.minusSeconds(it)}\"") }
        cost?.let { add("\"cost\":$it") }
    }.joinToString(prefix = "{", postfix = "}", separator = ",")

    fun parse(json: String): BoardCard? = parseBoardCard(Json.parseToJsonElement(json))

    fun faceOf(
        title: String = "Title of a1",
        column: String = "CODING",
        blocked: Boolean? = null,
        enteredAgo: Long? = null,
        cost: String? = null,
    ): CardFace {
        val card = parse(row(title, column, blocked, enteredAgo, cost))
        return CardFace.of(requireNotNull(card) { "the row was dropped" }, clock)
    }
}

private fun List<PaletteEntry>.keys(): List<String> = map {
    when (it) {
        is PaletteEntry.Card -> "card:${it.card.id}"
        is PaletteEntry.View -> "view:${it.view.name}"
    }
}

object PaletteFixtures {
    fun mixedCards(): List<BoardCard> = listOf(
        BoardFixtures.aCard("abc123", Column.CODING, "Refactor parser"),
        BoardFixtures.aCard("b", Column.SPEC_REVIEW, "Write the spec"),
        BoardFixtures.aCard("c", Column.DONE, "Fix login"),
        BoardFixtures.aCard("d", Column.STAGED, "Approve release"),
    )
}

object BoardFixtures {
    fun empty(): Board = Board(emptyList(), null)

    fun aCard(id: String, column: Column, title: String = "Title of $id") = BoardCard(id, title, column)

    fun anEvent(
        seq: Long,
        cardId: String,
        type: EventType,
        payload: Map<String, String> = emptyMap(),
    ) = BoardEvent(seq, cardId, type, payload)

    fun aMove(seq: Long, cardId: String, to: Column, type: EventType = EventType.CARD_MOVED) =
        anEvent(seq, cardId, type, mapOf("to" to to.name))

    fun aColumnTime(kind: ColumnTimeKind, seconds: Long, stays: Int = 3) = ColumnTime(kind, seconds, stays)

}

object ForecastFixtures {
    val now: Instant = Instant.parse("2026-10-16T12:00:00Z")
}

object PulseFixtures {
    fun aPulse(
        state: AssistantState = AssistantState.WORKING,
        phrase: String = "Em andamento: #235, #240.",
        capacity: Int = 4,
        active: List<String> = listOf("#235", "#240"),
    ) = Pulse(state, phrase, capacity, active)

    /** A day with a US$ 10.00 limit and [spent] already spent. */
    fun dayWithLimit(spent: String, dayReached: Boolean = false) =
        FeedFixtures.aSummary(spent, limits = CostLimitsInfo(null, BigDecimal("10.00")), dayReached = dayReached)
}
