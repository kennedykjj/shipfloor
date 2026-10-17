package shipfloor.app

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.freedesktop.dbus.Marshalling
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Column
import shipfloor.core.EventType
import shipfloor.core.OWNER_WAIT_COLUMNS

object OwnerNoticeFixtures {
    const val A = "3f2a9c1e-1111-4222-8333-444455556666"
    const val B = "9b7d0e42-1111-4222-8333-444455556666"
    const val C = "c0ffee00-1111-4222-8333-444455556666"
    const val SECRET_TITLE = "Rotate the production credentials"

    val noon: Instant = Instant.parse("2026-10-16T12:00:00Z")
    val night: Instant = Instant.parse("2026-10-16T23:00:00Z")
    val morning: Instant = Instant.parse("2026-10-17T07:00:00Z")

    fun aCard(id: String = A, column: Column = Column.SPEC_REVIEW, title: String = SECRET_TITLE) = BoardCard(id, title, column)

    fun anEvent(seq: Long, cardId: String, type: EventType, to: Column? = null, externalId: String? = null): BoardEvent {
        val payload = buildMap {
            if (to != null) put("to", to.name)
            if (externalId != null) put("externalId", externalId)
            if (type == EventType.CARD_CREATED) put("title", SECRET_TITLE)
        }
        return BoardEvent(seq, cardId, type, payload)
    }

    fun moved(seq: Long, cardId: String, to: Column) = anEvent(seq, cardId, EventType.CARD_MOVED, to)
}

private class NoticeClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

private class FakeTray(var failure: RuntimeException? = null) : TrayPort {
    val shows = mutableListOf<Int>()
    var clears = 0
    private var listener: () -> Unit = {}

    override fun show(waiting: Int) {
        failure?.let { throw it }
        shows += waiting
    }

    override fun clear() {
        failure?.let { throw it }
        clears++
    }

    override fun onActivated(listener: () -> Unit) {
        this.listener = listener
    }

    fun click() = listener()
}

private class FakeNotifications(var failure: RuntimeException? = null) : NotificationPort {
    data class Sent(val title: String, val body: String, val replacesId: Int)

    val sent = mutableListOf<Sent>()
    private var nextId = 100
    private var listener: (Int) -> Unit = {}

    override fun notify(title: String, body: String, replacesId: Int): Int {
        failure?.let { throw it }
        sent += Sent(title, body, replacesId)
        return if (replacesId != 0) replacesId else nextId++
    }

    override fun onActivated(listener: (notificationId: Int) -> Unit) {
        this.listener = listener
    }

    fun click(id: Int) = listener(id)
}

