package shipfloor.core

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ColumnTimesTest {

    private val from = ColumnTimesFixtures.t0
    private val to = ColumnTimesFixtures.t0.plusSeconds(86_400)

    private fun times(vararg cards: List<Event>): Map<Column, ColumnTime> = ColumnTimes.of(cards.toList(), from, to)

    @Test
    fun `AC-1 work columns carry the median in seconds of the stays that ended in the period`() {
        val result = times(
            ColumnTimesFixtures.aCardThrough(Column.CODING to 60L, Column.VALIDATION to 30L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 600L, Column.VALIDATION to 90L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 120L, Column.REFINEMENT to 45L),
        )

        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 120L, 3))
        assertThat(result[Column.VALIDATION]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 60L, 2))
        assertThat(result[Column.REFINEMENT]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 45L, 1))
    }

    @Test
    fun `AC-1 an even count takes the truncated mean of the two central stays`() {
        val result = times(
            ColumnTimesFixtures.aCardThrough(Column.CODING to 100L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 101L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 5_000L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 10L),
        )

        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 100L, 4))
    }

    @Test
    fun `AC-2 gate columns carry the truncated mean in seconds labeled as wait`() {
        val result = times(
            ColumnTimesFixtures.aCardThrough(Column.SPEC_REVIEW to 600L, Column.STAGED to 10L),
            ColumnTimesFixtures.aCardThrough(Column.SPEC_REVIEW to 1_200L, Column.STAGED to 11L),
            ColumnTimesFixtures.aCardThrough(Column.SPEC_REVIEW to 80_000L),
        )

        assertThat(result[Column.SPEC_REVIEW]).isEqualTo(ColumnTime(ColumnTimeKind.MEAN_WAIT, 27_266L, 3))
        assertThat(result[Column.STAGED]).isEqualTo(ColumnTime(ColumnTimeKind.MEAN_WAIT, 10L, 2))
    }

    @Test
    fun `AC-3 a column with no concluded stay has no value, never zero`() {
        val result = times(ColumnTimesFixtures.aCardThrough(Column.CODING to 60L))

        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 60L, 1))
        assertThat(result).doesNotContainKeys(Column.REFINEMENT, Column.VALIDATION, Column.SPEC_REVIEW, Column.STAGED)
        assertThat(ColumnTimes.of(emptyList(), from, to)).isEmpty()
        assertThat(ColumnTimes.of(listOf(emptyList()), from, to)).isEmpty()
    }

    @Test
    fun `AC-3 columns that are neither work nor gate never appear`() {
        val result = times(
            ColumnTimesFixtures.aCardThrough(
                Column.READY_FOR_REFINEMENT to 10L,
                Column.REFINEMENT to 20L,
                Column.SPEC_REVIEW to 30L,
                Column.READY_FOR_DEV to 40L,
                Column.CODING to 50L,
                Column.VALIDATION to 60L,
                Column.STAGED to 70L,
                Column.READY_FOR_PROD to 80L,
            ),
        )

        assertThat(result.keys).containsExactlyInAnyOrder(
            Column.REFINEMENT, Column.SPEC_REVIEW, Column.CODING, Column.VALIDATION, Column.STAGED,
        )
        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 50L, 1))
        assertThat(result[Column.SPEC_REVIEW]).isEqualTo(ColumnTime(ColumnTimeKind.MEAN_WAIT, 30L, 1))
    }

    @Test
    fun `AC-3 a stay still open does not enter the calculation`() {
        val withOpen = times(
            ColumnTimesFixtures.aCardThrough(Column.CODING to 60L),
            ColumnTimesFixtures.aCardThrough(Column.CODING to 9_999L, endsIn = null),
        )
        val onlyOpen = times(ColumnTimesFixtures.aCardThrough(Column.CODING to 9_999L, endsIn = null))

        assertThat(withOpen[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 60L, 1))
        assertThat(onlyOpen).doesNotContainKey(Column.CODING)
    }

    @Test
    fun `AC-3 only stays whose end is in the period count, from inclusive and to exclusive`() {
        val endsBefore = ColumnTimesFixtures.aCardThrough(Column.CODING to 1_000L, start = from.minusSeconds(1_001))
        val endsAtFrom = ColumnTimesFixtures.aCardThrough(Column.CODING to 100L, start = from.minusSeconds(100))
        val endsAtTo = ColumnTimesFixtures.aCardThrough(Column.CODING to 7_000L, start = to.minusSeconds(7_000))
        val endsInside = ColumnTimesFixtures.aCardThrough(Column.CODING to 300L, start = from.plusSeconds(10))

        val result = times(endsBefore, endsAtFrom, endsAtTo, endsInside)

        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 200L, 2))
    }

    @Test
    fun `AC-4 each concluded visit to the same column is a separate stay`() {
        val result = times(
            ColumnTimesFixtures.aCardThrough(
                Column.CODING to 100L,
                Column.VALIDATION to 40L,
                Column.CODING to 300L,
            ),
        )

        assertThat(result[Column.CODING]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 200L, 2))
        assertThat(result[Column.VALIDATION]).isEqualTo(ColumnTime(ColumnTimeKind.MEDIAN, 40L, 1))
    }
}

object ColumnTimesFixtures {
    val t0: Instant = Instant.parse("2026-10-16T03:00:00Z")

    private fun anEvent(id: String, at: Instant, type: EventType, payload: Map<String, String>) =
        Event(EventId(id), at, Actor.Owner, type, payload)

    /**
     * A card created in Inbox at [start] that sits in each given column for the given seconds, in order.
     * [endsIn] closes the last stay with a move; null leaves the card in its last column (stay still open).
     */
    fun aCardThrough(vararg stays: Pair<Column, Long>, start: Instant = t0, endsIn: Column? = Column.DONE): List<Event> {
        val events = mutableListOf(anEvent("created-$start", start, EventType.CARD_CREATED, mapOf("title" to "T")))
        var previous = Column.INBOX
        var at = start
        var n = 0
        for ((column, seconds) in stays) {
            events += anEvent("move-$start-${n++}", at, EventType.CARD_MOVED, mapOf("from" to previous.name, "to" to column.name))
            previous = column
            at = at.plusSeconds(seconds)
        }
        if (endsIn != null) {
            events += anEvent("move-$start-${n}", at, EventType.CARD_MOVED, mapOf("from" to previous.name, "to" to endsIn.name))
        }
        return events
    }
}
