package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.ports.CloseResult
import shipfloor.ports.ExternalText
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.ports.UnknownTrackerItemException

/**
 * A simulated `acli`: answers from files under [rec] (`<verb>.out/.err/.exit/.sleep`, verb is the third argument:
 * `view`, `search`, `comment`, `transition`), logs every call to `calls` and copies any file argument to `body`.
 */
class AcliHarness(private val root: Path) {
    val rec: Path = Files.createDirectories(root.resolve("rec"))
    val workDir: Path = Files.createDirectories(root.resolve("work"))
    val tmpDir: Path = Files.createDirectories(root.resolve("tmpdir"))
    private val bin: Path = Files.createDirectories(root.resolve("bin"))
    val acli: Path = ClaudeRuntimeFixtures.aScript(bin, "acli", script())

    fun on(verb: String, out: String = "", err: String = "", exit: Int = 0, sleep: Boolean = false) {
        Files.writeString(rec.resolve("$verb.out"), out)
        if (err.isNotEmpty()) Files.writeString(rec.resolve("$verb.err"), err)
        if (exit != 0) Files.writeString(rec.resolve("$verb.exit"), exit.toString())
        if (sleep) Files.writeString(rec.resolve("$verb.sleep"), "1")
    }

    fun calls(): List<String> =
        if (Files.exists(rec.resolve("calls"))) Files.readAllLines(rec.resolve("calls")) else emptyList()

    fun callsTo(verb: String): List<String> = calls().filter { it.startsWith("jira workitem $verb") }

    /** Everything the fake saw of a comment: its arguments and the content of any file argument. */
    fun commentSeen(): String =
        callsTo("comment").joinToString("\n") + "\n" +
            (if (Files.exists(rec.resolve("body"))) Files.readString(rec.resolve("body")) else "")

    private fun script(): String =
        """
        rec="$rec"
        printf '%s\n' "§*" >> "§rec/calls"
        for a in "§@"; do
          if [ -f "§a" ]; then cp "§a" "§rec/body"; fi
        done
        name="§3"
        if [ -f "§rec/§name.sleep" ]; then sleep 30; fi
        if [ -f "§rec/§name.out" ]; then cat "§rec/§name.out"; fi
        if [ -f "§rec/§name.err" ]; then cat "§rec/§name.err" >&2; fi
        code=0
        if [ -f "§rec/§name.exit" ]; then code=§(cat "§rec/§name.exit"); fi
        exit §code
        """.trimIndent().replace('§', '$')
}

object AcliTrackerFixtures {
    const val SITE = "acme.atlassian.net"
    const val SECRET = "ATATT-SUPERSECRETTOKEN secret-ticket-text"
    const val NOT_FOUND = "Issue does not exist or you do not have permission to see it."

    fun issue(
        key: String = "PROJ-1",
        summary: String = "Titulo",
        description: String = "\"Corpo\"",
        labels: List<String> = listOf("bug"),
        category: String = "new",
    ): String {
        val l = labels.joinToString(",") { "\"$it\"" }
        return """{"key":"$key","fields":{"summary":"$summary","description":$description,"labels":[$l],""" +
            """"status":{"name":"X","statusCategory":{"key":"$category"}}}}"""
    }

    fun adf(vararg texts: String): String {
        val paragraphs = texts.joinToString(",") {
            """{"type":"paragraph","content":[{"type":"text","text":"$it"}]}"""
        }
        return """{"type":"doc","version":1,"content":[$paragraphs]}"""
    }
}

class AcliTrackerTest {

    @TempDir
    lateinit var tmp: Path

    private fun tracker(
        h: AcliHarness,
        acli: String = h.acli.toString(),
        site: String = AcliTrackerFixtures.SITE,
        project: String = "PROJ",
        timeout: Duration = Duration.ofSeconds(30),
    ) = AcliTracker(site, project, acli, h.workDir, h.tmpDir, timeout)

