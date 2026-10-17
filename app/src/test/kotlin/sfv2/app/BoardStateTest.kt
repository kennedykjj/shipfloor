package sfv2.app

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
import sfv2.core.AssistantState
import sfv2.core.Column
import sfv2.core.ColumnTime
import sfv2.core.ColumnTimeKind
import sfv2.core.EventType
import sfv2.core.ForecastGap
import sfv2.core.OWNER_WAIT_COLUMNS
import sfv2.core.QueueForecast

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
 *
 * Column map (pure, in `BoardMap.kt`): `boardMap`, `scrollTarget` and `edgeFades`. Total width is the sum of the
 * column widths plus the gaps between them; `maxScroll = max(0, total - viewportWidth)`.
 */
class BoardStateTest {
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
    fun `AC-1 map has one box per column in board order with the card counts`() {
        val board = BoardFixtures.empty().applySnapshot(
            listOf(
                BoardFixtures.aCard("a", Column.INBOX),
                BoardFixtures.aCard("b", Column.CODING),
                BoardFixtures.aCard("c", Column.CODING),
            ),
        )

        val map = boardMap(board, BoardFixtures.uniformWidths(200), gap = 10, viewportWidth = 1000, scrollOffset = 0)

        assertThat(map.boxes.map { it.column }).containsExactlyElementsOf(Column.entries)
        assertThat(map.boxes.map { it.count }).containsExactlyElementsOf(
            Column.entries.map { board.cardsIn(it).size },
        )
        assertThat(map.boxes.first { it.column == Column.CODING }.count).isEqualTo(2)
    }

    @Test
    fun `AC-1 map box width is proportional to the rendered column width`() {
        val widths = BoardFixtures.uniformWidths(200) + (Column.CODING to 400)
        val total = 9 * 200 + 400 + 9 * 10

        val map = boardMap(BoardFixtures.empty(), widths, gap = 10, viewportWidth = 1000, scrollOffset = 0)

        val inbox = map.boxes.first { it.column == Column.INBOX }
        val coding = map.boxes.first { it.column == Column.CODING }
        val second = map.boxes[1]
        assertThat(inbox.start).isCloseTo(0f, within(EPSILON))
        assertThat(inbox.share).isCloseTo(200f / total, within(EPSILON))
        assertThat(coding.share).isCloseTo(400f / total, within(EPSILON))
        assertThat(coding.share).isCloseTo(inbox.share * 2, within(EPSILON))
        assertThat(second.start).isCloseTo(210f / total, within(EPSILON))
        val last = map.boxes.last()
        assertThat(last.start + last.share).isCloseTo(1f, within(EPSILON))
    }

    @Test
    fun `AC-2 viewport outline starts and ends at the matching proportion of the map`() {
        val total = 10 * 200 + 9 * 10

        val map = boardMap(
            BoardFixtures.empty(), BoardFixtures.uniformWidths(200), gap = 10, viewportWidth = 1000, scrollOffset = 500,
        )

        assertThat(map.viewStart).isCloseTo(500f / total, within(EPSILON))
        assertThat(map.viewShare).isCloseTo(1000f / total, within(EPSILON))
    }

    @Test
    fun `AC-2 a viewport as wide as the board or wider covers the whole map`() {
        val total = 10 * 200 + 9 * 10
        val widths = BoardFixtures.uniformWidths(200)

        val exact = boardMap(BoardFixtures.empty(), widths, gap = 10, viewportWidth = total, scrollOffset = 0)
        val wider = boardMap(BoardFixtures.empty(), widths, gap = 10, viewportWidth = total + 500, scrollOffset = 0)

        assertThat(exact.viewStart).isCloseTo(0f, within(EPSILON))
        assertThat(exact.viewShare).isCloseTo(1f, within(EPSILON))
        assertThat(wider.viewStart).isCloseTo(0f, within(EPSILON))
        assertThat(wider.viewShare).isCloseTo(1f, within(EPSILON))
    }

    @Test
    fun `AC-3 click target aligns the column to the left edge of the viewport`() {
        val widths = BoardFixtures.uniformWidths(200)

        val target = scrollTarget(Column.entries[3], widths, gap = 10, viewportWidth = 1000)

        assertThat(target).isEqualTo(3 * 210)
    }

