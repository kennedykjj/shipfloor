package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.ports.CloseResult
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItemState
import shipfloor.ports.UnknownTrackerItemException

object GhTrackerFixtures {
    const val SECRET = "ghp_SUPERSECRETTOKEN secret-issue-text"

    fun issue(
        number: Int = 5,
        title: String = "Titulo",
        body: String = "Corpo",
        labels: List<String> = listOf("bug"),
        state: String = "OPEN",
    ): String {
        val l = labels.joinToString(",") { """{"name":"$it"}""" }
        return """{"number":$number,"title":"$title","body":"$body","labels":[$l],""" +
            """"url":"https://github.com/acme/widgets/issues/$number","state":"$state"}"""
    }
}

class GhTrackerTest {

    @TempDir
    lateinit var tmp: Path

    private fun tracker(h: GhHarness, ghPath: String = h.gh.toString()) =
        GhTracker(GhForgeFixtures.REPO, ghPath, h.workDir, h.bodies, Duration.ofSeconds(30))

    @Test
    fun `AC-1 fetch returns title body labels and the text hash`() {
        val h = GhHarness(tmp)
        h.on("issue-view", GhTrackerFixtures.issue(title = "Meu titulo", body = "Meu corpo", labels = listOf("bug", "p1")))

        val item = requireNotNull(tracker(h).fetch("5"))

        assertThat(item.id).isEqualTo("5")
        assertThat(item.title.value).isEqualTo("Meu titulo")
        assertThat(item.body.value).isEqualTo("Meu corpo")
        assertThat(item.labels.map { it.value }).containsExactly("bug", "p1")
        assertThat(item.state).isEqualTo(TrackerItemState.OPEN)
        assertThat(item.textHash).matches("[0-9a-f]{64}")
        val call = h.callsTo("issue view").single()
        assertThat(call).contains("5", "--repo ${GhForgeFixtures.REPO}")
    }

    @Test
    fun `AC-1 fetch returns null for an issue that does not exist`() {
        val h = GhHarness(tmp)
        h.on("issue-view", err = "GraphQL: Could not resolve to an Issue with the number of 99.", exit = 1)

        assertThat(tracker(h).fetch("99")).isNull()
    }

    @Test
    fun `AC-2 hash is stable without change and moves when title or body change`() {
        val h = GhHarness(tmp)
        val t = tracker(h)
        h.on("issue-view", GhTrackerFixtures.issue())
        val first = requireNotNull(t.fetch("5")).textHash
        val again = requireNotNull(t.fetch("5")).textHash
        h.on("issue-view", GhTrackerFixtures.issue(title = "Outro"))
        val otherTitle = requireNotNull(t.fetch("5")).textHash
        h.on("issue-view", GhTrackerFixtures.issue(body = "Outro corpo"))
        val otherBody = requireNotNull(t.fetch("5")).textHash

        assertThat(again).isEqualTo(first)
        assertThat(otherTitle).isNotEqualTo(first)
        assertThat(otherBody).isNotEqualTo(first).isNotEqualTo(otherTitle)
    }

    @Test
    fun `AC-3 search returns the matching items and an empty list when nothing matches`() {
        val h = GhHarness(tmp)
        h.on("issue-list", "[${GhTrackerFixtures.issue(3, "A")},${GhTrackerFixtures.issue(4, "B", labels = listOf("x"))}]")
        val t = tracker(h)

        val items = t.search("is:open")

        assertThat(items.map { it.id }).containsExactly("3", "4")
        assertThat(items.map { it.title.value }).containsExactly("A", "B")
        assertThat(items[1].labels.map { it.value }).containsExactly("x")
        val call = h.callsTo("issue list").single()
        assertThat(call).contains("--repo ${GhForgeFixtures.REPO}", "--limit 100", "is:open")

        h.on("issue-list", "[]")
        assertThat(t.search("nada")).isEmpty()
    }

    @Test
    fun `AC-3 a blank search lists the open issues without a search term`() {
        val h = GhHarness(tmp)
        h.on("issue-list", "[${GhTrackerFixtures.issue(3, "A")}]")
        val t = tracker(h)

        val items = t.search("")

        assertThat(items.map { it.id }).containsExactly("3")
        val call = h.callsTo("issue list").single()
        assertThat(call).contains("--state open", "--limit 100").doesNotContain("--search", "--state all")
    }

    @Test
    fun `AC-4 imported text never renders as plain text`() {
        val h = GhHarness(tmp)
        h.on("issue-view", GhTrackerFixtures.issue(title = "TituloSecreto", body = "CorpoSecreto", labels = listOf("LabelSecreta")))

        val item = requireNotNull(tracker(h).fetch("5"))

        val rendered = listOf(item.title.toString(), item.body.toString(), item.labels.single().toString(), item.toString())
        rendered.forEach { assertThat(it).doesNotContain("TituloSecreto", "CorpoSecreto", "LabelSecreta") }
    }

