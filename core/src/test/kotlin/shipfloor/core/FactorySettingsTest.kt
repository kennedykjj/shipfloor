package shipfloor.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

object FactorySettingsFixtures {
    fun aWorkHours(
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(17, 0),
        zone: String = "America/Sao_Paulo",
    ) = WorkHoursSetting(days, start, end, zone)

    fun aSettings(
        poolSize: Int = 3,
        workHours: WorkHoursSetting? = aWorkHours(),
        cost: CostSetting = CostSetting(BigDecimal("5"), BigDecimal("20")),
        notifications: NotificationSetting = NotificationSetting(setOf(NotificationType.RUN_FAILED), null, null),
    ) = FactorySettings(poolSize, workHours, cost, notifications)
}

class FactorySettingsTest {

    private fun fields(settings: FactorySettings, costUnlimitedAllowed: Boolean = true): List<String> =
        FactorySettingsRules.validate(settings, costUnlimitedAllowed).map { it.field }

    @Test
    fun `AC-4 valid settings have no issues`() {
        assertThat(FactorySettingsRules.validate(FactorySettingsFixtures.aSettings())).isEmpty()
    }

    @Test
    fun `AC-4 pool zero is refused naming the pool field`() {
        val issues = FactorySettingsRules.validate(FactorySettingsFixtures.aSettings(poolSize = 0))

        assertThat(issues.map { it.field }).containsExactly("poolSize")
        assertThat(issues.single().reason).isNotBlank()
    }

    @Test
    fun `AC-4 work hours ending before or at the start are refused naming the work hours field`() {
        val before = FactorySettingsFixtures.aWorkHours(start = LocalTime.of(18, 0), end = LocalTime.of(9, 0))
        val same = FactorySettingsFixtures.aWorkHours(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0))

        assertThat(fields(FactorySettingsFixtures.aSettings(workHours = before))).containsExactly("workHours")
        assertThat(fields(FactorySettingsFixtures.aSettings(workHours = same))).containsExactly("workHours")
    }

    @Test
    fun `AC-4 negative or zero cost limits are refused naming each cost field`() {
        val negativeDay = FactorySettingsFixtures.aSettings(cost = CostSetting(BigDecimal("5"), BigDecimal("-1")))
        val zeroCard = FactorySettingsFixtures.aSettings(cost = CostSetting(BigDecimal.ZERO, BigDecimal("20")))

        assertThat(fields(negativeDay)).containsExactly("cost.perDayUsd")
        assertThat(fields(zeroCard)).containsExactly("cost.perCardUsd")
    }

    @Test
    fun `AC-4 empty cost limits are accepted only when unlimited cost is allowed`() {
        val none = FactorySettingsFixtures.aSettings(cost = CostSetting(null, null))

        assertThat(fields(none, costUnlimitedAllowed = true)).isEmpty()
        assertThat(fields(none, costUnlimitedAllowed = false)).containsExactlyInAnyOrder("cost.perCardUsd", "cost.perDayUsd")
    }
}