    @Test
    fun `AC-3 click target never goes past the end of the board`() {
        val widths = BoardFixtures.uniformWidths(200)
        val maxScroll = 10 * 200 + 9 * 10 - 1000

        val target = scrollTarget(Column.entries.last(), widths, gap = 10, viewportWidth = 1000)

        assertThat(target).isEqualTo(maxScroll)
    }

    @Test
    fun `AC-3 click target is never below zero, even when the viewport is wider than the board`() {
        val widths = BoardFixtures.uniformWidths(200)

        val first = scrollTarget(Column.entries.first(), widths, gap = 10, viewportWidth = 1000)
        val wide = scrollTarget(Column.entries.last(), widths, gap = 10, viewportWidth = 5000)

        assertThat(first).isEqualTo(0)
        assertThat(wide).isEqualTo(0)
    }

    @Test
    fun `AC-4 a board without cards still maps ten boxes with count zero`() {
        val map = boardMap(
            BoardFixtures.empty(), BoardFixtures.uniformWidths(200), gap = 10, viewportWidth = 1000, scrollOffset = 0,
        )

        assertThat(map.boxes).hasSize(10)
        assertThat(map.boxes.map { it.count }).allMatch { it == 0 }
        assertThat(map.boxes.map { it.column }).containsExactlyElementsOf(Column.entries)
    }

    @Test
    fun `AC-4 a zero viewport width gives an empty outline without dividing by zero`() {
        val map = boardMap(
            BoardFixtures.empty(), BoardFixtures.uniformWidths(200), gap = 10, viewportWidth = 0, scrollOffset = 0,
        )
        val degenerate = boardMap(
            BoardFixtures.empty(), BoardFixtures.uniformWidths(0), gap = 0, viewportWidth = 0, scrollOffset = 0,
        )

        assertThat(map.boxes).hasSize(10)
        assertThat(map.viewShare).isCloseTo(0f, within(EPSILON))
        assertThat(degenerate.boxes).hasSize(10)
        assertThat(degenerate.viewShare).isCloseTo(0f, within(EPSILON))
        assertThat(degenerate.boxes.map { it.share }).allMatch { !it.isNaN() }
        assertThat(degenerate.viewStart.isNaN()).isFalse()
    }

    @Test
    fun `AC-5 only the right edge fades at the start of the scroll`() {
        assertThat(edgeFades(scrollOffset = 0, maxScroll = 500)).isEqualTo(EdgeFades(start = false, end = true))
    }

    @Test
    fun `AC-5 both edges fade in the middle of the scroll`() {
        assertThat(edgeFades(scrollOffset = 250, maxScroll = 500)).isEqualTo(EdgeFades(start = true, end = true))
    }

    @Test
    fun `AC-5 only the left edge fades at the end of the scroll`() {
        assertThat(edgeFades(scrollOffset = 500, maxScroll = 500)).isEqualTo(EdgeFades(start = true, end = false))
    }