    @Test
    fun `AC-1 fetch returns title body labels https url and open state`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(summary = "Meu titulo", description = "\"Meu corpo\"", labels = listOf("bug", "p1")))

        val item = requireNotNull(tracker(h).fetch("PROJ-1"))

        assertThat(item.id).isEqualTo("PROJ-1")
        assertThat(item.title.value).isEqualTo("Meu titulo")
        assertThat(item.body.value).isEqualTo("Meu corpo")
        assertThat(item.labels.map { it.value }).containsExactly("bug", "p1")
        assertThat(item.url).isEqualTo("https://acme.atlassian.net/browse/PROJ-1")
        assertThat(item.state).isEqualTo(TrackerItemState.OPEN)
        assertThat(h.callsTo("view").single()).contains("PROJ-1")
    }

    @Test
    fun `a search, blank or not, is scoped to the project and never lists the whole site`() {
        val h = AcliHarness(tmp)
        h.on("search", "[${AcliTrackerFixtures.issue("PROJ-3", "A")}]")
        val t = tracker(h)

        t.search("")
        t.search("bug")

        val calls = h.callsTo("search")
        assertThat(calls).hasSize(2)
        assertThat(calls[0]).contains("project = \"PROJ\" AND statusCategory != Done")
        assertThat(calls[1]).contains("project = \"PROJ\" AND text ~ \"bug\"")
    }

    @Test
    fun `a key of another project or an invalid project is refused without calling acli`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue("OTHER-1"))
        h.on("search", "[]")

        val t = tracker(h)
        assertThrows<TrackerException> { t.fetch("OTHER-1") }
        assertThrows<TrackerException> { t.writeMilestone("OTHER-1", "m") }
        assertThrows<TrackerException> { t.close("OTHER-1", "c") }
        listOf("proj", "P", "PROJ\" OR project = \"X").forEach { bad ->
            assertThrows<TrackerException> { tracker(h, project = bad).search("") }
        }

        assertThat(h.calls()).isEmpty()
    }

    @Test
    fun `AC-1 a done status category maps to the closed state`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(category = "done"))

        assertThat(requireNotNull(tracker(h).fetch("PROJ-1")).state).isEqualTo(TrackerItemState.CLOSED)
    }

    @Test
    fun `AC-2 unknown id fetches null and fails writeMilestone and close with UnknownTrackerItemException`() {
        val h = AcliHarness(tmp)
        h.on("view", err = AcliTrackerFixtures.NOT_FOUND, exit = 1)
        h.on("comment", err = AcliTrackerFixtures.NOT_FOUND, exit = 1)
        h.on("transition", err = AcliTrackerFixtures.NOT_FOUND, exit = 1)
        val t = tracker(h)

        assertThat(t.fetch("PROJ-99")).isNull()
        val w = assertThrows<UnknownTrackerItemException> { t.writeMilestone("PROJ-99", "m") }
        val c = assertThrows<UnknownTrackerItemException> { t.close("PROJ-99", "c") }

        assertThat(w.message).contains("PROJ-99")
        assertThat(c.message).contains("PROJ-99")
    }

    @Test
    fun `AC-3 search returns the matching items quoting the query and a blank one lists the open tickets`() {
        val h = AcliHarness(tmp)
        h.on("search", "[${AcliTrackerFixtures.issue("PROJ-3", "A")},${AcliTrackerFixtures.issue("PROJ-4", "B", labels = listOf("x"))}]")
        val t = tracker(h)

        val items = t.search("login bug")

        assertThat(items.map { it.id }).containsExactly("PROJ-3", "PROJ-4")
        assertThat(items.map { it.title.value }).containsExactly("A", "B")
        assertThat(items[1].labels.map { it.value }).containsExactly("x")
        assertThat(h.callsTo("search").single()).contains("text ~ \"login bug\"")

        h.on("search", "[${AcliTrackerFixtures.issue("PROJ-3", "A")}]")
        assertThat(t.search("  ").map { it.id }).containsExactly("PROJ-3")
        val blank = h.callsTo("search").last()
        assertThat(blank).contains("statusCategory != Done").doesNotContain("text ~")

        h.on("search", "[]")
        assertThat(t.search("nada")).isEmpty()
    }

    @Test
    fun `AC-4 close comments on an open ticket then transitions it and reports Closed`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(category = "new"))
        h.on("comment", "")
        h.on("transition", """{"key":"PROJ-1","status":"Done"}""")

        val result = tracker(h).close("PROJ-1", "Entregue")

        assertThat(result).isEqualTo(CloseResult.Closed)
        assertThat(h.commentSeen()).contains("PROJ-1", "Entregue")
        assertThat(h.callsTo("transition").single()).contains("PROJ-1")
        val verbs = h.calls().map { it.split(" ")[2] }
        assertThat(verbs.indexOf("comment")).isLessThan(verbs.indexOf("transition"))
    }

    @Test
    fun `AC-5 closing an already closed ticket reports AlreadyClosed without commenting or transitioning`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(category = "done"))

        val result = tracker(h).close("PROJ-1", "Entregue")

        assertThat(result).isEqualTo(CloseResult.AlreadyClosed)
        assertThat(h.callsTo("comment")).isEmpty()
        assertThat(h.callsTo("transition")).isEmpty()
    }

    @Test
    fun `AC-6 writeMilestone comments on the ticket and never transitions it`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue())
        h.on("comment", "")

        tracker(h).writeMilestone("PROJ-1", "Marco: spec pronta\n\nlinha 2")

        assertThat(h.callsTo("comment").single()).contains("PROJ-1")
        assertThat(h.commentSeen()).contains("Marco: spec pronta")
        assertThat(h.callsTo("transition")).isEmpty()
    }

    @Test
    fun `AC-6 comment text with shell syntax and flags reaches acli literally`() {
        val h = AcliHarness(tmp)
        h.on("comment", "")
        val text = "x \$(touch pwned) `id` --flag"

        tracker(h).writeMilestone("PROJ-1", text)

        assertThat(h.commentSeen()).contains(text)
        assertThat(Files.exists(h.workDir.resolve("pwned"))).isFalse()
    }

    @Test
    fun `AC-7 failures name the operation and id and never the ticket text or acli output`() {
        val h = AcliHarness(tmp)
        h.on("view", err = "boom ${AcliTrackerFixtures.SECRET}", exit = 1)

        val failed = assertThrows<TrackerException> { tracker(h).fetch("PROJ-5") }
        val missing = assertThrows<TrackerException> { tracker(h, tmp.resolve("no-such-acli").toString()).fetch("PROJ-6") }

        assertThat(failed.message).contains("fetch", "PROJ-5").doesNotContain("SUPERSECRET", "secret-ticket-text")
        assertThat(missing.message).contains("fetch", "PROJ-6")
    }

    @Test
    fun `AC-7 a timeout fails naming the operation and id`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(), sleep = true)

        val e = assertThrows<TrackerException> { tracker(h, timeout = Duration.ofMillis(500)).fetch("PROJ-5") }

        assertThat(e.message).contains("fetch", "PROJ-5")
    }

    @Test
    fun `AC-7 acli output above 8 MiB fails naming the operation and id`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(description = "\"" + "x".repeat(9 * 1024 * 1024) + "\""))

        val e = assertThrows<TrackerException> { tracker(h).fetch("PROJ-5") }

        assertThat(e.message).contains("fetch", "PROJ-5")
        assertThat(e.message!!.length).isLessThan(1000)
        assertThat(Files.list(h.tmpDir).use { it.count() }).isZero()
    }

    @Test
    fun `AC-7 invalid ids site and queries are refused without running acli`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue())
        h.on("search", "[]")
        val t = tracker(h)

        listOf("abc", "proj-1", "PROJ-1 --x", "-PROJ-1", "PROJ-1\n", "PROJ-0000000000").forEach { id ->
            assertThrows<TrackerException> { t.fetch(id) }
            assertThrows<TrackerException> { t.writeMilestone(id, "m") }
            assertThrows<TrackerException> { t.close(id, "c") }
        }
        listOf("-x", "a\nb", "a\u0000b").forEach { q -> assertThrows<TrackerException> { t.search(q) } }
        listOf("evil.com/x", "UPPER.example.com", "a b", "").forEach { site ->
            assertThrows<TrackerException> { tracker(h, site = site).fetch("PROJ-1") }
        }

        assertThat(h.calls()).isEmpty()
    }

    @Test
    fun `AC-7 search text is escaped inside the quoted literal`() {
        val h = AcliHarness(tmp)
        h.on("search", "[]")
        val t = tracker(h)

        t.search("x\" OR project = OUTRO")
        t.search("\\\"")
        t.search(") ORDER BY")

        val calls = h.callsTo("search")
        assertThat(calls[0]).contains("text ~ \"x\\\" OR project = OUTRO\"")
        assertThat(calls[1]).contains("text ~ \"\\\\\\\"\"")
        assertThat(calls[2]).contains("text ~ \") ORDER BY\"")
    }

    @Test
    fun `AC-8 text only leaves as ExternalText and the hash is the one of title and body`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(summary = "TituloSecreto", description = "\"CorpoSecreto\"", labels = listOf("LabelSecreta")))

        val item = requireNotNull(tracker(h).fetch("PROJ-1"))

        val expected = TrackerItem.of(
            "PROJ-1",
            ExternalText("TituloSecreto"),
            ExternalText("CorpoSecreto"),
            listOf(ExternalText("LabelSecreta")),
            "https://acme.atlassian.net/browse/PROJ-1",
            TrackerItemState.OPEN,
        )
        assertThat(item.textHash).isEqualTo(expected.textHash).matches("[0-9a-f]{64}")
        val rendered = listOf(item.title.toString(), item.body.toString(), item.labels.single().toString(), item.toString())
        rendered.forEach { assertThat(it).doesNotContain("TituloSecreto", "CorpoSecreto", "LabelSecreta") }
    }

    @Test
    fun `AC-9 a structured ADF description becomes plain text and the hash follows it`() {
        val h = AcliHarness(tmp)
        h.on("view", AcliTrackerFixtures.issue(summary = "T", description = AcliTrackerFixtures.adf("Primeira linha", "Segunda linha")))

        val item = requireNotNull(tracker(h).fetch("PROJ-1"))

        assertThat(item.body.value).contains("Primeira linha", "Segunda linha")
        assertThat(item.body.value).doesNotContain("paragraph", "\"type\"", "{", "version")
        val expected = TrackerItem.of(
            "PROJ-1", ExternalText("T"), ExternalText(item.body.value), listOf(ExternalText("bug")),
            "https://acme.atlassian.net/browse/PROJ-1", TrackerItemState.OPEN,
        )
        assertThat(item.textHash).isEqualTo(expected.textHash)
    }
}
