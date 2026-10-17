package shipfloor.app

import java.time.Duration
import java.time.Instant
import shipfloor.core.AssistantState

/** Whether the skin may notify at all. SILENT never emits a popup nor an animation. */
enum class SkinMode { NORMAL, SILENT }

/** The system's motion preference, read by the shell. REDUCED drops the slide and the fade. */
enum class MotionPreference { FULL, REDUCED }

enum class PopupPhase { ENTRY, STAY, EXIT, GONE }

/** What the renderer shows: only the state's phrase, never card text or ids. */
data class PopupCommand(
    val state: AssistantState,
    val phrase: String,
    val emittedAt: Instant,
    val motion: MotionPreference,
)

private val ENTRY_DURATION: Duration = Duration.ofMillis(400)
private val STAY_DURATION: Duration = Duration.ofMillis(4000)
private val EXIT_DURATION: Duration = Duration.ofMillis(260)

/**
 * The animated pop-up skin as a pure, immutable model: a notified state becomes a [PopupCommand], and the phase
 * is a function of the time elapsed since emission. It remembers the last notified state, even after the popup
 * is gone or while silent, so a shell that polls every cycle does not repeat the same notice.
 */
class PopupSkin private constructor(
    private val lastState: AssistantState?,
    val current: PopupCommand?,
) {
    constructor() : this(null, null)

    data class Update(val skin: PopupSkin, val command: PopupCommand?)

    fun onState(
        state: AssistantState,
        phrase: String,
        mode: SkinMode,
        motion: MotionPreference,
        now: Instant,
    ): Update {
        if (mode == SkinMode.SILENT) return Update(PopupSkin(state, null), null)
        if (state == lastState) return Update(this, null)
        val command = PopupCommand(state, phrase, now, motion)
        return Update(PopupSkin(state, command), command)
    }

    fun phase(now: Instant): PopupPhase {
        val command = current ?: return PopupPhase.GONE
        val elapsed = Duration.between(command.emittedAt, now)
        val entry = if (command.motion == MotionPreference.REDUCED) Duration.ZERO else ENTRY_DURATION
        val exit = if (command.motion == MotionPreference.REDUCED) Duration.ZERO else EXIT_DURATION
        return when {
            elapsed < entry -> PopupPhase.ENTRY
            elapsed < entry + STAY_DURATION -> PopupPhase.STAY
            elapsed < entry + STAY_DURATION + exit -> PopupPhase.EXIT
            else -> PopupPhase.GONE
        }
    }
}
