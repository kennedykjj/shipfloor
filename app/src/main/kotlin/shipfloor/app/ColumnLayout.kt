package shipfloor.app

import shipfloor.core.Column

/** How the board draws its cards; each density has its own minimum and default column width, in dp. */
enum class BoardDensity(val minWidth: Int, val defaultWidth: Int) {
    CARDS(200, 220),
    CHIPS(96, 96),
}

/** Screen-only column sizing: the widths the owner set per density, the fit-to-window toggle and the restored empty columns. */
data class ColumnLayoutState(
    val fit: Boolean = true,
    val density: BoardDensity = BoardDensity.CARDS,
    val restored: Set<Column> = emptySet(),
    val widths: Map<BoardDensity, Map<Column, Int>> = emptyMap(),
) {
    fun widthOf(column: Column): Int = widths[density]?.get(column) ?: density.defaultWidth

    fun resize(column: Column, width: Int): ColumnLayoutState =
        withWidths(density, widths[density].orEmpty() + (column to width.coerceIn(density.minWidth, MAX_WIDTH)))

    fun reset(column: Column): ColumnLayoutState = withWidths(density, widths[density].orEmpty() - column)

    fun withFit(fit: Boolean): ColumnLayoutState = copy(fit = fit)

    fun withDensity(density: BoardDensity): ColumnLayoutState = copy(density = density)

    fun restore(column: Column): ColumnLayoutState = copy(restored = restored + column)

    private fun withWidths(density: BoardDensity, perColumn: Map<Column, Int>): ColumnLayoutState =
        copy(widths = widths + (density to perColumn))

    companion object {
        const val MAX_WIDTH = 480
        const val KEY_STEP = 16
        const val COLLAPSED_WIDTH = 46
    }
}

/**
 * Rendered width of every column. Empty columns not restored by the owner are strips; with fit on, the room left in
 * the window is shared among the expanded columns in proportion to their widths, so the row fills the window exactly.
 */
fun columnWidths(state: ColumnLayoutState, counts: Map<Column, Int>, viewportDp: Int, gap: Int): Map<Column, Int> {
    val base = Column.entries.associateWith { column ->
        val collapsed = (counts[column] ?: 0) == 0 && column !in state.restored
        if (collapsed) ColumnLayoutState.COLLAPSED_WIDTH else state.widthOf(column)
    }
    val expanded = Column.entries.filter { base.getValue(it) != ColumnLayoutState.COLLAPSED_WIDTH || (counts[it] ?: 0) > 0 || it in state.restored }
    val spare = viewportDp - gap * (Column.entries.size - 1) - base.values.sum()
    val expandedTotal = expanded.sumOf { base.getValue(it) }
    if (!state.fit || spare <= 0 || expanded.isEmpty() || expandedTotal == 0) return base

    val extra = expanded.associateWith { spare * base.getValue(it) / expandedTotal }
    val leftover = spare - extra.values.sum()
    val bonus = expanded.take(leftover).toSet()
    return base.mapValues { (column, width) ->
        if (column in extra) width + extra.getValue(column) + (if (column in bonus) 1 else 0) else width
    }
}
