package shipfloor.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
import shipfloor.ports.ExternalText
import shipfloor.ports.FakeTracker
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class CardImportTest {
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
    private lateinit var beta: String
    private val tracker = FakeTracker()
    private var sequence = 0
    private val extraYaml = mutableMapOf<String, String>()

    /** Looks a production class up by name so a missing one fails an assertion, not the compilation. */
    private fun production(name: String): Class<*> {
        val found = runCatching { Class.forName(name) }.getOrNull()
        assertThat(found).describedAs("class $name exists").isNotNull()
        return requireNotNull(found)
    }

    private fun importer(): (String) -> Response {
        val constructor = production("shipfloor.daemon.CardImport").constructors.single { it.parameterCount == 7 }
        val trackerArg: Any = if (Tracker::class.java.isAssignableFrom(constructor.parameterTypes[3])) tracker else { _: Project -> tracker as Tracker }
        val instance = constructor.newInstance(store, recorder, registry, trackerArg, proof, clock, DaemonLog { _, _ -> })
        val handle = instance.javaClass.methods.single { it.name == "handle" }
        return { line -> handle.invoke(instance, parseRequest(line)) as Response }
    }

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("import.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        registry = ProjectRegistry(object : ProjectConfigSource {
            override fun read(repo: Path): String {
                val withExtra =
                "project:\n  name: ${repo.fileName}\nlayers:\n  - name: daemon\n    path: daemon/\n" + extraYaml[repo.fileName.toString()].orEmpty()
                val withDor =
                "project:\n  name: ${repo.fileName}\nlayers:\n  - name: daemon\n    path: daemon/\n" +
                    (if (repo.fileName.toString() == "strict") "dor:\n  - title\n  - body\n  - acceptance_criteria\n  - label\n" else "")
                return if (repo.fileName.toString() == "strict") withDor else withExtra
            }
        })
        alpha = register("alpha")
        beta = register("beta")
        proof = OwnerProof.create(dir.resolve("run").resolve("owner.token"), SecureRandom())
        token = Files.readString(dir.resolve("run").resolve("owner.token"))
    }

    private fun register(name: String): String {
        val path = Files.createDirectories(dir.resolve(name))
        Files.createDirectories(path.resolve(".git"))
        return registry.register(path).repo
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private fun item(
        id: String,
        title: String = "Issue title",
        body: String = "body",
        state: TrackerItemState = TrackerItemState.OPEN,
    ): TrackerItem =
        TrackerItem.of(id, ExternalText(title), ExternalText(body), emptyList(), "https://github.com/acme/alpha/issues/$id", state)

    private fun importById(id: String, project: String = "alpha", proofText: String = token, actor: String = "owner"): Response =
        importer()("""{"cmd":"card.import","actor":"$actor","project":"$project","id":"$id","proof":"$proofText"}""")

    private fun importQuery(query: String, project: String = "alpha"): Response =
        importer()("""{"cmd":"card.import","actor":"owner","project":"$project","query":"$query","proof":"$token"}""")

    private fun cardsOf(response: Response): List<CardView> = (response as Response.CardsResult).cards

    private fun withRef(externalId: String): List<String> =
        store.cards().filter { c -> c.card.references.any { it.provider == "github" && it.externalId == externalId } }.map { it.id }

    private fun reimports(cardId: String): List<Event> = store.events(cardId).filter { it.type.name == "CARD_REIMPORTED" }

    private fun seedCard(external: TrackerItem, column: Column, title: String = "Local title"): String {
        val id = "card-${sequence++}"
        val ref = ExternalRef("github", external.id, external.url, clock.instant(), TextHash(external.textHash))
        val seed = Event(EventId("seed-${sequence++}"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to title))
        recorder.record(id, Card.create(title, column).withReference(ref), seed)
        store.assignProject(id, alpha)
        return id
    }

    private fun nothingStored() {
        assertThat(store.cards()).isEmpty()
        assertThat(store.eventsAfter(0)).isEmpty()
    }

    @Test
    fun `AC-1 importing an issue creates an Inbox card with the title and the external reference`() {
        val source = item("7", "Add login")
        tracker.seed(source)

        val cards = cardsOf(importById("7"))

        assertThat(cards).hasSize(1)
        val card = requireNotNull(store.card(cards.single().id))
        assertThat(cards.single().title).isEqualTo("Add login")
        assertThat(card.title).isEqualTo("Add login")
        assertThat(card.column).isEqualTo(Column.INBOX)
        assertThat(card.references).containsExactly(
            ExternalRef("github", "7", source.url, clock.instant(), TextHash(source.textHash)),
        )
        assertThat(store.projectOf(cards.single().id)).isEqualTo(alpha)
    }

    @Test
    fun `AC-2 the creation event is authored by the provider adapter`() {
        tracker.seed(item("7"))

        val id = cardsOf(importById("7")).single().id

        val events = store.events(id)
        assertThat(events).hasSize(1)
        assertThat(events.single().type).isEqualTo(EventType.CARD_CREATED)
        assertThat(events.single().actor).isEqualTo(Actor.Adapter("github"))
        assertThat(events.single().payload["origin"]).isEqualTo("imported")
        assertThat(events.single().payload["provider"]).isEqualTo("github")
        assertThat(events.single().payload["externalId"]).isEqualTo("7")
    }

    @Test
    fun `AC-3 importing the same issue again creates no second card`() {
        tracker.seed(item("7"))

        importById("7")
        importById("7")

        assertThat(store.cards()).hasSize(1)
        assertThat(withRef("7")).hasSize(1)
    }

    @Test
    fun `AC-4 a reimport with changed text updates title and hash and records the event`() {
        tracker.seed(item("7", "Old title", "old body"))
        val id = cardsOf(importById("7")).single().id
        val changed = item("7", "New title", "new body")
        tracker.seed(changed)

        importById("7")

        val card = requireNotNull(store.card(id))
        assertThat(card.title).isEqualTo("New title")
        assertThat(card.references.single().textHash).isEqualTo(TextHash(changed.textHash))
        val event = reimports(id).single()
        assertThat(event.payload["changed"]).isEqualTo("true")
        assertThat(event.payload["title"]).isEqualTo("New title")
        assertThat(event.payload["provider"]).isEqualTo("github")
        assertThat(event.payload["externalId"]).isEqualTo("7")
    }

    @Test
    fun `AC-5 a reimport with unchanged text records the event and leaves the card as it was`() {
        tracker.seed(item("7"))
        val id = cardsOf(importById("7")).single().id
        val before = requireNotNull(store.card(id))

        importById("7")

        assertThat(store.card(id)).isEqualTo(before)
        assertThat(reimports(id).single().payload["changed"]).isEqualTo("false")
        assertThat(store.cards()).hasSize(1)
    }

    @Test
    fun `AC-6 a search imports each result as an Inbox card without duplicating imported ones`() {
        tracker.seed(item("1", "Search topic one"))
        tracker.seed(item("2", "Search topic two"))
        tracker.seed(item("3", "Search topic three"))
        importById("2")

        val created = cardsOf(importQuery("topic"))

        assertThat(created.map { it.title }).containsExactlyInAnyOrder("Search topic one", "Search topic three")
        assertThat(store.cards()).hasSize(3)
        assertThat(store.cards().map { it.card.column }).containsOnly(Column.INBOX)
        assertThat(withRef("2")).hasSize(1)
        assertThat(reimports(withRef("2").single())).isEmpty()
        cardsOf(importQuery("topic"))
        assertThat(store.cards()).hasSize(3)
    }

    @Test
    fun `AC-7 a missing issue or a tracker failure creates nothing and names the external id`() {
        val missing = importById("404")
        assertThat(missing).isInstanceOf(Response.Error::class.java)
        assertThat((missing as Response.Error).message).contains("404")
        nothingStored()

        tracker.seed(item("8"))
        tracker.failNext("fetch")
        val failed = importById("8")
        assertThat(failed).isInstanceOf(Response.Error::class.java)
        assertThat((failed as Response.Error).message).contains("8")
        nothingStored()

        tracker.failNext("search")
        assertThat(importQuery("Issue")).isInstanceOf(Response.Error::class.java)
        nothingStored()
    }

    @Test
    fun `AC-8 a request that is not from the owner is refused and nothing is created`() {
        tracker.seed(item("7"))

        assertThat(importById("7", proofText = "forged")).isInstanceOf(Response.Error::class.java)
        assertThat(importById("7", actor = "agent:coder")).isInstanceOf(Response.Error::class.java)
        assertThat(importer()("""{"cmd":"card.import","actor":"owner","project":"alpha","query":"Issue"}"""))
            .isInstanceOf(Response.Error::class.java)

        nothingStored()
    }

    @Test
    fun `AC-9 the same issue number in two projects makes one card per project`() {
        tracker.seed(item("7"))

        importById("7", project = "alpha")
        importById("7", project = "beta")

        assertThat(withRef("7")).hasSize(2)
        assertThat(withRef("7").map { store.projectOf(it) }).containsExactlyInAnyOrder(alpha, beta)
        importById("7", project = "beta")
        assertThat(withRef("7")).hasSize(2)
    }

    @Test
    fun `AC-10 the card title is normalized and never empty`() {
        val long = "a".repeat(250)
        val titles = mapOf(
            "1" to ("Fix\u0000the\nbug\t\tnow" to "Fix the bug now"),
            "2" to ("too    many   spaces" to "too many spaces"),
            "3" to ("   \n\t  " to "Issue 3"),
            "4" to (long to "a".repeat(200)),
            "5" to ("" to "Issue 5"),
        )
        titles.forEach { (id, pair) -> tracker.seed(item(id, pair.first)) }

        titles.forEach { (id, pair) ->
            val response = importById(id)
            assertThat(response).describedAs("issue $id").isInstanceOf(Response.CardsResult::class.java)
            assertThat(cardsOf(response).single().title).describedAs("issue $id").isEqualTo(pair.second)
            assertThat(store.card(cardsOf(response).single().id)?.title).isEqualTo(pair.second)
        }
    }

    @Test
    fun `AC-11 a reimport after Ready for Dev keeps the title and flags it frozen`() {
        val old = item("7", "Old title", "old")
        val id = seedCard(old, Column.CODING, title = "Old title")
        val changed = item("7", "New title", "new")
        tracker.seed(changed)

        importById("7")

        val card = requireNotNull(store.card(id))
        assertThat(card.title).isEqualTo("Old title")
        assertThat(card.column).isEqualTo(Column.CODING)
        assertThat(card.references.single().textHash).isEqualTo(TextHash(changed.textHash))
        val event = reimports(id).single()
        assertThat(event.payload["changed"]).isEqualTo("true")
        assertThat(event.payload["title_frozen"]).isEqualTo("true")
        assertThat(store.cards()).hasSize(1)
    }

    @Test
    fun `AC-3 of #55 a reimport with a different hash warns the owner with ids and hashes only`() {
        val old = item("7", "Old title", "old secret body")
        val id = seedCard(old, Column.CODING, title = "Old title")
        val changed = item("7", "New title", "new secret body")
        tracker.seed(changed)

        importById("7")

        val warnings = store.events(id).filter { it.type.name == "EXTERNAL_TEXT_CHANGED" }
        assertThat(warnings).hasSize(1)
        val warning = warnings.single()
        assertThat(warning.payload["externalId"]).isEqualTo("7")
        assertThat(warning.payload["previousHash"]).isEqualTo(old.textHash)
        assertThat(warning.payload["newHash"]).isEqualTo(changed.textHash)
        assertThat(warning.payload.values.joinToString(" ")).doesNotContain("title", "secret")

        importById("7")
        assertThat(store.events(id).filter { it.type.name == "EXTERNAL_TEXT_CHANGED" }).hasSize(1)
    }

    @Test
    fun `AC-3 of #55 a reimport with the same hash emits no warning`() {
        tracker.seed(item("7"))
        val id = cardsOf(importById("7")).single().id

        importById("7")

        assertThat(store.events(id).filter { it.type.name == "EXTERNAL_TEXT_CHANGED" }).isEmpty()
    }

    @Test
    fun `AC-12 a search skips closed issues while an import by id accepts one`() {
        tracker.seed(item("1", "Topic open"))
        tracker.seed(item("2", "Topic closed", state = TrackerItemState.CLOSED))

        val created = cardsOf(importQuery("Topic"))

        assertThat(created.map { it.title }).containsExactly("Topic open")
        assertThat(withRef("2")).isEmpty()

        val byId = cardsOf(importById("2"))
        assertThat(byId.map { it.title }).containsExactly("Topic closed")
        assertThat(withRef("2")).hasSize(1)
    }

    @Test
    fun `AC-1 of #197 a project that declares the jira tracker imports a card with the jira reference and adapter`() {
        extraYaml["gamma"] = "tracker: jira\njira_site: acme.atlassian.net\njira_project: PROJ\n"
        val gamma = registry.register(Files.createDirectories(dir.resolve("gamma")).also { Files.createDirectories(it.resolve(".git")) })
        val url = "https://acme.atlassian.net/browse/PROJ-1"
        tracker.seed(TrackerItem.of("PROJ-1", ExternalText("Jira title"), ExternalText("body"), emptyList(), url, TrackerItemState.OPEN))

        val id = cardsOf(importById("PROJ-1", project = "gamma")).single().id
        importById("PROJ-1", project = "gamma")

        val card = requireNotNull(store.card(id))
        assertThat(card.references.single().provider).isEqualTo("jira")
        assertThat(card.references.single().externalId).isEqualTo("PROJ-1")
        assertThat(store.cards()).hasSize(1)
        val created = store.events(id).single { it.type == EventType.CARD_CREATED }
        assertThat(created.actor).isEqualTo(Actor.Adapter("jira"))
        assertThat(created.payload["provider"]).isEqualTo("jira")
        assertThat(reimports(id).single().payload["provider"]).isEqualTo("jira")
        assertThat(ProjectDelivery.trackerOf(gamma, dir.resolve("tracker")).javaClass.simpleName).isEqualTo("AcliTracker")
        val github = registry.list().first { it.name == "alpha" }
        assertThat(ProjectDelivery.trackerOf(github, dir.resolve("tracker")).javaClass.simpleName).isNotEqualTo("AcliTracker")
    }

    private fun boardLine(rows: List<Pair<String, String>>, project: String = "alpha"): String {
        val json = rows.joinToString(",") { """{"id":"${it.first}","column":"${it.second}"}""" }
        return """{"cmd":"card.import_board","actor":"owner","project":"$project","rows":[$json],"proof":"$token"}"""
    }

    private class BoardOutcome(val cards: List<CardView>, val ignored: List<Pair<String, String>>)

    private fun importBoard(vararg rows: Pair<String, String>, project: String = "alpha"): BoardOutcome {
        val response = runCatching { importer()(boardLine(rows.toList(), project)) }.getOrNull()
        assertThat(response?.javaClass?.simpleName).describedAs("board import response").isEqualTo("BoardImportResult")
        val result = requireNotNull(response)
        val cards = result.javaClass.methods.single { it.name == "getCards" }.invoke(result) as List<*>
        val ignored = result.javaClass.methods.single { it.name == "getIgnored" }.invoke(result) as List<*>
        return BoardOutcome(
            cards.map { it as CardView },
            ignored.map { nullable ->
                val row = requireNotNull(nullable)
                val id = row.javaClass.methods.single { it.name == "getId" }.invoke(row) as String
                val reason = row.javaClass.methods.single { it.name == "getReason" }.invoke(row) as String
                id to reason
            },
        )
    }

    private val completeBody = "Do the thing\n\nCritérios de Aceitação\n- it works"

    private fun dorItem(id: String, body: String = completeBody, labels: List<String> = listOf("feature"), title: String = "Ready title $id"): TrackerItem =
        TrackerItem.of(id, ExternalText(title), ExternalText(body), labels.map { ExternalText(it) }, "https://github.com/acme/strict/issues/$id", TrackerItemState.OPEN)

    private fun blocks(externalId: String): List<Event> =
        store.events(withRef(externalId).single()).filter { it.type == EventType.CARD_BLOCKED }

    @Test
    fun `AC-1 of #86 an incomplete ticket enters Inbox blocked by the DoR gate listing only the missing field names`() {
        register("strict")
        tracker.seed(dorItem("1", body = "secret text with no criteria", labels = emptyList()))
        tracker.seed(dorItem("2", body = "secret text with no criteria", labels = emptyList()))
        tracker.seed(dorItem("3", body = "secret text with no criteria", labels = emptyList()))

        importById("1", project = "strict")
        importQuery("Ready title 2", project = "strict")
        importBoard("3" to "Inbox", project = "strict")

        listOf("1", "2", "3").forEach { id ->
            assertThat(columnOf(id)).describedAs(id).isEqualTo(Column.INBOX)
            val block = blocks(id).single()
            assertThat(block.actor).isEqualTo(Actor.Factory("dor-gate"))
            val reason = block.payload["reason"].orEmpty()
            assertThat(reason).startsWith("dor_missing:").contains("acceptance_criteria").contains("label")
            assertThat(reason).doesNotContain("title").doesNotContain("body").doesNotContain("secret")
        }
    }

    @Test
    fun `AC-2 of #86 a ticket with every required field enters Inbox without a block`() {
        register("strict")
        tracker.seed(dorItem("1"))

        importById("1", project = "strict")

        assertThat(columnOf("1")).isEqualTo(Column.INBOX)
        assertThat(blocks("1")).isEmpty()
    }

    @Test
    fun `AC-4 of #86 fixing the ticket and reimporting keeps the card blocked until the owner unblocks`() {
        register("strict")
        tracker.seed(dorItem("1", labels = emptyList()))
        importById("1", project = "strict")
        tracker.seed(dorItem("1"))

        importById("1", project = "strict")

        val events = store.events(withRef("1").single())
        assertThat(events.filter { it.type == EventType.CARD_BLOCKED }).hasSize(1)
        assertThat(events.map { it.type }).doesNotContain(EventType.OWNER_APPROVED, EventType.CARD_MOVED)
        assertThat(events.last { it.type == EventType.CARD_BLOCKED }.payload["reason"]).contains("label")
    }

    @Test
    fun `AC-5 of #86 a V0 board import into a column other than Inbox is not blocked by the DoR`() {
        register("strict")
        tracker.seed(dorItem("1", body = "x", labels = emptyList()))
        tracker.seed(dorItem("2", body = "x", labels = emptyList()))
        tracker.seed(dorItem("3", body = "x", labels = emptyList()).let {
            TrackerItem.of(it.id, it.title, it.body, it.labels, it.url, TrackerItemState.CLOSED)
        })

        importBoard("1" to "Ready for Refinement", "2" to "Spec Review", "3" to "Done", project = "strict")

        assertThat(columnOf("1")).isEqualTo(Column.READY_FOR_REFINEMENT)
        assertThat(columnOf("2")).isEqualTo(Column.SPEC_REVIEW)
        assertThat(columnOf("3")).isEqualTo(Column.DONE)
        listOf("1", "2", "3").forEach { assertThat(blocks(it)).describedAs(it).isEmpty() }
    }

    private fun columnOf(externalId: String): Column? =
        withRef(externalId).singleOrNull()?.let { store.card(it)?.column }

    private fun createdPayload(externalId: String): Map<String, String> =
        store.events(withRef(externalId).single()).single { it.type == EventType.CARD_CREATED }.payload

    @Test
    fun `AC-1 a board import places each card in its mapped column, open issues off the board in Inbox, closed Done issues in Done`() {
        tracker.seed(item("1", "Inbox one"))
        tracker.seed(item("2", "Refine two"))
        tracker.seed(item("3", "Review three"))
        tracker.seed(item("4", "Closed four", state = TrackerItemState.CLOSED))
        tracker.seed(item("5", "Open five off the board"))

        val outcome = importBoard("1" to "Inbox", "2" to "Ready for Refinement", "3" to "Spec Review", "4" to "Done")

        assertThat(outcome.ignored).isEmpty()
        assertThat(outcome.cards).hasSize(5)
        assertThat(columnOf("1")).isEqualTo(Column.INBOX)
        assertThat(columnOf("2")).isEqualTo(Column.READY_FOR_REFINEMENT)
        assertThat(columnOf("3")).isEqualTo(Column.SPEC_REVIEW)
        assertThat(columnOf("4")).isEqualTo(Column.DONE)
        assertThat(columnOf("5")).isEqualTo(Column.INBOX)
        assertThat(createdPayload("2")["origin"]).isEqualTo("v0")
        assertThat(createdPayload("5")["origin"]).isEqualTo("v0")
        assertThat(store.projectOf(withRef("3").single())).isEqualTo(alpha)
    }

    @Test
    fun `AC-2 importing the board again duplicates no card and records no new creation`() {
        tracker.seed(item("1", "One"))
        tracker.seed(item("2", "Two"))
        tracker.seed(item("3", "Open three"))
        importBoard("1" to "Coding", "2" to "Inbox")
        val cardsBefore = store.cards().size
        val eventsBefore = store.eventsAfter(0).size

        val second = importBoard("1" to "Coding", "2" to "Inbox")

        assertThat(second.cards).isEmpty()
        assertThat(store.cards()).hasSize(cardsBefore).hasSize(3)
        assertThat(store.eventsAfter(0)).hasSize(eventsBefore)
        assertThat(withRef("1")).hasSize(1)
    }

    @Test
    fun `AC-3 a row with an unknown column is reported and ignored while the others are imported`() {
        tracker.seed(item("1", "One"))
        tracker.seed(item("2", "Two"))

        val outcome = importBoard("1" to "Backlog", "2" to "Spec Review")

        assertThat(outcome.ignored).containsExactly("1" to "unknown_column")
        assertThat(withRef("1")).isEmpty()
        assertThat(columnOf("2")).isEqualTo(Column.SPEC_REVIEW)
    }

    @Test
    fun `AC-4 a row whose issue does not exist is reported and ignored while the others are imported`() {
        tracker.seed(item("2", "Two"))

        val outcome = importBoard("404" to "Inbox", "2" to "Ready for Refinement")

        assertThat(outcome.ignored).containsExactly("404" to "not_found")
        assertThat(withRef("404")).isEmpty()
        assertThat(columnOf("2")).isEqualTo(Column.READY_FOR_REFINEMENT)
        assertThat(store.cards()).hasSize(1)
    }

    @Test
    fun `AC-5 Technical Refinement is Ready for Refinement and in-flight V0 columns land in Spec Review with v0_column`() {
        val v0 = listOf("Ready for Dev", "Coding", "Validation", "Staged", "Ready for Prod")
        v0.forEachIndexed { index, _ -> tracker.seed(item("${index + 10}", "In flight ${index + 10}")) }
        tracker.seed(item("1", "Tech refinement"))

        val outcome = importBoard(
            *(listOf("1" to "Technical Refinement") + v0.mapIndexed { index, name -> "${index + 10}" to name }).toTypedArray(),
        )

        assertThat(outcome.ignored).isEmpty()
        assertThat(columnOf("1")).isEqualTo(Column.READY_FOR_REFINEMENT)
        v0.forEachIndexed { index, name ->
            val id = "${index + 10}"
            assertThat(columnOf(id)).describedAs(name).isEqualTo(Column.SPEC_REVIEW)
            assertThat(createdPayload(id)["v0_column"]).describedAs(name).isEqualTo(name)
            assertThat(createdPayload(id)["origin"]).isEqualTo("v0")
        }
    }
}
