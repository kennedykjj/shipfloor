package shipfloor.app

sealed interface ConnectionState {
    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    data class Disconnected(val reason: DisconnectReason) : ConnectionState
}

enum class DisconnectReason { SERVICE_NOT_INSTALLED, START_FAILED, START_TIMED_OUT, NOT_RESPONDING, NO_RUNTIME_DIR }
