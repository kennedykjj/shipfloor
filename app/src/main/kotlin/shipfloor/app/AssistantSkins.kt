package shipfloor.app

private const val PREFIX = "> "
private const val ELLIPSIS = "…"
private const val EMPTY_TEXT = "aguardando…"
private const val MAX_HISTORY = 100

/** A way of presenting the assistant's phrases. It receives the whole history, oldest first. */
interface AssistantSkin {
    val id: String

    fun show(history: List<String>)
}

class UnknownSkinException(id: String) : RuntimeException("unknown skin $id")

/** What the terminal skin displays: one string per line, oldest first. */
data class TerminalWindow(val lines: List<String>)

/** A small window with the last [maxLines] phrases, each cut to [maxColumns] code points. */
class TerminalSkin(private val maxLines: Int = 5, private val maxColumns: Int = 40) : AssistantSkin {
    init {
        require(maxLines >= 1) { "maxLines must be at least 1, was $maxLines" }
        require(maxColumns >= 4) { "maxColumns must be at least 4, was $maxColumns" }
    }

    override val id: String = "terminal"

    @Volatile
    var current: TerminalWindow = window(emptyList())
        private set

    fun window(history: List<String>): TerminalWindow {
        if (history.isEmpty()) return TerminalWindow(listOf(PREFIX + EMPTY_TEXT))
        return TerminalWindow(history.takeLast(maxLines).map { cut(PREFIX + sanitize(it)) })
    }

    override fun show(history: List<String>) {
        current = window(history)
    }

    private fun cut(line: String): String {
        if (line.codePointCount(0, line.length) <= maxColumns) return line
        val keep = line.offsetByCodePoints(0, maxColumns - 1)
        return line.substring(0, keep) + ELLIPSIS
    }
}

/** Holds the skins, the active one and the phrase history. Safe to call from several threads. */
class SkinRegistry(private val skins: List<AssistantSkin>, initial: String) {
    private val phrases = ArrayDeque<String>()
    private var activeSkin: AssistantSkin = find(initial)

    val active: AssistantSkin
        @Synchronized get() = activeSkin

    val history: List<String>
        @Synchronized get() = phrases.toList()

    @Synchronized
    fun activate(id: String) {
        activeSkin = find(id)
        activeSkin.show(phrases.toList())
    }

    @Synchronized
    fun publish(phrase: String) {
        phrases.addLast(sanitize(phrase))
        while (phrases.size > MAX_HISTORY) phrases.removeFirst()
        activeSkin.show(phrases.toList())
    }

    private fun find(id: String): AssistantSkin = skins.firstOrNull { it.id == id } ?: throw UnknownSkinException(id)
}

private fun sanitize(phrase: String): String =
    phrase.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("")
