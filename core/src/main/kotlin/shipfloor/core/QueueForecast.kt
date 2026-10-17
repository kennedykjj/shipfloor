package shipfloor.core

import java.time.Instant

sealed interface QueueForecast {
    data object Empty : QueueForecast

    data class FinishesAt(val at: Instant, val ownerSteps: Int) : QueueForecast

    data class Unknown(val gap: ForecastGap) : QueueForecast
}

enum class ForecastGap { NO_POOL, NO_HISTORY }

/** Baseline forecast of when the machine work in the queue ends; the owner's wait is not added. */
object QueueForecasts {
    private val remainingWork: Map<Column, List<Column>> = mapOf(
        Column.READY_FOR_REFINEMENT to listOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION),
        Column.REFINEMENT to listOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION),
        Column.READY_FOR_DEV to listOf(Column.CODING, Column.VALIDATION),
        Column.CODING to listOf(Column.CODING, Column.VALIDATION),
        Column.VALIDATION to listOf(Column.VALIDATION),
    )

    fun of(columns: List<Column>, medians: Map<Column, ColumnTime>, poolCapacity: Int, now: Instant): QueueForecast {
        val owed = columns.mapNotNull { remainingWork[it] }
        if (owed.isEmpty()) return QueueForecast.Empty
        if (poolCapacity <= 0) return QueueForecast.Unknown(ForecastGap.NO_POOL)
        var total = 0L
        for (column in owed.flatten()) {
            val median = medians[column]?.takeIf { it.kind == ColumnTimeKind.MEDIAN }
                ?: return QueueForecast.Unknown(ForecastGap.NO_HISTORY)
            total += median.seconds
        }
        val seconds = (total + poolCapacity - 1) / poolCapacity
        return QueueForecast.FinishesAt(now.plusSeconds(seconds), columns.count { it in OWNER_WAIT_COLUMNS })
    }
}
