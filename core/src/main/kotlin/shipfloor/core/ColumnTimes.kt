package shipfloor.core

import java.time.Duration
import java.time.Instant

enum class ColumnTimeKind { MEDIAN, MEAN_WAIT }

data class ColumnTime(val kind: ColumnTimeKind, val seconds: Long, val stays: Int)

/** Time per column from card events: median for work columns, mean wait for gate columns. */
object ColumnTimes {
    private val medianColumns = setOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION)
    private val meanWaitColumns = setOf(Column.SPEC_REVIEW, Column.STAGED)

    /** Stays whose end falls in [from, toExclusive); open stays and columns without stays are left out. */
    fun of(eventsByCard: List<List<Event>>, from: Instant, toExclusive: Instant): Map<Column, ColumnTime> {
        val stays = mutableMapOf<Column, MutableList<Long>>()
        for (events in eventsByCard) {
            for ((column, seconds, end) in concludedStays(events)) {
                if (end.isBefore(from) || !end.isBefore(toExclusive)) continue
                stays.getOrPut(column) { mutableListOf() }.add(seconds)
            }
        }
        val result = mutableMapOf<Column, ColumnTime>()
        for (column in Column.entries) {
            val durations = stays[column]?.sorted()?.takeIf { it.isNotEmpty() } ?: continue
            when (column) {
                in medianColumns -> result[column] = ColumnTime(ColumnTimeKind.MEDIAN, median(durations), durations.size)
                in meanWaitColumns -> result[column] = ColumnTime(ColumnTimeKind.MEAN_WAIT, durations.sum() / durations.size, durations.size)
                else -> Unit
            }
        }
        return result
    }

    private fun median(sorted: List<Long>): Long {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private data class Stay(val column: Column, val seconds: Long, val end: Instant)

    /** Each CARD_MOVED closes the stay in the previous column; the card starts in Inbox at CARD_CREATED. */
    private fun concludedStays(events: List<Event>): List<Stay> {
        var since = events.firstOrNull { it.type == EventType.CARD_CREATED }?.at ?: return emptyList()
        var column: Column? = Column.INBOX
        val stays = mutableListOf<Stay>()
        for (move in events.filter { it.type == EventType.CARD_MOVED }) {
            column?.let { stays += Stay(it, Duration.between(since, move.at).seconds, move.at) }
            column = Column.entries.firstOrNull { it.name == move.payload["to"] }
            since = move.at
        }
        return stays
    }
}
