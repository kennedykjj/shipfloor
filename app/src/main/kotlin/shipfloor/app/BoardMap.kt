package shipfloor.app

import shipfloor.core.Column

/** One box of the column map: [start] and [share] are fractions (0..1) of the whole board width. */
data class MapBox(val column: Column, val count: Int, val start: Float, val share: Float)

/** The map of the board: a box per column and the outline of the visible window, as fractions of the board width. */
data class BoardMap(val boxes: List<MapBox>, val viewStart: Float, val viewShare: Float)

/** Whether there is more to see past each lateral edge. */
data class EdgeFades(val start: Boolean, val end: Boolean)

private fun totalWidth(widths: Map<Column, Int>, gap: Int): Int =
    Column.entries.sumOf { widths[it] ?: 0 } + gap * (Column.entries.size - 1)

private fun fraction(part: Int, total: Int): Float = if (total <= 0) 0f else part.toFloat() / total

fun boardMap(board: Board, widths: Map<Column, Int>, gap: Int, viewportWidth: Int, scrollOffset: Int): BoardMap {
    val total = totalWidth(widths, gap)
    var position = 0
    val boxes = Column.entries.map { column ->
        val width = widths[column] ?: 0
        val box = MapBox(column, board.cardsIn(column).size, fraction(position, total), fraction(width, total))
        position += width + gap
        box
    }
    if (viewportWidth <= 0) return BoardMap(boxes, viewStart = fraction(scrollOffset.coerceIn(0, total), total), viewShare = 0f)
    val maxScroll = maxOf(0, total - viewportWidth)
    return BoardMap(
        boxes,
        viewStart = fraction(scrollOffset.coerceIn(0, maxScroll), total),
        viewShare = fraction(minOf(viewportWidth, total), total),
    )
}

fun scrollTarget(column: Column, widths: Map<Column, Int>, gap: Int, viewportWidth: Int): Int {
    val columnStart = Column.entries.takeWhile { it != column }.sumOf { (widths[it] ?: 0) + gap }
    val maxScroll = maxOf(0, totalWidth(widths, gap) - viewportWidth)
    return columnStart.coerceIn(0, maxScroll)
}

fun edgeFades(scrollOffset: Int, maxScroll: Int): EdgeFades =
    EdgeFades(start = maxScroll > 0 && scrollOffset > 0, end = maxScroll > 0 && scrollOffset < maxScroll)