class OwnerNoticesTest {
    @Test
    fun `the tray menu layout is exported with the dbusmenu signature`() {
        val getLayout = DbusMenu::class.java.getMethod("GetLayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java)

        val signature = Marshalling.getDBusType(getLayout.genericReturnType).joinToString("")

        assertThat(signature).isEqualTo("u(ia{sv}av)")
    }

    private val clock = NoticeClock(OwnerNoticeFixtures.noon)
    private val tray = FakeTray()
    private val notifications = FakeNotifications()
    private val logs = mutableListOf<Pair<String, Map<String, String>>>()
    private val opened = mutableListOf<String?>()

    private fun shell(language: NoticeLanguage = NoticeLanguage.PT_BR) =
        OwnerNoticeShell(
            tray = tray,
            notifications = notifications,
            clock = clock,
            zone = ZoneOffset.UTC,
            language = language,
            log = { message, fields -> logs += message to fields },
            open = { opened += it },
        )

    private fun OwnerNoticeShell.created(seq: Long, id: String, column: Column, externalId: String?) =
        onEvent(
            OwnerNoticeFixtures.anEvent(seq, id, EventType.CARD_CREATED, externalId = externalId),
            OwnerNoticeFixtures.aCard(id, column),
        )

    // ---- AC-1 ----

    @Test
    fun `AC-1 a card that starts waiting shows the tray and sends one notification`() {
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")

        assertThat(tray.shows).containsExactly(1)
        assertThat(notifications.sent).hasSize(1)
        assertThat(notifications.sent.single().title).isEqualTo("Shipfloor")
        assertThat(notifications.sent.single().body).isEqualTo("Card #70 espera sua decisão: Spec Review.")
        assertThat(notifications.sent.single().replacesId).isZero()
    }

    @Test
    fun `AC-1 a card moved to Staged notifies and a move to a non waiting column does not`() {
        val shell = shell()

        shell.onEvent(OwnerNoticeFixtures.moved(1, OwnerNoticeFixtures.A, Column.CODING), null)
        assertThat(tray.shows).isEmpty()
        assertThat(notifications.sent).isEmpty()

        shell.onEvent(OwnerNoticeFixtures.moved(2, OwnerNoticeFixtures.A, Column.STAGED), null)
        assertThat(tray.shows).containsExactly(1)
        assertThat(notifications.sent.single().body).endsWith("Staged.")
    }

    // ---- AC-2 / AC-3 ----

    @Test
    fun `AC-2 nothing is shown or notified during the quiet window`() {
        clock.now = OwnerNoticeFixtures.night
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")
        shell.tick()

        assertThat(tray.shows).isEmpty()
        assertThat(tray.clears).isEqualTo(1)
        assertThat(notifications.sent).isEmpty()
    }

    @Test
    fun `AC-3 when the window ends one notice summarizes what still waits and skips resolved cards`() {
        clock.now = OwnerNoticeFixtures.night
        val shell = shell()
        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")
        shell.created(2, OwnerNoticeFixtures.B, Column.STAGED, "72")
        shell.created(3, OwnerNoticeFixtures.C, Column.SPEC_REVIEW, "75")
        shell.onEvent(OwnerNoticeFixtures.anEvent(4, OwnerNoticeFixtures.B, EventType.OWNER_APPROVED, Column.READY_FOR_DEV), null)

        clock.now = OwnerNoticeFixtures.morning
        shell.tick()
        shell.tick()

        assertThat(notifications.sent).hasSize(1)
        assertThat(notifications.sent.single().body).isEqualTo("2 cards esperam sua decisão: #70, #75.")
        assertThat(tray.shows.last()).isEqualTo(2)
    }

    @Test
    fun `AC-3 the policy emits a single notifying Show on the first call after the quiet window`() {
        val policy = OwnerNoticePolicy()
        val waiting = WaitingCards.EMPTY
            .withSnapshot(listOf(OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A), OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.B, Column.STAGED)), OwnerNoticeFixtures.night)

        assertThat(policy.decide(waiting, quiet = true)).isEqualTo(NoticeDecision.Clear)
        assertThat(policy.decide(waiting, quiet = true)).isEqualTo(NoticeDecision.None)

        val decision = policy.decide(waiting, quiet = false)

