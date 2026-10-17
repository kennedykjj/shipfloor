package shipfloor.app

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import shipfloor.core.AssistantState

class PopupSkinTest {
    private val t0 = Instant.parse("2026-10-16T10:00:00Z")

    private fun PopupSkin.notify(
        state: AssistantState,
        mode: SkinMode = SkinMode.NORMAL,
        motion: MotionPreference = MotionPreference.FULL,
        now: Instant = t0,
        phrase: String = "phrase for $state",
    ): PopupSkin.Update = onState(state, phrase, mode, motion, now)

    @Test
    fun `AC-1 normal mode emits a popup command with the phrase and starts at entry`() {
        val update = PopupSkin().notify(AssistantState.WORKING, phrase = "Working on it")

        assertThat(update.command)
            .isEqualTo(PopupCommand(AssistantState.WORKING, "Working on it", t0, MotionPreference.FULL))
        assertThat(update.skin.current).isEqualTo(update.command)
        assertThat(update.skin.phase(t0)).isEqualTo(PopupPhase.ENTRY)
    }

    @Test
    fun `AC-2 silent mode emits no popup and no animation`() {
        val update = PopupSkin().notify(AssistantState.WORKING, mode = SkinMode.SILENT)

        assertThat(update.command).isNull()
        assertThat(update.skin.current).isNull()
        assertThat(update.skin.phase(t0)).isEqualTo(PopupPhase.GONE)
    }

    @Test
    fun `AC-2 switching to silent ends a visible popup at once`() {
        val shown = PopupSkin().notify(AssistantState.WORKING).skin
        val update = shown.notify(AssistantState.BLOCKED, mode = SkinMode.SILENT, now = t0.plusMillis(100))

        assertThat(update.command).isNull()
        assertThat(update.skin.phase(t0.plusMillis(100))).isEqualTo(PopupPhase.GONE)
    }

    @Test
    fun `AC-3 same state while popup is shown emits nothing and does not restart the timing`() {
        val shown = PopupSkin().notify(AssistantState.WORKING).skin
        val later = t0.plusMillis(1000)
        val update = shown.notify(AssistantState.WORKING, now = later)

        assertThat(update.command).isNull()
        assertThat(update.skin.current?.emittedAt).isEqualTo(t0)
        assertThat(update.skin.phase(later)).isEqualTo(PopupPhase.STAY)
    }

    @Test
    fun `AC-3 a different state replaces the popup and restarts at entry`() {
        val shown = PopupSkin().notify(AssistantState.WORKING).skin
        val later = t0.plusMillis(1000)
        val update = shown.notify(AssistantState.BLOCKED, now = later)

        assertThat(update.command?.state).isEqualTo(AssistantState.BLOCKED)
        assertThat(update.command?.emittedAt).isEqualTo(later)
        assertThat(update.skin.phase(later)).isEqualTo(PopupPhase.ENTRY)
    }

    @Test
    fun `AC-4 phase follows elapsed time through entry stay exit and gone`() {
        val skin = PopupSkin().notify(AssistantState.WORKING).skin

        assertThat(skin.phase(t0)).isEqualTo(PopupPhase.ENTRY)
        assertThat(skin.phase(t0.plusMillis(399))).isEqualTo(PopupPhase.ENTRY)
        assertThat(skin.phase(t0.plusMillis(400))).isEqualTo(PopupPhase.STAY)
        assertThat(skin.phase(t0.plusMillis(4399))).isEqualTo(PopupPhase.STAY)
        assertThat(skin.phase(t0.plusMillis(4400))).isEqualTo(PopupPhase.EXIT)
        assertThat(skin.phase(t0.plusMillis(4659))).isEqualTo(PopupPhase.EXIT)
        assertThat(skin.phase(t0.plusMillis(4660))).isEqualTo(PopupPhase.GONE)
    }

    @Test
    fun `AC-4 reduced motion has no entry and no exit animation`() {
        val update = PopupSkin().notify(AssistantState.WORKING, motion = MotionPreference.REDUCED)
        val skin = update.skin

        assertThat(update.command?.motion).isEqualTo(MotionPreference.REDUCED)
        assertThat(skin.phase(t0)).isEqualTo(PopupPhase.STAY)
        assertThat(skin.phase(t0.plusMillis(3999))).isEqualTo(PopupPhase.STAY)
        assertThat(skin.phase(t0.plusMillis(4000))).isEqualTo(PopupPhase.GONE)
    }

    @Test
    fun `AC-5 same state notified again after gone emits no new popup`() {
        val skin = PopupSkin().notify(AssistantState.WORKING).skin
        val afterGone = t0.plusMillis(10_000)
        assertThat(skin.phase(afterGone)).isEqualTo(PopupPhase.GONE)

        val update = skin.notify(AssistantState.WORKING, now = afterGone)

        assertThat(update.command).isNull()
        assertThat(update.skin.phase(afterGone)).isEqualTo(PopupPhase.GONE)
    }

    @Test
    fun `AC-5 a different state after gone emits a new popup`() {
        val skin = PopupSkin().notify(AssistantState.WORKING).skin
        val afterGone = t0.plusMillis(10_000)

        val update = skin.notify(AssistantState.IDLE, now = afterGone)

        assertThat(update.command?.state).isEqualTo(AssistantState.IDLE)
        assertThat(update.skin.phase(afterGone)).isEqualTo(PopupPhase.ENTRY)
    }

    @Test
    fun `AC-6 silent then normal with the last notified state emits nothing and a new state emits`() {
        val shown = PopupSkin().notify(AssistantState.WORKING).skin
        val silent = shown.notify(AssistantState.WORKING, mode = SkinMode.SILENT, now = t0.plusMillis(100)).skin
        val back = t0.plusMillis(20_000)

        val same = silent.notify(AssistantState.WORKING, mode = SkinMode.NORMAL, now = back)
        assertThat(same.command).isNull()

        val different = same.skin.notify(AssistantState.BLOCKED, mode = SkinMode.NORMAL, now = back)
        assertThat(different.command?.state).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-6 a state notified during silence counts as the last notified state`() {
        val silent = PopupSkin().notify(AssistantState.BLOCKED, mode = SkinMode.SILENT).skin

        val update = silent.notify(AssistantState.BLOCKED, mode = SkinMode.NORMAL, now = t0.plusMillis(500))

        assertThat(update.command).isNull()
    }
}
