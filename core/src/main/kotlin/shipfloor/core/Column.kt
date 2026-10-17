package shipfloor.core

/** Board columns in board order; [Column.entries] gives the order. */
enum class Column(val displayName: String) {
    INBOX("Inbox"),
    READY_FOR_REFINEMENT("Ready for Refinement"),
    REFINEMENT("Refinement"),
    SPEC_REVIEW("Spec Review"),
    READY_FOR_DEV("Ready for Dev"),
    CODING("Coding"),
    VALIDATION("Validation"),
    STAGED("Staged"),
    READY_FOR_PROD("Ready for Prod"),
    DONE("Done"),
}

/** Columns where a card waits for the owner's decision; the single source for assistant, board and notices. */
val OWNER_WAIT_COLUMNS: Set<Column> = setOf(Column.SPEC_REVIEW, Column.STAGED)
