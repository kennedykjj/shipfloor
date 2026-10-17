package shipfloor.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val CLOSURE_PREFIX = "CLOSURE_"
private const val CLOSURE_WRITTEN = "CLOSURE_WRITTEN"
private const val CLOSURE_FAILED = "CLOSURE_FAILED"
private const val CLOSURE_CONFIRMED = "CLOSURE_CONFIRMED"

sealed interface ClosureState {
    data object Loading : ClosureState

    data class Draft(
        val status: String,
        val summary: String,
        val prLink: String,
        val draftHash: String,
        val notSent: NotSentCause? = null,
    ) : ClosureState

    data class Refused(val reason: String) : ClosureState

    data class Confirming(val draftHash: String) : ClosureState

    data object Closed : ClosureState

    data class Failed(val reason: String, val draftHash: String, val notSent: NotSentCause? = null) : ClosureState
}

/** The closure screen's logic: asks for the draft, sends the owner's confirmation once and keeps the hash for a retry. */
class ClosureController(private val commands: ClosureCommands, private val io: CoroutineDispatcher) {
    private val mutableState = MutableStateFlow<ClosureState>(ClosureState.Loading)
    val state: StateFlow<ClosureState> = mutableState
    private var cardId: String = ""

    /** Seeds from the card timeline (chronological; the last `CLOSURE_*` event decides) and asks for a draft otherwise. */
    suspend fun open(cardId: String, events: List<TimelineEvent>) {
        this.cardId = cardId
        val last = events.lastOrNull { it.type.startsWith(CLOSURE_PREFIX) }
        val confirmedHash = events.lastOrNull { it.type == CLOSURE_CONFIRMED }?.payload?.get("draft_hash")
        mutableState.value = when {
            last?.type == CLOSURE_WRITTEN -> ClosureState.Closed
            last?.type == CLOSURE_FAILED && confirmedHash != null ->
                ClosureState.Failed(last.payload["reason"].orEmpty(), confirmedHash)
            else -> fetchDraft()
        }
    }

    /** Sends the confirmation from a draft or a failure; in any other state it does nothing. */
    suspend fun confirm() {
        val before = mutableState.value
        val hash = when (before) {
            is ClosureState.Draft -> before.draftHash
            is ClosureState.Failed -> before.draftHash
            else -> return
        }
        if (!mutableState.compareAndSet(before, ClosureState.Confirming(hash))) return
        mutableState.value = when (val outcome = withContext(io) { commands.confirm(cardId, hash) }) {
            ConfirmOutcome.Closed -> ClosureState.Closed
            ConfirmOutcome.DraftChanged -> fetchDraft()
            is ConfirmOutcome.Failed -> ClosureState.Failed(outcome.reason, hash)
            is ConfirmOutcome.NotSent -> when (before) {
                is ClosureState.Draft -> before.copy(notSent = outcome.cause)
                is ClosureState.Failed -> before.copy(notSent = outcome.cause)
                else -> before
            }
        }
    }

    private suspend fun fetchDraft(): ClosureState =
        when (val outcome = withContext(io) { commands.draft(cardId) }) {
            is DraftOutcome.Drafted -> ClosureState.Draft(outcome.status, outcome.summary, outcome.prLink, outcome.draftHash)
            is DraftOutcome.Refused -> ClosureState.Refused(outcome.reason)
            is DraftOutcome.NotSent -> ClosureState.Refused(Texts.closureNotSent(outcome.cause))
        }
}
