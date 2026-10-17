package shipfloor.ports

import java.nio.file.Path

/** Boundary that executes one agent session; the model behind it is swappable. */
interface Runtime {
    fun launch(request: SessionRequest): SessionResult
}

data class SessionRequest(
    val stage: String,
    val worktree: Path,
    val model: String,
    val mcpConfig: Path,
    /** The only instruction of the session: the stage skill plus the card and role it runs for. */
    val prompt: String,
)

sealed interface SessionResult {
    val transcriptPath: Path

    data class Exited(val exitCode: Int, override val transcriptPath: Path) : SessionResult

    /** The session did not end on its own; the watchdog decides what to do. */
    data class Hung(override val transcriptPath: Path) : SessionResult
}
