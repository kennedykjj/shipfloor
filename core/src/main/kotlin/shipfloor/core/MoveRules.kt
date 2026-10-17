package shipfloor.core

import shipfloor.core.Column.CODING
import shipfloor.core.Column.DONE
import shipfloor.core.Column.INBOX
import shipfloor.core.Column.READY_FOR_DEV
import shipfloor.core.Column.READY_FOR_PROD
import shipfloor.core.Column.READY_FOR_REFINEMENT
import shipfloor.core.Column.REFINEMENT
import shipfloor.core.Column.SPEC_REVIEW
import shipfloor.core.Column.STAGED
import shipfloor.core.Column.VALIDATION
import shipfloor.core.MoveAuthority.FACTORY_OR_OWNER
import shipfloor.core.MoveAuthority.OWNER_ONLY

/** Who may perform a move listed in [MoveRules.table]. */
enum class MoveAuthority { OWNER_ONLY, FACTORY_OR_OWNER }

sealed interface MoveDecision {
    data object Allowed : MoveDecision

    data class Refused(val reason: String) : MoveDecision
}

/** The single source of truth for which column moves are allowed and by whom. */
object MoveRules {
    val table: Map<Pair<Column, Column>, MoveAuthority> = mapOf(
        (INBOX to READY_FOR_REFINEMENT) to OWNER_ONLY,
        (READY_FOR_REFINEMENT to REFINEMENT) to FACTORY_OR_OWNER,
        (REFINEMENT to SPEC_REVIEW) to FACTORY_OR_OWNER,
        (REFINEMENT to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
        (SPEC_REVIEW to READY_FOR_DEV) to OWNER_ONLY,
        (SPEC_REVIEW to READY_FOR_REFINEMENT) to OWNER_ONLY,
        (READY_FOR_DEV to CODING) to FACTORY_OR_OWNER,
        (CODING to READY_FOR_DEV) to FACTORY_OR_OWNER,
        (READY_FOR_DEV to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
        (CODING to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
        (CODING to VALIDATION) to FACTORY_OR_OWNER,
        (VALIDATION to CODING) to FACTORY_OR_OWNER,
        (VALIDATION to STAGED) to FACTORY_OR_OWNER,
        (STAGED to READY_FOR_PROD) to OWNER_ONLY,
        (STAGED to CODING) to FACTORY_OR_OWNER,
        (READY_FOR_PROD to STAGED) to FACTORY_OR_OWNER,
        (READY_FOR_PROD to DONE) to FACTORY_OR_OWNER,
        (READY_FOR_PROD to VALIDATION) to FACTORY_OR_OWNER,
    )

    fun check(from: Column, to: Column, actor: Actor): MoveDecision {
        val authority = table[from to to]
            ?: return MoveDecision.Refused("move from ${from.displayName} to ${to.displayName} is not allowed")
        return when {
            authority == FACTORY_OR_OWNER || actor == Actor.Owner -> MoveDecision.Allowed
            else -> MoveDecision.Refused(
                "only the owner approves the move from ${from.displayName} to ${to.displayName}",
            )
        }
    }
}
