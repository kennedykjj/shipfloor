package shipfloor.app

import java.time.Clock
import java.time.ZoneId
import shipfloor.core.AssistantState
import shipfloor.core.AssistantStateRules
import shipfloor.core.AssistantStates

interface TrayPort {
    fun show(waiting: Int)

    fun clear()

    fun onActivated(listener: () -> Unit)
}

interface NotificationPort {
    /** [replacesId] 0 sends a new notification; the result is the id to reuse as [replacesId]. */
    fun notify(title: String, body: String, replacesId: Int): Int

    fun onActivated(listener: (notificationId: Int) -> Unit)
}

/** Connects the waiting-card logic to the tray and the notification service; a failing port never blocks the other. */
class OwnerNoticeShell(
    private val tray: TrayPort,
    private val notifications: NotificationPort,
    private val clock: Clock,
    private val zone: ZoneId,
    private val language: NoticeLanguage,
    private val log: (String, Map<String, String>) -> Unit,
    private val open: (String?) -> Unit,
) {
    private val lock = Any()
    private val policy = OwnerNoticePolicy()
    private var waiting = WaitingCards.EMPTY
    private var lastNotificationId = 0
    private val cited = mutableMapOf<Int, List<String>>()

    init {
        tray.onActivated { open(synchronized(lock) { waiting.ordered.firstOrNull()?.id }) }
        notifications.onActivated { id ->
            open(synchronized(lock) { NoticeClick.target(cited[id].orEmpty(), waiting) })
        }
    }

    fun onSnapshot(cards: List<BoardCard>) =
        synchronized(lock) {
            waiting = waiting.withSnapshot(cards, clock.instant())
            evaluate()
        }

    fun onEvent(event: BoardEvent, fetched: BoardCard?) =
        synchronized(lock) {
            waiting = waiting.withEvent(event, fetched, clock.instant())
            evaluate()
        }

    /** Re-evaluates the quiet window without a new event. */
    fun tick() = synchronized(lock) { evaluate() }

    private fun evaluate() {
        val quiet = AssistantStates.derive(emptyList(), clock.instant(), AssistantStateRules(zone = zone)) == AssistantState.NIGHT
        when (val decision = policy.decide(waiting, quiet)) {
            NoticeDecision.None -> Unit
            NoticeDecision.Clear -> guarded("tray", emptyList()) { tray.clear() }
            is NoticeDecision.Show -> show(decision)
        }
    }

    private fun show(decision: NoticeDecision.Show) {
        guarded("tray", decision.cards) { tray.show(decision.cards.size) }
        if (!decision.notify) return
        guarded("notification", decision.cards) {
            val body = NoticeText.body(decision.cards, language)
            val id = notifications.notify(NoticeText.TITLE, body, lastNotificationId)
            lastNotificationId = id
            cited[id] = decision.cards.map { it.id }
        }
    }

    private fun guarded(port: String, cards: List<WaitingCard>, action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            val reason = e.javaClass.simpleName
            if (cards.isEmpty()) log("owner_notice_failed", mapOf("port" to port, "reason" to reason))
            cards.forEach { log("owner_notice_failed", mapOf("port" to port, "card" to it.id, "reason" to reason)) }
        }
    }
}
