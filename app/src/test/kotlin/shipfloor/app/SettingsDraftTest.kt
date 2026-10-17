package shipfloor.app

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import shipfloor.core.CostSetting
import shipfloor.core.FactorySettings
import shipfloor.core.NotificationSetting
import shipfloor.core.NotificationType

object SettingsDraftFixtures {
    val saved = FactorySettings(
        poolSize = 2,
        workHours = null,
        cost = CostSetting(BigDecimal("5"), BigDecimal("20")),
        notifications = NotificationSetting(setOf(NotificationType.RUN_FAILED), null, null),
    )

    /** Records what it was asked to save and answers with a preset result. */
    class FakeClient(var result: SaveResult) : SettingsClient {
        val sent = mutableListOf<FactorySettings>()

        override fun load(): LoadResult = error("load is not used by these tests")

        override fun save(settings: FactorySettings): SaveResult {
            sent.add(settings)
            return result
        }
    }

    fun edited(draft: SettingsDraft, change: (FactorySettings) -> FactorySettings): SettingsDraft =
        draft.copy(draft = change(draft.draft))
}

class SettingsDraftTest {

    @Test
    fun `AC-1 an edit stays a draft, counts as a change and leaves the saved values untouched`() {
        val client = SettingsDraftFixtures.FakeClient(SaveResult.Unreachable)
        val untouched = SettingsDraft(SettingsDraftFixtures.saved)
        assertThat(untouched.changeCount).isEqualTo(0)

        val edited = SettingsDraftFixtures.edited(untouched) { it.copy(poolSize = 5) }

        assertThat(edited.saved).isEqualTo(SettingsDraftFixtures.saved)
        assertThat(edited.draft.poolSize).isEqualTo(5)
        assertThat(edited.changeCount).isEqualTo(1)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `AC-2 discard returns to the saved values and nothing is sent`() {
        val client = SettingsDraftFixtures.FakeClient(SaveResult.Unreachable)
        val edited = SettingsDraftFixtures.edited(SettingsDraft(SettingsDraftFixtures.saved)) {
            it.copy(poolSize = 5, cost = CostSetting(null, BigDecimal("1")))
        }
        assertThat(edited.changeCount).isEqualTo(2)

        val discarded = edited.discard()

        assertThat(discarded.draft).isEqualTo(SettingsDraftFixtures.saved)
        assertThat(discarded.saved).isEqualTo(SettingsDraftFixtures.saved)
        assertThat(discarded.changeCount).isEqualTo(0)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `AC-6 an unreachable daemon keeps the draft with a visible error and the draft can be saved later`() {
        val client = SettingsDraftFixtures.FakeClient(SaveResult.Unreachable)
        val edited = SettingsDraftFixtures.edited(SettingsDraft(SettingsDraftFixtures.saved)) { it.copy(poolSize = 5) }

        val failed = edited.save(client)

        assertThat(failed.draft).isEqualTo(edited.draft)
        assertThat(failed.saved).isEqualTo(SettingsDraftFixtures.saved)
        assertThat(failed.error).isNotNull()

        client.result = SaveResult.Saved(edited.draft)
        val retried = failed.save(client)

        assertThat(retried.saved).isEqualTo(edited.draft)
        assertThat(retried.changeCount).isEqualTo(0)
        assertThat(retried.error).isNull()
        assertThat(client.sent).containsExactly(edited.draft, edited.draft)
    }
}
