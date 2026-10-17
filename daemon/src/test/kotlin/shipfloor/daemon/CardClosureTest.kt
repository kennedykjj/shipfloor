package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ExternalRef
import shipfloor.core.TextHash
import shipfloor.ports.CloseResult
import shipfloor.ports.ExternalText
import shipfloor.ports.FakeTracker
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class CardClosureTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private lateinit var registry: ProjectRegistry
    private lateinit var proof: OwnerProof
    private lateinit var token: String
    private lateinit var alpha: String
    private val fake = FakeTracker()
    private var confirmedWhenClosing: Boolean? = null
    private var sequence = 0

    /** Delegates to [fake] and notes whether the confirmation was already stored when the write started. */
    private val tracker: Tracker = object : Tracker by fake {
        override fun close(id: String, comment: String): CloseResult {
            confirmedWhenClosing = store.cards().any { c -> store.events(c.id).any { it.type.name == "CLOSURE_CONFIRMED" } }
            return fake.close(id, comment)
        }
    }

    /** Looks a production class up by name so a missing one fails an assertion, not the compilation. */
    private fun production(name: String): Class<*> {
        val found = runCatching { Class.forName(name) }.getOrNull()
        assertThat(found).describedAs("class $name exists").isNotNull()
        return requireNotNull(found)
    }

    /** The forge link the closure is given for a PR number; null stands for a project without a GitHub origin. */
    private var prLink: (String) -> String? = { pr -> "https://github.com/acme/alpha/pull/$pr" }

    private fun closure(): (String) -> Response {
        val constructor = production("shipfloor.daemon.CardClosure").constructors.filter { !it.isSynthetic }.maxBy { it.parameterCount }
        val args = constructor.parameterTypes.map { type ->
            when (type) {
                CardEventStore::class.java -> store
                EventRecorder::class.java -> recorder
                ProjectRegistry::class.java -> registry
                OwnerProof::class.java -> proof
                Clock::class.java -> clock
                DaemonLog::class.java -> DaemonLog { _, _ -> }
                Tracker::class.java -> tracker
                Function1::class.java -> { _: Any? -> tracker }
                Function2::class.java -> { _: Any?, pr: Any? -> prLink(pr as String) }
                else -> error("unexpected constructor parameter $type")
            }
        }
        val instance = constructor.newInstance(*args.toTypedArray())
        val handle = instance.javaClass.methods.single { it.name == "handle" }
        return { line -> handle.invoke(instance, parseRequest(line)) as Response }
    }

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("closure.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        registry = ProjectRegistry(object : ProjectConfigSource {
            override fun read(repo: Path): String =
                "project:\n  name: ${repo.fileName}\nlayers:\n  - name: daemon\n    path: daemon/\n" +
                    if (repo.fileName.toString() == "jira") "tracker: jira\njira_site: acme.atlassian.net\njira_project: PROJ\n" else ""
        })
        val path = Files.createDirectories(dir.resolve("alpha"))
        Files.createDirectories(path.resolve(".git"))
        alpha = registry.register(path).repo
        proof = OwnerProof.create(dir.resolve("run").resolve("owner.token"), SecureRandom())
        token = Files.readString(dir.resolve("run").resolve("owner.token"))
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private fun item(id: String, state: TrackerItemState = TrackerItemState.OPEN): TrackerItem =
        TrackerItem.of(id, ExternalText("Issue title"), ExternalText("body"), emptyList(), "https://github.com/acme/alpha/issues/$id", state)

    private fun record(cardId: String, card: Card, type: EventType, payload: Map<String, String>, actor: Actor = Actor.Factory("deliver")) {
        recorder.record(cardId, card, Event(EventId("e-${sequence++}"), clock.instant(), actor, type, payload))
    }

    /** A card in [column] referencing issue [issue]; DONE cards carry the delivery events [merged] (PR_MERGED) and [movePr]. */
    private fun seed(
        column: Column = Column.DONE,
        title: String = "Local title",
        issue: String? = "7",
        merged: Pair<String, String>? = "42" to "abc1234",
        movePr: String? = null,
    ): String {
        val id = "card-${sequence++}"
        val base = Card.create(title, column)
        val card = issue?.let { i -> base.withReference(ExternalRef("github", i, "https://github.com/acme/alpha/issues/$i", clock.instant(), TextHash("h"))) } ?: base
        record(id, card, EventType.CARD_CREATED, mapOf("title" to title), Actor.Owner)
        store.assignProject(id, alpha)
        merged?.let { record(id, card, EventType.PR_MERGED, mapOf("pr" to it.first, "merge_commit" to it.second, "head" to "h1")) }
        if (column == Column.DONE) {
            val extra = movePr?.let { mapOf("pr" to it) } ?: emptyMap()
            record(id, card, EventType.CARD_MOVED, mapOf("from" to "STAGED", "to" to "DONE") + extra)
        }
        return id
    }

    private fun draft(id: String): Response = closure()("""{"cmd":"card.close.draft","id":"$id"}""")

    private fun confirm(id: String, hash: String, proofText: String = token, actor: String = "owner"): Response =
        closure()("""{"cmd":"card.close.confirm","actor":"$actor","id":"$id","draftHash":"$hash","proof":"$proofText"}""")

    private fun prop(response: Response, name: String): String {
        assertThat(response.javaClass.simpleName).isEqualTo("CloseDraftResult")
        val getter = response.javaClass.methods.single { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }
        return getter.invoke(response) as String
    }

    private fun hashOf(id: String): String = prop(draft(id), "draftHash")

    private fun closureEvents(id: String): List<Event> = store.events(id).filter { it.type.name.startsWith("CLOSURE_") }

    private fun typesOf(id: String): List<String> = closureEvents(id).map { it.type.name }

    private fun nothingWritten(id: String) {
        assertThat(closureEvents(id)).isEmpty()
        assertThat(fake.calls["close"]).isNull()
        assertThat(fake.closures).isEmpty()
    }

    @Test
    fun `AC-1 the draft of a Done card with a github reference has status, summary and the PR link and writes nothing`() {
        fake.seed(item("7"))
        val id = seed()

        val response = draft(id)

        assertThat(prop(response, "status")).containsIgnoringCase("done")
        assertThat(prop(response, "summary")).contains(id).contains("abc1234")
        assertThat(prop(response, "prLink")).isEqualTo("https://github.com/acme/alpha/pull/42")
        assertThat(prop(response, "draftHash")).isNotBlank()
        nothingWritten(id)
    }

    @Test
    fun `AC-2 a card outside Done or without an external reference is refused for draft and confirmation`() {
        fake.seed(item("7"))
        val coding = seed(column = Column.CODING)
        val noRef = seed(issue = null)

        listOf(coding, noRef).forEach { id ->
            val refusedDraft = draft(id)
            assertThat(refusedDraft).describedAs("draft of $id").isInstanceOf(Response.Error::class.java)
            assertThat((refusedDraft as Response.Error).message).isNotBlank()
            assertThat(confirm(id, "anything")).describedAs("confirm of $id").isInstanceOf(Response.Error::class.java)
            nothingWritten(id)
        }
    }

    @Test
    fun `AC-3 the owner confirmation is stored before the tracker write and the write is the tracker close`() {
        fake.seed(item("7"))
        val id = seed()
        val hash = hashOf(id)

        val response = confirm(id, hash)

        assertThat(response).isNotInstanceOf(Response.Error::class.java)
        assertThat(confirmedWhenClosing).isTrue()
        assertThat(typesOf(id)).containsExactly("CLOSURE_CONFIRMED", "CLOSURE_WRITTEN")
        val events = closureEvents(id)
        assertThat(events[0].actor).isEqualTo(Actor.Owner)
        assertThat(events[0].payload["draft_hash"]).isEqualTo(hash)
        assertThat(events[1].actor).isInstanceOf(Actor.Factory::class.java)
        assertThat(events[1].payload["result"]).isEqualTo("closed")
        assertThat(fake.closures).containsOnlyKeys("7")
        assertThat(fake.closures.getValue("7")).contains("https://github.com/acme/alpha/pull/42")
    }

    @Test
    fun `AC-4 a confirmation without the owner proof is refused and nothing is recorded or written`() {
        fake.seed(item("7"))
        val id = seed()
        val hash = hashOf(id)

        assertThat(confirm(id, hash, proofText = "forged")).isInstanceOf(Response.Error::class.java)
        assertThat(confirm(id, hash, actor = "agent:coder")).isInstanceOf(Response.Error::class.java)
        assertThat(closure()("""{"cmd":"card.close.confirm","actor":"owner","id":"$id","draftHash":"$hash"}"""))
            .isInstanceOf(Response.Error::class.java)

        nothingWritten(id)
    }

    @Test
    fun `AC-5 a tracker failure leaves the card in Done with the confirmation and the failure recorded`() {
        fake.seed(item("7"))
        val id = seed(title = "Secret issue title")
        val hash = hashOf(id)
        fake.failNext("close")

        val response = confirm(id, hash)

        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat(typesOf(id)).containsExactly("CLOSURE_CONFIRMED", "CLOSURE_FAILED")
        val failed = closureEvents(id).last()
        assertThat(failed.payload["reason"]).isNotBlank().doesNotContain("Secret issue title")
        assertThat(requireNotNull(store.card(id)).column).isEqualTo(Column.DONE)
        assertThat(fake.closures).isEmpty()
    }

    @Test
    fun `AC-6 a retry repeats the write without a new confirmation and an already closed item counts as written`() {
        fake.seed(item("7"))
        val id = seed()
        val hash = hashOf(id)
        fake.failNext("close")
        confirm(id, hash)

        val retry = confirm(id, hash)

        assertThat(retry).isNotInstanceOf(Response.Error::class.java)
        assertThat(typesOf(id)).containsExactly("CLOSURE_CONFIRMED", "CLOSURE_FAILED", "CLOSURE_WRITTEN")
        assertThat(fake.calls["close"]).isEqualTo(2)
        assertThat(fake.closures).containsOnlyKeys("7")

        assertThat(confirm(id, hash)).isNotInstanceOf(Response.Error::class.java)
        assertThat(fake.calls["close"]).isEqualTo(2)
        assertThat(typesOf(id)).hasSize(3)

        fake.seed(item("8", TrackerItemState.CLOSED))
        val other = seed(issue = "8")
        assertThat(confirm(other, hashOf(other))).isNotInstanceOf(Response.Error::class.java)
        assertThat(closureEvents(other).last().payload["result"]).isEqualTo("already_closed")
        assertThat(fake.closures).doesNotContainKey("8")
    }

    @Test
    fun `AC-7 a PR-mode card takes the link from the move to Done and a card with no PR is refused`() {
        fake.seed(item("7"))
        val delivered = seed(merged = null, movePr = "9")

        val response = draft(delivered)

        assertThat(prop(response, "prLink")).isEqualTo("https://github.com/acme/alpha/pull/9")
        assertThat(prop(response, "summary")).doesNotContain("abc1234")

        val none = seed(merged = null)
        val refused = draft(none)
        assertThat(refused).isInstanceOf(Response.Error::class.java)
        assertThat((refused as Response.Error).message).contains("no_pr_recorded")
        assertThat(confirm(none, "x")).isInstanceOf(Response.Error::class.java)
        nothingWritten(none)
    }

    private fun seedJira(): String {
        val repo = registry.register(Files.createDirectories(dir.resolve("jira")).also { Files.createDirectories(it.resolve(".git")) }).repo
        val url = "https://acme.atlassian.net/browse/PROJ-1"
        val id = "card-${sequence++}"
        val card = Card.create("Local title", Column.DONE).withReference(ExternalRef("jira", "PROJ-1", url, clock.instant(), TextHash("h")))
        record(id, card, EventType.CARD_CREATED, mapOf("title" to "Local title"), Actor.Owner)
        store.assignProject(id, repo)
        record(id, card, EventType.PR_MERGED, mapOf("pr" to "42", "merge_commit" to "abc1234", "head" to "h1"))
        record(id, card, EventType.CARD_MOVED, mapOf("from" to "STAGED", "to" to "DONE"))
        return id
    }

    @Test
    fun `AC-3 of #197 a Jira card takes the PR link from the forge and closes the ticket with it`() {
        fake.seed(item("PROJ-1"))
        val id = seedJira()

        val response = draft(id)

        assertThat(prop(response, "prLink")).isEqualTo("https://github.com/acme/alpha/pull/42")
        assertThat(prop(response, "summary")).contains(id).contains("abc1234")
        assertThat(confirm(id, prop(response, "draftHash"))).isNotInstanceOf(Response.Error::class.java)
        assertThat(typesOf(id)).containsExactly("CLOSURE_CONFIRMED", "CLOSURE_WRITTEN")
        assertThat(fake.closures.getValue("PROJ-1")).contains("https://github.com/acme/alpha/pull/42").doesNotContain("browse")
    }

    @Test
    fun `AC-3 of #197 a card whose project has no PR link is refused naming the card and writes nothing`() {
        fake.seed(item("PROJ-1"))
        fake.seed(item("7"))
        val ids = listOf(seedJira(), seed())
        prLink = { null }

        ids.forEach { id ->
            val refused = draft(id)

            assertThat(refused).describedAs("draft of $id").isInstanceOf(Response.Error::class.java)
            assertThat((refused as Response.Error).message).contains(id)
            assertThat(confirm(id, "x")).isInstanceOf(Response.Error::class.java)
            nothingWritten(id)
        }
    }

    @Test
    fun `AC-3 of #197 the PR link is built from the origin slug and is null without a GitHub origin`() {
        val withOrigin = registry.register(Files.createDirectories(dir.resolve("origin")).also { Files.createDirectories(it.resolve(".git")) })
        val without = registry.register(Files.createDirectories(dir.resolve("plain")).also { Files.createDirectories(it.resolve(".git")) })
        git(Path.of(withOrigin.repo), "init", "-q")
        git(Path.of(withOrigin.repo), "remote", "add", "origin", "git@github.com:acme/alpha.git")
        git(Path.of(without.repo), "init", "-q")
        val method = production("shipfloor.daemon.ProjectDelivery").methods.single { it.name == "pullRequestUrl" }
        val delivery = production("shipfloor.daemon.ProjectDelivery").getField("INSTANCE").get(null)

        assertThat(method.invoke(delivery, withOrigin, "42")).isEqualTo("https://github.com/acme/alpha/pull/42")
        assertThat(method.invoke(delivery, without, "42")).isNull()
    }

    private fun git(repo: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isZero()
    }

    @Test
    fun `AC-8 the comment written to the tracker never contains the card title`() {
        fake.seed(item("7"))
        val title = "@team fix [login](https://evil.example/x) **now**"
        val id = seed(title = title)

        val hash = hashOf(id)
        assertThat(confirm(id, hash)).isNotInstanceOf(Response.Error::class.java)

        val comment = fake.closures.getValue("7")
        assertThat(comment).contains(id).contains("abc1234").contains("https://github.com/acme/alpha/pull/42")
        assertThat(comment).doesNotContain("@team").doesNotContain("evil.example").doesNotContain("login").doesNotContain("**now**")
    }
}
