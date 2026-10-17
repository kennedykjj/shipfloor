package shipfloor.ports

import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

object TrackerFixtures {
    fun anItem(
        id: String = "ISSUE-1",
        title: String = "Fix login",
        body: String = "secret-body-text",
        labels: List<String> = listOf("bug"),
        url: String = "https://tracker.example/ISSUE-1",
        state: TrackerItemState = TrackerItemState.OPEN,
    ): TrackerItem = TrackerItem.of(
        id = id,
        title = ExternalText(title),
        body = ExternalText(body),
        labels = labels.map { ExternalText(it) },
        url = url,
        state = state,
    )

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class TrackerTest {

    @Test
    fun `AC-1 fetch returns the imported item by id and null when it does not exist`() {
        val tracker: Tracker = FakeTracker()
        val item = TrackerFixtures.anItem(id = "ISSUE-1")
        (tracker as FakeTracker).seed(item)

        assertThat(tracker.fetch("ISSUE-1")).isEqualTo(item)
        assertThat(tracker.fetch("ISSUE-404")).isNull()
    }

    @Test
    fun `AC-2 search returns the list of matching items`() {
        val tracker = FakeTracker()
        val login = TrackerFixtures.anItem(id = "ISSUE-1", title = "Fix login")
        val other = TrackerFixtures.anItem(id = "ISSUE-2", title = "Add export")
        val login2 = TrackerFixtures.anItem(id = "ISSUE-3", title = "Login page broken")
        tracker.seed(login)
        tracker.seed(other)
        tracker.seed(login2)

        val found = tracker.search("login")

        assertThat(found.map { it.id }).contains("ISSUE-1").doesNotContain("ISSUE-2")
        assertThat(tracker.search("zzz-no-match")).isEmpty()
    }

    @Test
    fun `AC-3 writeMilestone records the milestone on that item in order`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-1"))
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-2", url = "https://tracker.example/2"))

        tracker.writeMilestone("ISSUE-1", "started")
        tracker.writeMilestone("ISSUE-1", "pr-open")

        assertThat(tracker.milestones["ISSUE-1"]).containsExactly("started", "pr-open")
        assertThat(tracker.milestones["ISSUE-2"].orEmpty()).isEmpty()
        val unknown = assertThrows<UnknownTrackerItemException> { tracker.writeMilestone("ISSUE-99", "x") }
        assertThat(unknown.message).contains("ISSUE-99")
    }

    @Test
    fun `AC-4 close closes the item and records the comment`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-1"))

        val result = tracker.close("ISSUE-1", "done in PR 7")

        assertThat(result).isEqualTo(CloseResult.Closed)
        assertThat(tracker.fetch("ISSUE-1")?.state).isEqualTo(TrackerItemState.CLOSED)
        assertThat(tracker.closures["ISSUE-1"]).isEqualTo("done in PR 7")
        assertThrows<UnknownTrackerItemException> { tracker.close("ISSUE-99", "c") }
    }

    @Test
    fun `AC-5 the fake counts calls per operation including a call that fails`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-1"))

        tracker.fetch("ISSUE-1")
        tracker.fetch("ISSUE-1")
        tracker.search("login")
        tracker.writeMilestone("ISSUE-1", "m")
        tracker.close("ISSUE-1", "c")
        tracker.failNext("close")
        val error = assertThrows<TrackerException> { tracker.close("ISSUE-1", "again") }

        assertThat(tracker.calls["fetch"]).isEqualTo(2)
        assertThat(tracker.calls["search"]).isEqualTo(1)
        assertThat(tracker.calls["writeMilestone"]).isEqualTo(1)
        assertThat(tracker.calls["close"]).isEqualTo(2)
        assertThat(error.message).contains("close").contains("ISSUE-1")
        assertThat(error.message).doesNotContain("secret-body-text")
    }

    @Test
    fun `AC-6 a failure leaves the state untouched, happens once and results are deterministic`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-1"))
        tracker.failNext("writeMilestone")
        tracker.failNext("close")

        assertThrows<TrackerException> { tracker.writeMilestone("ISSUE-1", "m") }
        assertThrows<TrackerException> { tracker.close("ISSUE-1", "c") }

        assertThat(tracker.milestones["ISSUE-1"].orEmpty()).isEmpty()
        assertThat(tracker.closures).isEmpty()
        assertThat(tracker.fetch("ISSUE-1")?.state).isEqualTo(TrackerItemState.OPEN)
        assertThat(tracker.close("ISSUE-1", "c")).isEqualTo(CloseResult.Closed)
        assertThat(tracker.fetch("ISSUE-1")).isEqualTo(tracker.fetch("ISSUE-1"))
    }

    @Test
    fun `AC-7 text from the tracker arrives as ExternalText that does not reveal its value`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(title = "Fix login", body = "secret-body-text", labels = listOf("bug")))

        val item = requireNotNull(tracker.fetch("ISSUE-1"))

        assertThat(item.title).isInstanceOf(ExternalText::class.java)
        assertThat(item.title.value).isEqualTo("Fix login")
        assertThat(item.body.value).isEqualTo("secret-body-text")
        assertThat(item.labels.map { it.value }).containsExactly("bug")
        assertThat(item.body.toString()).doesNotContain("secret-body-text")
        assertThat(item.toString()).doesNotContain("secret-body-text").doesNotContain("Fix login")
    }

    @Test
    fun `AC-8 textHash is the SHA-256 of title NUL body, equal for equal text and different when the body changes`() {
        val a = TrackerFixtures.anItem(id = "A", title = "T", body = "B")
        val b = TrackerFixtures.anItem(id = "B", title = "T", body = "B", url = "https://tracker.example/b")
        val changed = TrackerFixtures.anItem(id = "A", title = "T", body = "B2")

        assertThat(a.textHash).isEqualTo(TrackerFixtures.sha256Hex("T\u0000B"))
        assertThat(b.textHash).isEqualTo(a.textHash)
        assertThat(changed.textHash).isNotEqualTo(a.textHash)
    }

    @Test
    fun `AC-9 closing an already closed item returns AlreadyClosed and records no new comment`() {
        val tracker = FakeTracker()
        tracker.seed(TrackerFixtures.anItem(id = "ISSUE-1"))
        tracker.close("ISSUE-1", "first")

        val result = tracker.close("ISSUE-1", "second")

        assertThat(result).isEqualTo(CloseResult.AlreadyClosed)
        assertThat(tracker.closures["ISSUE-1"]).isEqualTo("first")
    }

    @Test
    fun `AC-10 building an item with a non-https, oversized or control-character url fails with TrackerException`() {
        assertThrows<TrackerException> { TrackerFixtures.anItem(url = "http://tracker.example/1") }
        assertThrows<TrackerException> { TrackerFixtures.anItem(url = "ftp://tracker.example/1") }
        assertThrows<TrackerException> { TrackerFixtures.anItem(url = "https://tracker.example/" + "a".repeat(2048)) }
        assertThrows<TrackerException> { TrackerFixtures.anItem(url = "https://tracker.example/\u0007") }
        assertThrows<TrackerException> { TrackerFixtures.anItem(url = "https://tracker.example/a\nb") }

        val longestValid = "https://tracker.example/".let { it + "a".repeat(2048 - it.length) }
        assertThat(TrackerFixtures.anItem(url = longestValid).url).isEqualTo(longestValid)
    }
}