    @Test
    fun `AC-5 failures name the operation and id and never the issue text or gh output`() {
        val h = GhHarness(tmp)
        h.on("issue-view", err = "boom ${GhTrackerFixtures.SECRET}", exit = 1)

        val failed = assertThrows<TrackerException> { tracker(h).fetch("5") }
        val missing = assertThrows<TrackerException> { tracker(h, tmp.resolve("no-such-gh").toString()).fetch("6") }
        val badId = assertThrows<TrackerException> { tracker(h).fetch("abc") }

        assertThat(failed.message).contains("fetch", "5").doesNotContain("SUPERSECRET", "secret-issue-text")
        assertThat(missing.message).contains("fetch", "6")
        assertThat(badId.message).contains("abc")
    }

    @Test
    fun `AC-6 writeMilestone comments on the issue through a body file`() {
        val h = GhHarness(tmp)
        h.on("issue-view", GhTrackerFixtures.issue())
        h.on("issue-comment", "https://github.com/acme/widgets/issues/5#issuecomment-1\n")

        tracker(h).writeMilestone("5", "Marco: spec pronta\n\nlinha 2")

        assertThat(h.callsTo("issue comment").single()).contains("5", "--repo ${GhForgeFixtures.REPO}")
        assertThat(h.body()).isEqualTo("Marco: spec pronta\n\nlinha 2")
    }

    @Test
    fun `AC-6 close closes an open issue with the comment and reports AlreadyClosed otherwise`() {
        val h = GhHarness(tmp)
        val t = tracker(h)
        h.on("issue-view", GhTrackerFixtures.issue(state = "OPEN"))
        h.on("issue-close", "")

        assertThat(t.close("5", "Entregue")).isEqualTo(CloseResult.Closed)
        assertThat(h.callsTo("issue close").single()).contains("5", "--comment", "Entregue")

        val h2 = GhHarness(tmp.resolve("two"))
        h2.on("issue-view", GhTrackerFixtures.issue(state = "CLOSED"))
        assertThat(tracker(h2).close("5", "Entregue")).isEqualTo(CloseResult.AlreadyClosed)
        assertThat(h2.callsTo("issue close")).isEmpty()
    }

    @Test
    fun `AC-6 unknown issue id becomes UnknownTrackerItemException on write and close`() {
        val h = GhHarness(tmp)
        val notFound = "GraphQL: Could not resolve to an Issue with the number of 99."
        h.on("issue-view", err = notFound, exit = 1)
        h.on("issue-comment", err = notFound, exit = 1)
        h.on("issue-close", err = notFound, exit = 1)
        val t = tracker(h)

        val w = assertThrows<UnknownTrackerItemException> { t.writeMilestone("99", "m") }
        val c = assertThrows<UnknownTrackerItemException> { t.close("99", "c") }

        assertThat(w.message).contains("99")
        assertThat(c.message).contains("99")
    }

    @Test
    fun `AC-7 search refuses scope qualifiers in any case without calling gh`() {
        val h = GhHarness(tmp)
        h.on("issue-list", "[]")
        val t = tracker(h)

        listOf("repo:other/x bug", "bug ORG:acme", "User:someone", "is:open Repo:a/b").forEach { q ->
            assertThrows<TrackerException> { t.search(q) }
        }

        assertThat(h.calls()).isEmpty()
    }

    @Test
    fun `AC-8 search that returns exactly the 100 item limit fails as truncated`() {
        val h = GhHarness(tmp)
        h.on("issue-list", (1..100).joinToString(",", "[", "]") { GhTrackerFixtures.issue(it) })
        val t = tracker(h)

        val e = assertThrows<TrackerException> { t.search("bug") }
        assertThat(e.message).contains("truncada")

        h.on("issue-list", (1..99).joinToString(",", "[", "]") { GhTrackerFixtures.issue(it) })
        assertThat(t.search("bug")).hasSize(99)
    }

    @Test
    fun `AC-9 gh output above 8 MiB fails naming the operation and id`() {
        val h = GhHarness(tmp)
        val huge = "x".repeat(9 * 1024 * 1024)
        h.on("issue-view", GhTrackerFixtures.issue(body = huge))

        val e = assertThrows<TrackerException> { tracker(h).fetch("5") }

        assertThat(e.message).contains("fetch", "5")
        assertThat(e.message!!.length).isLessThan(1000)
        assertThat(Files.exists(h.rec)).isTrue()
    }
}
