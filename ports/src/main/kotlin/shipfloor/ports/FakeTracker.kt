package shipfloor.ports

/** In-memory [Tracker] for tests. Every call is counted in [calls], including one that fails. */
class FakeTracker : Tracker {
    val calls: MutableMap<String, Int> = mutableMapOf()
    val milestones: MutableMap<String, MutableList<String>> = mutableMapOf()
    val closures: MutableMap<String, String> = mutableMapOf()

    private val items: MutableMap<String, TrackerItem> = linkedMapOf()
    private val failures: MutableSet<String> = mutableSetOf()

    fun seed(item: TrackerItem) {
        items[item.id] = item
    }

    /** Makes the next call of [operation] throw [TrackerException] once, leaving the state untouched. */
    fun failNext(operation: String) {
        failures.add(operation)
    }

    override fun fetch(id: String): TrackerItem? {
        begin("fetch", id)
        return items[id]
    }

    override fun search(query: String): List<TrackerItem> {
        begin("search", null)
        return items.values.filter { it.title.value.contains(query, ignoreCase = true) }
    }

    override fun writeMilestone(id: String, milestone: String) {
        begin("writeMilestone", id)
        requireKnown(id)
        milestones.getOrPut(id) { mutableListOf() }.add(milestone)
    }

    override fun close(id: String, comment: String): CloseResult {
        begin("close", id)
        val item = requireKnown(id)
        if (item.state == TrackerItemState.CLOSED) return CloseResult.AlreadyClosed
        items[id] = item.closed()
        closures[id] = comment
        return CloseResult.Closed
    }

    private fun begin(operation: String, id: String?) {
        calls[operation] = (calls[operation] ?: 0) + 1
        if (failures.remove(operation)) {
            val target = id?.let { " for item $it" }.orEmpty()
            throw TrackerException("Tracker $operation failed$target")
        }
    }

    private fun requireKnown(id: String): TrackerItem =
        items[id] ?: throw UnknownTrackerItemException("Unknown tracker item $id")
}