    @Test
    fun `AC-5 no edge fades when there is nothing to scroll`() {
        assertThat(edgeFades(scrollOffset = 0, maxScroll = 0)).isEqualTo(EdgeFades(start = false, end = false))
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

    @Test
    fun `AC-1 resizing a column sets its width and leaves the others alone`() {
        val state = ColumnLayoutState().resize(Column.CODING, 300)

        assertThat(state.widthOf(Column.CODING)).isEqualTo(300)
        assertThat(Column.entries.filter { it != Column.CODING }.map { state.widthOf(it) }).allMatch { it == 220 }
    }

    @Test
    fun `AC-1 the width is clamped to the minimum of the density and to 480`() {
        val cards = ColumnLayoutState()
        val chips = ColumnLayoutState().withDensity(BoardDensity.CHIPS)

        assertThat(ColumnLayoutState.MAX_WIDTH).isEqualTo(480)
        assertThat(cards.resize(Column.CODING, 150).widthOf(Column.CODING)).isEqualTo(200)
        assertThat(cards.resize(Column.CODING, 900).widthOf(Column.CODING)).isEqualTo(480)
        assertThat(chips.resize(Column.CODING, 50).widthOf(Column.CODING)).isEqualTo(96)
        assertThat(chips.resize(Column.CODING, 900).widthOf(Column.CODING)).isEqualTo(480)
    }

    @Test
    fun `AC-2 resetting a column brings back only its default width of the current density`() {
        val state = ColumnLayoutState().resize(Column.CODING, 300).resize(Column.INBOX, 260)

        val after = state.reset(Column.CODING)

        assertThat(after.widthOf(Column.CODING)).isEqualTo(220)
        assertThat(after.widthOf(Column.INBOX)).isEqualTo(260)
        assertThat(state.withDensity(BoardDensity.CHIPS).resize(Column.CODING, 200).reset(Column.CODING).widthOf(Column.CODING))
            .isEqualTo(96)
    }

    @Test
    fun `AC-4 a wide window splits the spare room in proportion to the widths and fills it exactly`() {
        val state = ColumnLayoutState().resize(Column.CODING, 440)

        val widths = columnWidths(state, FULL_COUNTS, viewportDp = 3000, gap = 10)

        assertThat(widths.keys).containsExactlyElementsOf(Column.entries)
        assertThat(widths.values.sum() + 9 * 10).isEqualTo(3000)
        assertThat(widths.getValue(Column.CODING).toFloat() / widths.getValue(Column.INBOX))
            .isCloseTo(2f, within(0.05f))
        assertThat(widths.values).allMatch { it > 220 }
    }

    @Test
    fun `AC-4 the spare room goes only to expanded columns and collapsed ones keep their strip`() {
        val counts = FULL_COUNTS + (Column.INBOX to 0) + (Column.DONE to 0)

        val widths = columnWidths(ColumnLayoutState(), counts, viewportDp = 3000, gap = 10)

        assertThat(widths.getValue(Column.INBOX)).isEqualTo(46)
        assertThat(widths.getValue(Column.DONE)).isEqualTo(46)
        assertThat(widths.values.sum() + 9 * 10).isEqualTo(3000)
        assertThat(Column.entries.filter { it != Column.INBOX && it != Column.DONE }.map { widths.getValue(it) })
            .allMatch { it > 220 }
    }

    @Test
    fun `AC-5 a narrow window keeps every column at or above the minimum of the density`() {
        val state = ColumnLayoutState().resize(Column.CODING, 200)

        val widths = columnWidths(state, FULL_COUNTS, viewportDp = 1000, gap = 10)

        assertThat(widths.values).allMatch { it >= 200 }
        assertThat(widths.getValue(Column.CODING)).isEqualTo(200)
        assertThat(widths.values.sum() + 9 * 10).isGreaterThan(1000)
    }

    @Test
    fun `AC-5 with fit off no column grows even in a wide window`() {
        val state = ColumnLayoutState().withFit(false).resize(Column.CODING, 300)

        val widths = columnWidths(state, FULL_COUNTS, viewportDp = 5000, gap = 10)

        assertThat(widths.getValue(Column.CODING)).isEqualTo(300)
        assertThat(Column.entries.filter { it != Column.CODING }.map { widths.getValue(it) }).allMatch { it == 220 }
    }

    @Test
    fun `AC-5 fit is on by default`() {
        assertThat(ColumnLayoutState().fit).isTrue()
        assertThat(ColumnLayoutState().density).isEqualTo(BoardDensity.CARDS)
        assertThat(ColumnLayoutState().withFit(false).withFit(true).fit).isTrue()
    }

    @Test
    fun `AC-6 an empty column becomes a 46 strip until the owner restores it`() {
        val counts = FULL_COUNTS + (Column.INBOX to 0)
        val state = ColumnLayoutState().withFit(false)

        val collapsed = columnWidths(state, counts, viewportDp = 5000, gap = 10)
        val restored = columnWidths(state.restore(Column.INBOX), counts, viewportDp = 5000, gap = 10)

        assertThat(ColumnLayoutState.COLLAPSED_WIDTH).isEqualTo(46)
        assertThat(collapsed.getValue(Column.INBOX)).isEqualTo(46)
        assertThat(collapsed.getValue(Column.CODING)).isEqualTo(220)
        assertThat(restored.getValue(Column.INBOX)).isEqualTo(220)
    }

    @Test
    fun `AC-6 a restored column comes back at the width the owner gave it`() {
        val counts = FULL_COUNTS + (Column.INBOX to 0)
        val state = ColumnLayoutState().withFit(false).resize(Column.INBOX, 300)

        val widths = columnWidths(state.restore(Column.INBOX), counts, viewportDp = 5000, gap = 10)

        assertThat(widths.getValue(Column.INBOX)).isEqualTo(300)
    }

    @Test
    fun `AC-6 a column with cards is never a strip`() {
        val widths = columnWidths(ColumnLayoutState().withFit(false), FULL_COUNTS, viewportDp = 5000, gap = 10)

        assertThat(widths.values).allMatch { it == 220 }
    }

    @Test
    fun `AC-7 changing the density keeps the widths the owner set for the other density`() {
        val cards = ColumnLayoutState().resize(Column.CODING, 300)

        val chips = cards.withDensity(BoardDensity.CHIPS)
        val chipsResized = chips.resize(Column.CODING, 150)
        val backToCards = chipsResized.withDensity(BoardDensity.CARDS)
        val backToChips = backToCards.withDensity(BoardDensity.CHIPS)

        assertThat(chips.widthOf(Column.CODING)).isEqualTo(96)
        assertThat(backToCards.widthOf(Column.CODING)).isEqualTo(300)
        assertThat(backToChips.widthOf(Column.CODING)).isEqualTo(150)
    }

    @Test
    fun `AC-7 the densities have their own minimum and default width`() {
        assertThat(BoardDensity.entries.map { it.name }).containsExactly("CARDS", "CHIPS")
        assertThat(BoardDensity.CARDS.minWidth).isEqualTo(200)
        assertThat(BoardDensity.CARDS.defaultWidth).isEqualTo(220)
        assertThat(BoardDensity.CHIPS.minWidth).isEqualTo(96)
        assertThat(BoardDensity.CHIPS.defaultWidth).isEqualTo(96)
    }

    @Test
    fun `AC-8 the arrow keys move a column by 16 dp`() {
        val state = ColumnLayoutState().resize(Column.CODING, 300)
        val step = ColumnLayoutState.KEY_STEP

        assertThat(step).isEqualTo(16)
        assertThat(state.resize(Column.CODING, state.widthOf(Column.CODING) + step).widthOf(Column.CODING)).isEqualTo(316)
        assertThat(state.resize(Column.CODING, state.widthOf(Column.CODING) - step).widthOf(Column.CODING)).isEqualTo(284)
    }

    @Test
    fun `AC-8 the arrow keys stop at the limits of the density`() {
        val step = ColumnLayoutState.KEY_STEP
        val atMax = ColumnLayoutState().resize(Column.CODING, 480)
        val atMin = ColumnLayoutState().resize(Column.CODING, 200)

        assertThat(atMax.resize(Column.CODING, atMax.widthOf(Column.CODING) + step).widthOf(Column.CODING)).isEqualTo(480)
        assertThat(atMin.resize(Column.CODING, atMin.widthOf(Column.CODING) - step).widthOf(Column.CODING)).isEqualTo(200)
    }

    @Test
    fun `AC-8 the minimap and the scroll target work on the rendered widths`() {
        val widths = columnWidths(ColumnLayoutState().resize(Column.CODING, 440), FULL_COUNTS, viewportDp = 3000, gap = 10)

        val map = boardMap(BoardFixtures.empty(), widths, gap = 10, viewportWidth = 3000, scrollOffset = 0)
        val target = scrollTarget(Column.entries[3], widths, gap = 10, viewportWidth = 1000)

        assertThat(map.viewShare).isCloseTo(1f, within(EPSILON))
        assertThat(map.boxes.first { it.column == Column.CODING }.share)
            .isCloseTo(widths.getValue(Column.CODING) / 3000f, within(EPSILON))
        assertThat(target).isEqualTo(Column.entries.take(3).sumOf { widths.getValue(it) + 10 })
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

    fun uniformWidths(width: Int): Map<Column, Int> = Column.entries.associateWith { width }
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
