package shipfloor.core

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueForecastTest {

    private val now = QueueForecastFixtures.now

    private fun forecast(
        vararg columns: Column,
        medians: Map<Column, ColumnTime> = QueueForecastFixtures.medians(),
        capacity: Int = 1,
    ): QueueForecast = QueueForecasts.of(columns.toList(), medians, capacity, now)

    @Test
    fun `AC-1 a card in Ready for Refinement or Refinement still owes the three work columns`() {
        // 600 + 1200 + 300
        val expected = QueueForecast.FinishesAt(now.plusSeconds(2_100), 0)

        assertThat(forecast(Column.READY_FOR_REFINEMENT)).isEqualTo(expected)
        assertThat(forecast(Column.REFINEMENT)).isEqualTo(expected)
    }

    @Test
    fun `AC-1 a card in Ready for Dev or Coding still owes Coding and Validation`() {
        // 1200 + 300
        val expected = QueueForecast.FinishesAt(now.plusSeconds(1_500), 0)

        assertThat(forecast(Column.READY_FOR_DEV)).isEqualTo(expected)
        assertThat(forecast(Column.CODING)).isEqualTo(expected)
    }

    @Test
    fun `AC-1 a card in Validation owes only Validation`() {
        assertThat(forecast(Column.VALIDATION)).isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(300), 0))
    }

    @Test
    fun `AC-1 the total of every card is divided by the pool capacity`() {
        // (2100 + 1500 + 1500 + 300) / 2
        val result = forecast(Column.REFINEMENT, Column.READY_FOR_DEV, Column.CODING, Column.VALIDATION, capacity = 2)

        assertThat(result).isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(2_700), 0))
    }

    @Test
    fun `AC-1 the division rounds up to a whole second`() {
        val medians = QueueForecastFixtures.medians(validation = 301L)

        val result = forecast(Column.VALIDATION, medians = medians, capacity = 2)

        assertThat(result).isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(151), 0))
    }

    @Test
    fun `AC-2 cards in Spec Review and Staged add no wait and are counted as held by the owner`() {
        val result = forecast(Column.VALIDATION, Column.SPEC_REVIEW, Column.SPEC_REVIEW, Column.STAGED)

        assertThat(result).isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(300), 3))
    }

    @Test
    fun `AC-2 cards in Inbox, Ready for Prod and Done are not part of the forecast`() {
        val result = forecast(Column.VALIDATION, Column.INBOX, Column.READY_FOR_PROD, Column.DONE)

        assertThat(result).isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(300), 0))
    }

    @Test
    fun `AC-3 with no card needing machine work the queue is empty and has no time`() {
        assertThat(forecast()).isEqualTo(QueueForecast.Empty)
        assertThat(forecast(Column.INBOX, Column.SPEC_REVIEW, Column.STAGED, Column.READY_FOR_PROD, Column.DONE))
            .isEqualTo(QueueForecast.Empty)
    }

    @Test
    fun `AC-3 a pool capacity of zero or less has no forecast for lack of pool`() {
        assertThat(forecast(Column.CODING, capacity = 0)).isEqualTo(QueueForecast.Unknown(ForecastGap.NO_POOL))
        assertThat(forecast(Column.CODING, capacity = -2)).isEqualTo(QueueForecast.Unknown(ForecastGap.NO_POOL))
    }

    @Test
    fun `AC-3 a needed work column without a median has no forecast for lack of history`() {
        val withoutCoding = QueueForecastFixtures.medians().minus(Column.CODING)

        assertThat(forecast(Column.CODING, medians = withoutCoding)).isEqualTo(QueueForecast.Unknown(ForecastGap.NO_HISTORY))
        assertThat(forecast(Column.READY_FOR_REFINEMENT, medians = emptyMap())).isEqualTo(QueueForecast.Unknown(ForecastGap.NO_HISTORY))
    }

    @Test
    fun `AC-3 a missing median of a column no card still owes does not block the forecast`() {
        val onlyValidation = mapOf(Column.VALIDATION to QueueForecastFixtures.median(300L))

        assertThat(forecast(Column.VALIDATION, medians = onlyValidation))
            .isEqualTo(QueueForecast.FinishesAt(now.plusSeconds(300), 0))
    }
}

object QueueForecastFixtures {
    val now: Instant = Instant.parse("2026-10-16T12:00:00Z")

    fun median(seconds: Long) = ColumnTime(ColumnTimeKind.MEDIAN, seconds, 3)

    fun medians(refinement: Long = 600L, coding: Long = 1_200L, validation: Long = 300L): Map<Column, ColumnTime> = mapOf(
        Column.REFINEMENT to median(refinement),
        Column.CODING to median(coding),
        Column.VALIDATION to median(validation),
    )
}
