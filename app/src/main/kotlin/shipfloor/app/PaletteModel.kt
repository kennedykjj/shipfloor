package shipfloor.app

/** Screens the palette can switch to; [alias] is the name the screen shows elsewhere (its tab), also searchable. */
enum class PaletteView(val label: String, private val alias: String) {
    BOARD("Quadro", Texts.BOARD),
    FEED("Feed", Texts.FEED_TITLE),
    SETTINGS("Configurações", "Configurações"),
    ;

    fun matches(text: String): Boolean = label.contains(text, ignoreCase = true) || alias.contains(text, ignoreCase = true)
}

enum class Direction { UP, DOWN, FIRST, LAST }

sealed interface PaletteEntry {
    data class Card(val card: BoardCard, val waiting: Boolean) : PaletteEntry
    data class View(val view: PaletteView) : PaletteEntry
}

/** The state after choosing a row (always closed) and the row chosen, if any. */
data class PaletteChoice(val state: PaletteState, val entry: PaletteEntry?)

data class PaletteState(val open: Boolean = false, val query: String = "", val active: Int = 0) {
    fun toggle(): PaletteState = if (open) close() else PaletteState(open = true)

    fun close(): PaletteState = PaletteState()

    fun withQuery(query: String): PaletteState = copy(query = query, active = 0)

    /** Moves the active row; the result is always in `0 until size` (0 when [size] is 0). */
    fun move(direction: Direction, size: Int): PaletteState {
        if (size <= 0) return copy(active = 0)
        val current = active.coerceIn(0, size - 1)
        val next = when (direction) {
            Direction.DOWN -> (current + 1) % size
            Direction.UP -> (current - 1 + size) % size
            Direction.FIRST -> 0
            Direction.LAST -> size - 1
        }
        return copy(active = next)
    }

    fun select(entries: List<PaletteEntry>): PaletteChoice =
        PaletteChoice(close(), entries.getOrNull(active))
}

const val PALETTE_ROWS = 9

/** Waiting cards, then the views, then the other cards; filtered by [query], then cut to [PALETTE_ROWS]. */
fun paletteEntries(cards: List<BoardCard>, views: List<PaletteView>, query: String): List<PaletteEntry> {
    val text = query.trim()
    val (waiting, others) = cards.partition { isWaitingForOwner(it.column) }
    val all = waiting.map { PaletteEntry.Card(it, waiting = true) } +
        views.map { PaletteEntry.View(it) } +
        others.map { PaletteEntry.Card(it, waiting = false) }
    return all.filter { matches(it, text) }.take(PALETTE_ROWS)
}

private fun matches(entry: PaletteEntry, text: String): Boolean = when (entry) {
    is PaletteEntry.View -> entry.view.matches(text)
    is PaletteEntry.Card -> entry.card.id.startsWith(text, ignoreCase = true) ||
        entry.card.title.contains(text, ignoreCase = true) ||
        entry.card.column.displayName.contains(text, ignoreCase = true)
}
