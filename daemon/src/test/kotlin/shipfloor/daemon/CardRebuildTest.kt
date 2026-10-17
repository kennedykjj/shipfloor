package shipfloor.daemon

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
import shipfloor.ports.FakeForge
import shipfloor.ports.Forge
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.PullRequestState
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class CardRebuildTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val login = "factory-owner"
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private lateinit var registry: ProjectRegistry
    private lateinit var proof: OwnerProof
    private lateinit var token: String
    private lateinit var repo: String
    private var sequence = 0

    /** Scripted forge: only the listing and the login matter to a rebuild; every other call is a test bug. */
    /** Looks a production class up by name so a missing one fails an assertion, not the compilation. */
    private fun production(name: String): Class<*> {
        val found = runCatching { Class.forName(name) }.getOrNull()
        assertThat(found).describedAs("class $name exists").isNotNull()
        return requireNotNull(found)
    }

    /** A `PullRequestSummary(number, branch, title, state, author, fromFork)`. */
    private fun summary(number: Int, branch: String, title: String, state: PullRequestState, author: String, fromFork: Boolean): Any =
        production("shipfloor.ports.PullRequestSummary").constructors.single().newInstance(number, branch, title, state, author, fromFork)

    private fun rebuilder(forge: Forge): (String) -> Response {
        val constructor = production("shipfloor.daemon.CardRebuild").constructors.single { it.parameterCount == 7 }
        val instance = constructor.newInstance(store, recorder, registry, forge, proof, clock, DaemonLog { _, _ -> })
        val handle = instance.javaClass.methods.single { it.name == "handle" }
        return { line -> handle.invoke(instance, parseRequest(line)) as Response }
    }

    private fun scripted(prs: List<Any>, login: String): Forge =
        Proxy.newProxyInstance(Forge::class.java.classLoader, arrayOf(Forge::class.java)) { _, method, _ ->
            when (method.name) {
                "listPullRequests" -> prs
                "authenticatedLogin" -> login
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Forge

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("rebuild.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        val path = Files.createDirectories(dir.resolve("alpha"))
        Files.createDirectories(path.resolve(".git"))
        val yaml = "project:\n  name: alpha\nlayers:\n  - name: daemon\n    path: daemon/\n"
        registry = ProjectRegistry(object : ProjectConfigSource {
            override fun read(repo: Path): String = yaml
        })
        repo = registry.register(path).repo
        proof = OwnerProof.create(dir.resolve("run").resolve("owner.token"), SecureRandom())
        token = Files.readString(dir.resolve("run").resolve("owner.token"))
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    /** The request goes through the wire parser, as `shipfloor card rebuild` sends it. */
    private fun rebuild(forge: Forge): Response =
        rebuilder(forge)("""{"cmd":"card.rebuild","actor":"owner","proof":"$token"}""")

    private fun branch(id: String, title: String) = WorktreeManager.branchName("feat/{id}-{slug}", id, title)

    private fun pr(
        number: Int,
        id: String,
        title: String,
        state: PullRequestState,
        author: String = login,
        fromFork: Boolean = false,
        branch: String = branch(id, title),
    ) = summary(number, branch, title, state, author, fromFork)

    private fun created(response: Response): List<CardView> = (response as Response.CardsResult).cards

    private fun nothingStored() {
        assertThat(store.cards()).isEmpty()
        assertThat(store.eventsAfter(0)).isEmpty()
    }

    @Test
    fun `AC-1 an open factory PR becomes a card in Validation with the same id and title, never Staged`() {
        val id = UUID.randomUUID().toString()
        val forge = scripted(listOf(pr(7, id, "Ship the thing", PullRequestState.Open("abc"))), login)

        val cards = created(rebuild(forge))

        assertThat(cards).containsExactly(CardView(id, "Ship the thing", Column.VALIDATION))
        assertThat(store.card(id)?.column).isEqualTo(Column.VALIDATION)
        assertThat(store.card(id)?.title).isEqualTo("Ship the thing")
    }

    @Test
    fun `AC-2 a merged factory PR becomes a card in Done`() {
        val id = UUID.randomUUID().toString()
        val forge = scripted(listOf(pr(8, id, "Already shipped", PullRequestState.Merged)), login)

        assertThat(created(rebuild(forge))).containsExactly(CardView(id, "Already shipped", Column.DONE))
        assertThat(store.card(id)?.column).isEqualTo(Column.DONE)
    }

    @Test
    fun `AC-3 a PR closed without merge creates no card`() {
        val forge = scripted(listOf(pr(9, UUID.randomUUID().toString(), "Dropped", PullRequestState.ClosedUnmerged)), login)

        assertThat(created(rebuild(forge))).isEmpty()
        nothingStored()
    }

    @Test
    fun `AC-4 a branch outside the project format or with an invalid id is ignored and nothing is written`() {
        val id = UUID.randomUUID().toString()
        val open = PullRequestState.Open("abc")
        val forge = scripted(
            listOf(
                pr(1, id, "Title", open, branch = "feat/not-a-uuid-title"),
                pr(2, id, "Title", open, branch = "hotfix/$id-title"),
                pr(3, id, "Title", open, branch = "feat/$id-another-slug"),
                pr(4, id, "Title", open, branch = "feat/${id.uppercase()}-title-extra"),
            ),
            login,
        )

        assertThat(created(rebuild(forge))).isEmpty()
        nothingStored()
    }

    @Test
    fun `AC-5 an existing card is neither changed nor duplicated, also across repeated rebuilds`() {
        val existing = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        val seed = Event(EventId("seed-${sequence++}"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Local title"))
        recorder.record(existing, Card.create("Local title", Column.STAGED), seed)
        val forge = scripted(
            listOf(
                pr(1, existing, "Remote title", PullRequestState.Merged),
                pr(2, fresh, "Fresh", PullRequestState.Open("abc")),
            ),
            login,
        )

        assertThat(created(rebuild(forge)).map { it.id }).containsExactly(fresh)
        val afterFirst = store.eventsAfter(0).size
        assertThat(created(rebuild(forge))).isEmpty()

        assertThat(store.eventsAfter(0)).hasSize(afterFirst)
        assertThat(store.cards().map { it.id }).containsExactlyInAnyOrder(existing, fresh)
        assertThat(store.card(existing)?.title).isEqualTo("Local title")
        assertThat(store.card(existing)?.column).isEqualTo(Column.STAGED)
        assertThat(store.events(existing)).hasSize(1)
    }

    @Test
    fun `AC-6 a rebuilt card has one owner creation event marked rebuilt with the PR number and belongs to the project`() {
        val id = UUID.randomUUID().toString()
        val forge = scripted(listOf(pr(42, id, "History", PullRequestState.Open("abc"))), login)

        rebuild(forge)

        val events = store.events(id)
        assertThat(events).hasSize(1)
        assertThat(events.single().type).isEqualTo(EventType.CARD_CREATED)
        assertThat(events.single().actor).isEqualTo(Actor.Owner)
        assertThat(events.single().payload["origin"]).isEqualTo("rebuilt")
        assertThat(events.single().payload["pr"]).isEqualTo("42")
        assertThat(store.projectOf(id)).isEqualTo(repo)
    }

    @Test
    fun `AC-7 a forge failure while listing ends in an error naming the operation and writes nothing`() {
        val forge = FakeForge()
        forge.openPullRequest(PullRequestRequest(branch(UUID.randomUUID().toString(), "Any"), "main", "Any", "body"))
        forge.failNext("listPullRequests")

        val response = rebuild(forge)

        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat((response as Response.Error).message).contains("listPullRequests")
        nothingStored()
    }

    @Test
    fun `AC-9 a fork PR or a PR by another author is ignored and nothing is written`() {
        val open = PullRequestState.Open("abc")
        val forge = scripted(
            listOf(
                pr(1, UUID.randomUUID().toString(), "Forked", open, fromFork = true),
                pr(2, UUID.randomUUID().toString(), "Stranger", open, author = "someone-else"),
                pr(3, UUID.randomUUID().toString(), "Stranger merged", PullRequestState.Merged, author = "someone-else"),
            ),
            login,
        )

        assertThat(created(rebuild(forge))).isEmpty()
        nothingStored()
    }

    @Test
    fun `AC-10 every listed PR is considered and a listing at the limit fails as truncated`() {
        val many = List(40) { pr(it + 1, UUID.randomUUID().toString(), "Card $it", PullRequestState.Merged) }
        assertThat(created(rebuild(scripted(many, login)))).hasSize(40)
        assertThat(store.cards()).hasSize(40)

        val atLimit = List(1000) { pr(it + 1, UUID.randomUUID().toString(), "Limit $it", PullRequestState.Merged) }
        val response = rebuild(scripted(atLimit, login))

        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat((response as Response.Error).message).contains("listagem truncada")
        assertThat(store.cards()).hasSize(40)
    }
}
