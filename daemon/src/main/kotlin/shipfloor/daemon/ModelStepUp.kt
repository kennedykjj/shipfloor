package shipfloor.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import shipfloor.core.Column
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.StoredEvent

/**
 * Moves a card to the next model of [ladder] when a Coding run fails for capacity reasons, and back to the
 * first model when the card leaves Coding. [onEvent] runs inside [EventStream.publish], so it only enqueues;
 * a dedicated daemon thread resolves the card (a database read) and updates the step.
 */
class ModelStepUp(private val ladder: List<String>, private val store: CardEventStore) : AutoCloseable {
    private val steps = ConcurrentHashMap<String, Int>()
    private val queue = LinkedBlockingQueue<StoredEvent>()
    private val worker: Thread

    init {
        require(ladder.isNotEmpty()) { "model ladder must not be empty" }
        worker = Thread(::run, "model-step-up").apply {
            isDaemon = true
            start()
        }
    }

    /** The model the next run of [cardId] uses. */
    fun modelFor(cardId: String): String = ladder[steps[cardId] ?: 0]

    /** Never blocks and never touches the database: the event is handled on the worker thread. */
    fun onEvent(event: StoredEvent) {
        if (event.event.type == EventType.GATE_FINISHED || event.event.type == EventType.CARD_MOVED) {
            queue.add(event)
        }
    }

    /** Stops the worker thread and waits for it, so nothing touches the store after the caller closes it. Events not yet handled are dropped. */
    override fun close() {
        worker.interrupt()
        worker.join(JOIN_MILLIS)
    }

    private fun run() {
        try {
            while (true) {
                val event = queue.take()
                try {
                    handle(event)
                } catch (e: RuntimeException) {
                    System.err.println("event=model_step_up_failed card=${event.cardId} error=${e.javaClass.simpleName}")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(stored: StoredEvent) {
        val payload = stored.event.payload
        when (stored.event.type) {
            EventType.CARD_MOVED ->
                if (payload["from"] == Column.CODING.name && payload["to"] != Column.CODING.name) {
                    steps.remove(stored.cardId)
                }
            EventType.GATE_FINISHED -> {
                val capacityFailure = payload["verdict"] == "FAIL" && payload["reason"] in STEP_UP_REASONS
                if (capacityFailure && store.card(stored.cardId)?.column == Column.CODING) {
                    steps.merge(stored.cardId, 1) { current, one -> minOf(current + one, ladder.lastIndex) }
                }
            }
            else -> Unit
        }
    }

    companion object {
        private const val JOIN_MILLIS = 2_000L

        /**
         * Closed set of gate failure reasons that step the model up; every other reason is not about model
         * capacity and keeps the model.
         * - exit_code: the agent process exited with a failure.
         * - tests_failed: the agent's code ran and its tests failed.
         */
        val STEP_UP_REASONS: Set<String> = setOf("exit_code", "tests_failed")
    }
}