        assertThat(decision).isInstanceOf(NoticeDecision.Show::class.java)
        decision as NoticeDecision.Show
        assertThat(decision.notify).isTrue()
        assertThat(decision.cards.map { it.id }).containsExactly(OwnerNoticeFixtures.A, OwnerNoticeFixtures.B)
        assertThat(policy.decide(waiting, quiet = false)).isEqualTo(NoticeDecision.None)
    }

    @Test
    fun `AC-3 a card leaving only updates the icon without a new notification`() {
        val policy = OwnerNoticePolicy()
        val both = WaitingCards.EMPTY.withSnapshot(
            listOf(OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A), OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.B)),
            OwnerNoticeFixtures.noon,
        )
        policy.decide(both, quiet = false)

        val one = both.withEvent(OwnerNoticeFixtures.moved(1, OwnerNoticeFixtures.A, Column.CODING), null, OwnerNoticeFixtures.noon)
        val decision = policy.decide(one, quiet = false)

        assertThat(decision).isInstanceOf(NoticeDecision.Show::class.java)
        assertThat((decision as NoticeDecision.Show).notify).isFalse()
        assertThat(decision.cards.map { it.id }).containsExactly(OwnerNoticeFixtures.B)
        assertThat(policy.decide(one.withEvent(OwnerNoticeFixtures.moved(2, OwnerNoticeFixtures.B, Column.CODING), null, OwnerNoticeFixtures.noon), quiet = false))
            .isEqualTo(NoticeDecision.Clear)
    }

    // ---- AC-5 ----

    @Test
    fun `AC-5 notification text carries only the state phrase and ids, never card text`() {
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")
        shell.created(2, OwnerNoticeFixtures.B, Column.STAGED, "72")

        assertThat(notifications.sent).hasSize(2)
        val all = notifications.sent.joinToString("\n") { it.title + "\n" + it.body }
        assertThat(all).doesNotContain(OwnerNoticeFixtures.SECRET_TITLE)
        assertThat(notifications.sent.last().body).isEqualTo("2 cards esperam sua decisão: #70, #72.")
        assertThat(notifications.sent.last().replacesId).isEqualTo(100)
    }

    @Test
    fun `AC-5 text lists at most five ids then says how many more, in both languages, and the tooltip only counts`() {
        val cards = (1..9).map { WaitingCard("id$it", Column.SPEC_REVIEW, OwnerNoticeFixtures.noon, "${70 + it}") }

        assertThat(NoticeText.body(cards, NoticeLanguage.PT_BR))
            .isEqualTo("9 cards esperam sua decisão: #71, #72, #73, #74, #75 e mais 4.")
        assertThat(NoticeText.body(cards, NoticeLanguage.EN))
            .isEqualTo("9 cards are waiting for your decision: #71, #72, #73, #74, #75 and 4 more.")
        assertThat(NoticeText.body(cards.take(1), NoticeLanguage.EN)).isEqualTo("Card #71 is waiting for your decision: Spec Review.")
        assertThat(NoticeText.tooltip(0, NoticeLanguage.PT_BR)).isEqualTo("Shipfloor")
        assertThat(NoticeText.tooltip(3, NoticeLanguage.PT_BR)).isEqualTo("Shipfloor · 3 esperando você")
        assertThat(NoticeText.tooltip(3, NoticeLanguage.EN)).isEqualTo("Shipfloor · 3 waiting for you")
    }

    // ---- AC-7 ----

    @Test
    fun `AC-7 without a tray the notification still goes out and the reason is logged with the card id`() {
        tray.failure = IllegalStateException("no tray")
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")

        assertThat(notifications.sent).hasSize(1)
        assertThat(logs.any { it.second["card"] == OwnerNoticeFixtures.A }).isTrue()
        assertThat(logs.toString()).doesNotContain(OwnerNoticeFixtures.SECRET_TITLE)
    }

    @Test
    fun `AC-7 without a notification service the tray still updates and the reason is logged with the card id`() {
        notifications.failure = IllegalStateException("no service")
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.STAGED, "70")

        assertThat(tray.shows).containsExactly(1)
        assertThat(logs.any { it.second["card"] == OwnerNoticeFixtures.A }).isTrue()
        assertThat(logs.toString()).doesNotContain(OwnerNoticeFixtures.SECRET_TITLE)
    }

    // ---- AC-8 ----

    @Test
    fun `AC-8 clicking the notification opens the cited card, or the board once it is resolved`() {
        val shell = shell()
        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")
        val notificationId = 100

        notifications.click(notificationId)
        assertThat(opened).containsExactly(OwnerNoticeFixtures.A)

        shell.onEvent(OwnerNoticeFixtures.anEvent(2, OwnerNoticeFixtures.A, EventType.OWNER_APPROVED, Column.READY_FOR_DEV), null)
        notifications.click(notificationId)
        assertThat(opened).containsExactly(OwnerNoticeFixtures.A, null)
    }

    @Test
    fun `AC-8 clicking the tray icon opens the oldest waiting card, or the board when none waits`() {
        val shell = shell()
        shell.created(1, OwnerNoticeFixtures.B, Column.STAGED, "72")
        clock.now = clock.now.plusSeconds(60)
        shell.created(2, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")

        tray.click()
        assertThat(opened).containsExactly(OwnerNoticeFixtures.B)

        shell.onEvent(OwnerNoticeFixtures.moved(3, OwnerNoticeFixtures.B, Column.DONE), null)
        shell.onEvent(OwnerNoticeFixtures.moved(4, OwnerNoticeFixtures.A, Column.DONE), null)
        tray.click()
        assertThat(opened).containsExactly(OwnerNoticeFixtures.B, null)
    }

    @Test
    fun `AC-8 the target is the first cited card that still waits`() {
        val waiting = WaitingCards.EMPTY.withSnapshot(listOf(OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.B)), OwnerNoticeFixtures.noon)

        assertThat(NoticeClick.target(listOf(OwnerNoticeFixtures.A, OwnerNoticeFixtures.B), waiting)).isEqualTo(OwnerNoticeFixtures.B)
        assertThat(NoticeClick.target(listOf(OwnerNoticeFixtures.A), waiting)).isNull()
    }

    // ---- AC-9 ----

    @Test
    fun `AC-9 an OWNER_APPROVED event takes the card out of the waiting set without a new notification`() {
        val shell = shell()
        shell.created(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW, "70")

        shell.onEvent(OwnerNoticeFixtures.anEvent(2, OwnerNoticeFixtures.A, EventType.OWNER_APPROVED, Column.READY_FOR_DEV), null)

        assertThat(tray.shows).containsExactly(1)
        assertThat(tray.clears).isEqualTo(1)
        assertThat(notifications.sent).hasSize(1)
    }

    @Test
    fun `AC-9 the waiting set follows to of CARD_MOVED and OWNER_APPROVED and keeps since while it keeps waiting`() {
        val first = WaitingCards.EMPTY.withEvent(OwnerNoticeFixtures.moved(1, OwnerNoticeFixtures.A, Column.SPEC_REVIEW), null, OwnerNoticeFixtures.noon)
        val later = first.withEvent(
            OwnerNoticeFixtures.moved(2, OwnerNoticeFixtures.A, Column.STAGED), null, OwnerNoticeFixtures.morning,
        )

        assertThat(later.count).isEqualTo(1)
        assertThat(later.ordered.single().since).isEqualTo(OwnerNoticeFixtures.noon)
        assertThat(later.ordered.single().column).isEqualTo(Column.STAGED)

        val approved = later.withEvent(
            OwnerNoticeFixtures.anEvent(3, OwnerNoticeFixtures.A, EventType.OWNER_APPROVED, Column.READY_FOR_DEV), null, OwnerNoticeFixtures.morning,
        )
        assertThat(approved.count).isZero()
        assertThat(approved.contains(OwnerNoticeFixtures.A)).isFalse()
    }

    // ---- AC-10 ----

    @Test
    fun `AC-10 the snapshot counts only cards really in a waiting column`() {
        val waiting = WaitingCards.EMPTY.withSnapshot(
            listOf(
                OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A, Column.SPEC_REVIEW),
                OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.B, Column.STAGED),
                OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.C, Column.CODING),
            ),
            OwnerNoticeFixtures.noon,
        )

        assertThat(waiting.count).isEqualTo(2)
        assertThat(waiting.contains(OwnerNoticeFixtures.C)).isFalse()
    }

    @Test
    fun `AC-10 a card created in a waiting column counts by the fetched column, and one fetched elsewhere does not`() {
        val created = OwnerNoticeFixtures.anEvent(1, OwnerNoticeFixtures.A, EventType.CARD_CREATED, externalId = "70")

        val inReview = WaitingCards.EMPTY.withEvent(created, OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A, Column.SPEC_REVIEW), OwnerNoticeFixtures.noon)
        val inInbox = WaitingCards.EMPTY.withEvent(created, OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A, Column.INBOX), OwnerNoticeFixtures.noon)
        val unknown = WaitingCards.EMPTY.withEvent(created, null, OwnerNoticeFixtures.noon)

        assertThat(inReview.count).isEqualTo(1)
        assertThat(inInbox.count).isZero()
        assertThat(unknown.count).isZero()
    }

    @Test
    fun `AC-10 the app start with cards already waiting notifies them through the snapshot`() {
        val shell = shell()

        shell.onSnapshot(listOf(OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.A, Column.STAGED), OwnerNoticeFixtures.aCard(OwnerNoticeFixtures.B, Column.CODING)))

        assertThat(tray.shows).containsExactly(1)
        assertThat(notifications.sent).hasSize(1)
    }

    // ---- AC-11 ----

    @Test
    fun `AC-11 the label is the issue number with a reference, else the first 8 hex characters of the id`() {
        val withRef = WaitingCard(OwnerNoticeFixtures.A, Column.SPEC_REVIEW, OwnerNoticeFixtures.noon, "70")
        val withoutRef = WaitingCard(OwnerNoticeFixtures.A, Column.SPEC_REVIEW, OwnerNoticeFixtures.noon, null)

        assertThat(NoticeText.label(withRef)).isEqualTo("#70")
        assertThat(NoticeText.label(withoutRef)).isEqualTo("3f2a9c1e")
    }

    @Test
    fun `AC-11 a card without reference is cited by its short id in the notification`() {
        val shell = shell()

        shell.created(1, OwnerNoticeFixtures.A, Column.STAGED, null)

        assertThat(notifications.sent).hasSize(1)
        assertThat(notifications.sent.single().body).isEqualTo("Card 3f2a9c1e espera sua decisão: Staged.")
    }

    // ---- unified owner wait set (card #227) ----

    @Test
    fun `AC-4 the snapshot waits on exactly the core owner wait columns`() {
        val everyColumn = Column.entries.mapIndexed { index, column -> OwnerNoticeFixtures.aCard("card-$index", column) }

        val waiting = WaitingCards.EMPTY.withSnapshot(everyColumn, OwnerNoticeFixtures.noon)

        assertThat(OWNER_WAIT_COLUMNS).isNotEmpty()
        assertThat(waiting.ordered.map { it.column }).containsExactlyInAnyOrderElementsOf(OWNER_WAIT_COLUMNS)
    }

    @Test
    fun `AC-4 a move into each core owner wait column puts the card in the waiting set and a move out takes it off`() {
        assertThat(OWNER_WAIT_COLUMNS).isNotEmpty()
        OWNER_WAIT_COLUMNS.forEach { column ->
            val into = WaitingCards.EMPTY.withEvent(
                OwnerNoticeFixtures.moved(1, OwnerNoticeFixtures.A, column), null, OwnerNoticeFixtures.noon,
            )
            val out = into.withEvent(
                OwnerNoticeFixtures.moved(2, OwnerNoticeFixtures.A, Column.CODING), null, OwnerNoticeFixtures.noon,
            )

            assertThat(into.contains(OwnerNoticeFixtures.A)).describedAs(column.name).isTrue()
            assertThat(out.contains(OwnerNoticeFixtures.A)).describedAs(column.name).isFalse()
        }
    }
}

class SingleInstanceTest {
    @Test
    fun `the first launch holds the lock and a second one learns who is running`(@TempDir dir: Path) {
        val first = SingleInstance.tryAcquire(dir, ownPid = 4242)
        val second = SingleInstance.tryAcquire(dir, ownPid = 9999)

        assertThat(first).isInstanceOf(SingleInstance.Result.First::class.java)
        assertThat(second).isEqualTo(SingleInstance.Result.Running(4242))
        (first as SingleInstance.Result.First).lock?.release()
    }

    @Test
    fun `a lock file that cannot be made does not stop the app from starting`(@TempDir dir: Path) {
        val blocked = dir.resolve("file")
        Files.writeString(blocked, "not a directory")

        val result = SingleInstance.tryAcquire(blocked.resolve("shipfloor"))

        assertThat(result).isEqualTo(SingleInstance.Result.First(null))
    }
}
