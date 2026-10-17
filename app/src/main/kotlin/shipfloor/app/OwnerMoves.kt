package shipfloor.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import shipfloor.core.Column
import shipfloor.core.MoveAuthority
import shipfloor.core.MoveRules

/** Where the owner may take a card from [from]: the owner-only moves of [MoveRules.table]. */
fun ownerTargets(from: Column): List<Column> =
    MoveRules.table.filter { (move, authority) -> move.first == from && authority == MoveAuthority.OWNER_ONLY }.keys.map { it.second }

/** Sending a spec back to refinement asks for a note; the daemon requires it. */
fun needsNote(from: Column, to: Column): Boolean = from == Column.SPEC_REVIEW && to == Column.READY_FOR_REFINEMENT

sealed interface MoveOutcome {
    data object Accepted : MoveOutcome

    data class Refused(val reason: String) : MoveOutcome

    data class NotSent(val cause: NotSentCause) : MoveOutcome
}

enum class NotSentCause { DAEMON_UNAVAILABLE, NO_OWNER_PROOF, NO_ANSWER }

/** The board the controller edits optimistically; the monitor implements it. */
interface BoardEdits {
    fun board(): Board?

    fun edit(transform: (Board) -> Board)
}

enum class NoticeKind { REFUSED, NOT_SENT }

data class MoveNotice(val kind: NoticeKind, val text: String)

data class OwnerMoveState(
    val held: BoardCard? = null,
    val targets: List<Column> = emptyList(),
    val selected: Column? = null,
    val notePending: Boolean = false,
    val status: String? = null,
    val notice: MoveNotice? = null,
)

/** The owner's move gesture (drag or `M`): hold a card, choose a destination, submit. The daemon stays the judge. */
class OwnerMoveController(
    private val commands: OwnerCommands,
    private val edits: BoardEdits,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(OwnerMoveState())
    val state: StateFlow<OwnerMoveState> = mutableState

    fun hold(cardId: String) {
        val card = edits.board()?.cards?.firstOrNull { it.id == cardId }
        val targets = card?.let { ownerTargets(it.column) }.orEmpty()
        mutableState.value = if (card == null || targets.isEmpty()) {
            OwnerMoveState(status = Texts.NO_OWNER_MOVE, notice = mutableState.value.notice)
        } else {
            OwnerMoveState(held = card, targets = targets, status = Texts.holding(card.id), notice = null)
        }
    }

    fun chooseTarget(to: Column) {
        val current = mutableState.value
        if (to in current.targets) mutableState.value = current.copy(selected = to, notePending = false)
    }

    suspend fun submit(note: String? = null) {
        val current = mutableState.value
        val card = current.held ?: return
        val to = current.selected ?: return
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
        if (needsNote(card.column, to) && trimmed == null) {
            mutableState.value = current.copy(notePending = true)
            return
        }
        mutableState.value = OwnerMoveState()
        edits.edit { it.withOptimisticMove(card.id, to) }
        val outcome = withContext(io) { commands.move(card.id, card.column, to, trimmed) }
        if (outcome == MoveOutcome.Accepted) return
        edits.edit { it.revertMove(card.id, card.column, to) }
        mutableState.value = OwnerMoveState(notice = noticeFor(outcome))
    }

    fun cancel() {
        mutableState.value = OwnerMoveState(status = Texts.NOTHING_SENT, notice = mutableState.value.notice)
    }

    fun dismissNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }
}

private fun noticeFor(outcome: MoveOutcome): MoveNotice? =
    when (outcome) {
        MoveOutcome.Accepted -> null
        is MoveOutcome.Refused -> MoveNotice(NoticeKind.REFUSED, Texts.refused(outcome.reason))
        is MoveOutcome.NotSent -> MoveNotice(NoticeKind.NOT_SENT, Texts.notSent(outcome.cause))
    }

/**
 * What the card view shows while the owner decides on a spec in `SPEC_REVIEW`. [sent] stays true until the card
 * leaves that column: nothing is optimistic, the column only changes when the daemon event reaches the detail.
 */
data class SpecReviewState(
    val notePending: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val notice: MoveNotice? = null,
)

/** Approve or ask for a change from the card view. The daemon stays the judge; one command in flight at a time. */
class SpecReviewController(
    private val commands: OwnerCommands,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(SpecReviewState())
    val state: StateFlow<SpecReviewState> = mutableState

    suspend fun approve(cardId: String) {
        send(cardId, Column.READY_FOR_DEV, null)
    }

    /** A blank [note] opens the note field and sends nothing. */
    suspend fun requestChange(cardId: String, note: String?) {
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
        if (mutableState.value.sending) return
        if (trimmed == null) {
            mutableState.value = mutableState.value.copy(notePending = true)
            return
        }
        send(cardId, Column.READY_FOR_REFINEMENT, trimmed)
    }

    fun cancel() {
        mutableState.value = mutableState.value.copy(notePending = false)
    }

    fun dismissNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    /** Called with every refreshed detail: once the card left `SPEC_REVIEW` the "sent" mark is no longer needed. */
    fun onDetail(detail: CardDetail) {
        if (detail.column != Column.SPEC_REVIEW.name && mutableState.value.sent) {
            mutableState.value = mutableState.value.copy(sent = false)
        }
    }

    private suspend fun send(cardId: String, to: Column, note: String?) {
        val current = mutableState.value
        if (current.sending) return
        mutableState.value = current.copy(sending = true, sent = false, notice = null)
        val outcome = withContext(io) { commands.move(cardId, Column.SPEC_REVIEW, to, note) }
        mutableState.value = if (outcome == MoveOutcome.Accepted) {
            mutableState.value.copy(sending = false, sent = true, notePending = false)
        } else {
            mutableState.value.copy(sending = false, notice = noticeFor(outcome))
        }
    }
}

/** The card view offers the owner's actions only for a loaded card waiting in `SPEC_REVIEW`. */
fun specReviewActionsVisible(state: CardDetailState): Boolean =
    state is CardDetailState.Loaded && state.detail.column == Column.SPEC_REVIEW.name
