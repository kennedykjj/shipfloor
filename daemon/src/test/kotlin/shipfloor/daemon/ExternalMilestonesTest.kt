package shipfloor.daemon

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import shipfloor.core.ProjectPolicies
import shipfloor.core.TextHash
import shipfloor.ports.ExternalText
import shipfloor.ports.FakeTracker
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class ExternalMilestonesTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private val tracker = FakeTracker()
    private val logged = mutableListOf<Pair<String, Map<String, String>>>()
    private val imported = item("Title", "imported body")
    private val cardId = "card-1"
    private lateinit var proof: OwnerProof
    private lateinit var token: String

    private fun item(title: String, body: String): TrackerItem =
        TrackerItem.of("7", ExternalText(title), ExternalText(body), emptyList(), "https://github.com/acme/alpha/issues/7", TrackerItemState.OPEN)

    private fun policies(vararg keys: String): ProjectPolicies =
        ProjectPolicies.from(mapOf("milestones" to keys.toList(), "signers" to mapOf("milestone" to "policy")))

    private fun ownerPolicies(vararg keys: String): ProjectPolicies =
        ProjectPolicies.from(mapOf("milestones" to keys.toList()))

    /** Looks production types up by name so a missing one fails an assertion, not the compilation. */
    private fun production(name: String): Class<*> {
        val found = runCatching { Class.forName(name) }.getOrNull()
        assertThat(found).describedAs("class $name exists").isNotNull()
        return requireNotNull(found)
    }

    private class Subject(private val instance: Any, private val milestones: Class<*>) {
        fun reached(cardId: String, milestone: String, detail: String? = null) {
            val constant = milestones.enumConstants.single { (it as Enum<*>).name == milestone }
            val method = instance.javaClass.methods.single { it.name == "reached" && it.parameterCount == 3 }
            try {
                method.invoke(instance, cardId, constant, detail)
            } catch (e: InvocationTargetException) {
                throw requireNotNull(e.cause)
            }
        }

        /** Sends a socket line through the same parser the daemon uses. */
        fun send(line: String): Response {
            val method = instance.javaClass.methods.single { it.name == "handle" && it.parameterCount == 1 }
            return try {
                method.invoke(instance, parseRequest(line)) as Response
            } catch (e: InvocationTargetException) {
                throw requireNotNull(e.cause)
            }
        }
    }

    private fun milestones(policies: ProjectPolicies, with: Tracker = tracker): Subject {
        val type = production("shipfloor.daemon.ExternalMilestones")
        val constructor = type.constructors.filter { !it.isSynthetic }.maxBy { it.parameterCount }
        val project = Project("alpha", dir.toString(), listOf(ProjectLayer("daemon", "daemon/")), policies)
        val log = DaemonLog { event, fields -> synchronized(logged) { logged.add(event to fields) } }
        val args = constructor.parameterTypes.indices.map { index ->
            when (val parameter = constructor.parameterTypes[index]) {
                CardEventStore::class.java -> store
                EventRecorder::class.java -> recorder
                Clock::class.java -> clock
                DaemonLog::class.java -> log
                OwnerProof::class.java -> proof
                ProjectRegistry::class.java -> ProjectRegistry(object : ProjectConfigSource {
                    override fun read(repo: Path): String? = null
                })
                Function2::class.java -> { _: Any?, pr: Any? -> prLink(pr as String) }
                Function1::class.java -> {
                    val result = rawOf((constructor.genericParameterTypes[index] as ParameterizedType).actualTypeArguments[1])
                    val value: Any = when (result) {
                        Tracker::class.java -> with
                        ProjectPolicies::class.java -> policies
                        Project::class.java -> project
                        else -> error("unexpected lookup returning $result")
                    }
                    val lookup: (Any?) -> Any = { value }
                    lookup
                }
                else -> error("unexpected constructor parameter $parameter")
            }
        }
        return Subject(constructor.newInstance(*args.toTypedArray()), production("shipfloor.core.Milestone"))
    }

    private fun rawOf(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is WildcardType -> rawOf(type.upperBounds[0])
        is ParameterizedType -> rawOf(type.rawType)
        else -> error("unexpected type $type")
    }

    /** The forge link the milestones are given for a PR number; null stands for a project without a GitHub origin. */
    private var prLink: (String) -> String? = { pr -> "https://github.com/acme/alpha/pull/$pr" }

    @Test
    fun `AC-2 of #197 a Jira card gets its milestones through the project tracker with the forge link and the jira actor`() {
        val jira = ProjectPolicies.from(
            mapOf(
                "milestones" to listOf("started", "pr_opened"),
                "tracker" to "jira",
                "jira_site" to "acme.atlassian.net",
                "jira_project" to "PROJ",
                "signers" to mapOf("milestone" to "policy"),
            ),
        )
        val source = TrackerItem.of("PROJ-1", ExternalText("Jira title"), ExternalText("body"), emptyList(), "https://acme.atlassian.net/browse/PROJ-1", TrackerItemState.OPEN)
        tracker.seed(source)
        val ref = ExternalRef("jira", "PROJ-1", source.url, clock.instant(), TextHash(source.textHash))
        val jiraCard = "card-jira"
        recorder.record(jiraCard, Card.create("Title", Column.CODING).withReference(ref), Event(EventId("seed-jira"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Title")))
        val subject = milestones(jira)
        val written = { store.events(jiraCard).filter { it.type == EventType.MILESTONE_WRITTEN } }

        subject.reached(jiraCard, "STARTED")
        prLink = { null }
        subject.reached(jiraCard, "PR_OPENED", "12")

        assertThat(tracker.milestones["PROJ-1"]).containsExactly("started")
        assertThat(written().map { it.payload["milestone"] }).containsExactly("started")
        assertThat(written().single().actor).isEqualTo(Actor.Adapter("jira"))

        prLink = { pr -> "https://github.com/acme/alpha/pull/$pr" }
        tracker.seed(TrackerItem.of("PROJ-1", ExternalText("Changed"), ExternalText("changed"), emptyList(), source.url, TrackerItemState.OPEN))
        subject.reached(jiraCard, "PR_OPENED", "12")

        assertThat(tracker.milestones["PROJ-1"]).containsExactly("started", "https://github.com/acme/alpha/pull/12")
        assertThat(store.events(jiraCard).single { it.type == EventType.EXTERNAL_TEXT_CHANGED }.actor).isEqualTo(Actor.Adapter("jira"))
    }

    private fun pending(subject: Subject): List<Map<String, String>> {
        val response = subject.send("""{"cmd":"milestone.list"}""")
        assertThat(response.javaClass.simpleName).isEqualTo("MilestonesResult")
        val items = response.javaClass.methods.single { it.name == "getItems" }.invoke(response) as List<*>
        return items.map { item ->
            requireNotNull(item).let { i ->
                listOf("card", "milestone", "text", "textHash").associateWith { name ->
                    i.javaClass.methods.single { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }.invoke(i) as String
                }
            }
        }
    }

    private fun confirm(
        subject: Subject,
        hash: String,
        proofText: String? = token,
        actor: String = "owner",
        milestone: String = "started",
    ): Response {
        val proofField = proofText?.let { ""","proof":"$it"""" } ?: ""
        return subject.send(
            """{"cmd":"milestone.confirm","actor":"$actor","id":"$cardId","milestone":"$milestone","textHash":"$hash"$proofField}""",
        )
    }

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("milestones.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
        tracker.seed(imported)
        proof = OwnerProof.create(dir.resolve("run").resolve("owner.token"), SecureRandom())
        token = Files.readString(dir.resolve("run").resolve("owner.token"))
        val ref = ExternalRef("github", "7", imported.url, clock.instant(), TextHash(imported.textHash))
        val seed = Event(EventId("seed"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Title"))
        recorder.record(cardId, Card.create("Title", Column.CODING).withReference(ref), seed)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private fun eventsOf(type: String): List<Event> = store.events(cardId).filter { it.type.name == type }

    @Test
    fun `AC-1 a started milestone is written once even when the trigger repeats`() {
        val subject = milestones(policies("started"))

        subject.reached(cardId, "STARTED")
        subject.reached(cardId, "STARTED")

        assertThat(tracker.milestones["7"]).containsExactly("started")
        val written = eventsOf("MILESTONE_WRITTEN").single()
        assertThat(written.payload["milestone"]).isEqualTo("started")
        assertThat(written.payload["externalId"]).isEqualTo("7")
        assertThat(written.actor).isEqualTo(Actor.Adapter("github"))
    }

    @Test
    fun `AC-2 an opened PR writes its link to the tracker once`() {
        val subject = milestones(policies("pr_opened"))

        subject.reached(cardId, "PR_OPENED", "12")
        subject.reached(cardId, "PR_OPENED", "12")

        assertThat(tracker.milestones["7"]).containsExactly("https://github.com/acme/alpha/pull/12")
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
    }

    @Test
    fun `AC-4 a milestone that is off writes nothing to the tracker`() {
        val subject = milestones(policies("pr_opened"))
        val defaults = milestones(ProjectPolicies.DEFAULT)

        subject.reached(cardId, "STARTED")
        defaults.reached(cardId, "STARTED")
        defaults.reached(cardId, "PR_OPENED", "12")

        assertThat(tracker.milestones).isEmpty()
        assertThat(tracker.calls["writeMilestone"]).isNull()
        assertThat(eventsOf("MILESTONE_WRITTEN")).isEmpty()
    }

    @Test
    fun `AC-5 a tracker failure does not block the card and the milestone is retried on the next trigger`() {
        val subject = milestones(policies("started"))
        tracker.failNext("writeMilestone")

        subject.reached(cardId, "STARTED")

        assertThat(eventsOf("MILESTONE_WRITTEN")).isEmpty()
        assertThat(tracker.milestones).isEmpty()
        assertThat(logged.any { it.first == "milestone_failed" && it.second["card"] == cardId }).isTrue()
        assertThat(requireNotNull(store.card(cardId)).column).isEqualTo(Column.CODING)

        subject.reached(cardId, "STARTED")

        assertThat(tracker.milestones["7"]).containsExactly("started")
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
    }

    @Test
    fun `AC-7 changed external text warns once per new hash and never carries the text`() {
        val subject = milestones(policies("started", "pr_opened"))
        val changed = item("Leaky title", "leaky body")
        tracker.seed(changed)

        subject.reached(cardId, "STARTED")
        subject.reached(cardId, "PR_OPENED", "12")

        val warning = eventsOf("EXTERNAL_TEXT_CHANGED").single()
        assertThat(warning.payload["externalId"]).isEqualTo("7")
        assertThat(warning.payload["previousHash"]).isEqualTo(imported.textHash)
        assertThat(warning.payload["newHash"]).isEqualTo(changed.textHash)
        assertThat(warning.payload.values.joinToString(" ")).doesNotContain("Leaky", "leaky")
        assertThat(logged.flatMap { it.second.values }.joinToString(" ")).doesNotContain("Leaky", "leaky")
        assertThat(tracker.milestones["7"]).hasSize(2)

        val again = item("Another title", "another body")
        tracker.seed(again)
        milestones(policies("started", "pr_opened")).reached(cardId, "STARTED")

        assertThat(eventsOf("EXTERNAL_TEXT_CHANGED").map { it.payload["newHash"] })
            .containsExactly(changed.textHash, again.textHash)
    }

    @Test
    fun `AC-7 unchanged external text emits no warning`() {
        milestones(policies("started")).reached(cardId, "STARTED")

        assertThat(eventsOf("EXTERNAL_TEXT_CHANGED")).isEmpty()
    }

    @Test
    fun `AC-8 two simultaneous triggers of the same milestone make one write and one event`() {
        val writes = AtomicInteger()
        val hold = CountDownLatch(1)
        val slow = object : Tracker by tracker {
            override fun writeMilestone(id: String, milestone: String) {
                writes.incrementAndGet()
                hold.await(300, TimeUnit.MILLISECONDS)
                tracker.writeMilestone(id, milestone)
            }
        }
        val subject = milestones(policies("started"), slow)
        val start = CyclicBarrier(2)
        val threads = List(2) {
            Thread {
                start.await()
                subject.reached(cardId, "STARTED")
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertThat(writes.get()).isEqualTo(1)
        assertThat(tracker.milestones["7"]).containsExactly("started")
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
    }

    @Test
    fun `AC-1 owner-signed milestone is held as pending once per milestone and nothing reaches the tracker`() {
        val subject = milestones(ownerPolicies("started", "pr_opened"))

        subject.reached(cardId, "STARTED")
        subject.reached(cardId, "STARTED")

        assertThat(tracker.milestones).isEmpty()
        assertThat(tracker.calls["writeMilestone"]).isNull()
        assertThat(eventsOf("MILESTONE_WRITTEN")).isEmpty()
        val held = eventsOf("MILESTONE_PENDING").single()
        assertThat(held.payload["milestone"]).isEqualTo("started")
        assertThat(held.payload["externalId"]).isEqualTo("7")
        assertThat(held.payload["textHash"]).isNotBlank()

        subject.reached(cardId, "PR_OPENED", "12")

        assertThat(eventsOf("MILESTONE_PENDING").map { it.payload["milestone"] }).containsExactly("started", "pr_opened")
    }

    @Test
    fun `AC-2 owner-signed setting declared as policy writes at once without a pending`() {
        milestones(policies("started")).reached(cardId, "STARTED")

        assertThat(tracker.milestones["7"]).containsExactly("started")
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
        assertThat(eventsOf("MILESTONE_PENDING")).isEmpty()
    }

    @Test
    fun `AC-3 owner-signed pending milestones are listed with card, milestone and text`() {
        val subject = milestones(ownerPolicies("started", "pr_opened"))
        subject.reached(cardId, "STARTED")
        subject.reached(cardId, "PR_OPENED", "12")

        val items = pending(subject)

        assertThat(items.map { it["card"] }).containsOnly(cardId)
        assertThat(items.associate { it["milestone"] to it["text"] })
            .containsEntry("started", "started")
            .containsEntry("pr_opened", "https://github.com/acme/alpha/pull/12")
        assertThat(items.map { it["textHash"] }).allSatisfy { assertThat(it).isNotBlank() }
    }

    @Test
    fun `AC-4 owner-signed confirmation is recorded for the owner with the text hash and only then written`() {
        val subject = milestones(ownerPolicies("started"))
        subject.reached(cardId, "STARTED")
        val hash = pending(subject).single().getValue("textHash")

        val response = confirm(subject, hash)

        assertThat(response).isNotInstanceOf(Response.Error::class.java)
        assertThat(tracker.milestones["7"]).containsExactly("started")
        val confirmed = eventsOf("MILESTONE_CONFIRMED").single()
        assertThat(confirmed.actor).isEqualTo(Actor.Owner)
        assertThat(confirmed.payload["milestone"]).isEqualTo("started")
        assertThat(confirmed.payload["textHash"]).isEqualTo(hash)
        val order = store.events(cardId).map { it.type.name }.filter { it.startsWith("MILESTONE_") }
        assertThat(order).containsExactly("MILESTONE_PENDING", "MILESTONE_CONFIRMED", "MILESTONE_WRITTEN")
        assertThat(pending(subject)).isEmpty()
    }

    @Test
    fun `AC-5 owner-signed confirmation with missing or wrong proof, wrong actor or wrong hash is refused`() {
        val subject = milestones(ownerPolicies("started"))
        subject.reached(cardId, "STARTED")
        val hash = pending(subject).single().getValue("textHash")

        val refused = listOf(
            confirm(subject, hash, proofText = null),
            confirm(subject, hash, proofText = "wrong"),
            confirm(subject, hash, actor = "factory:deliver"),
            confirm(subject, "0".repeat(64)),
        )

        assertThat(refused).allSatisfy { assertThat(it).isInstanceOf(Response.Error::class.java) }
        assertThat(eventsOf("MILESTONE_CONFIRMED")).isEmpty()
        assertThat(eventsOf("MILESTONE_WRITTEN")).isEmpty()
        assertThat(tracker.milestones).isEmpty()
        assertThat(tracker.calls["writeMilestone"]).isNull()
    }

    @Test
    fun `AC-6 owner-signed milestone already written is not written again by a second confirmation`() {
        val subject = milestones(ownerPolicies("started"))
        subject.reached(cardId, "STARTED")
        val hash = pending(subject).single().getValue("textHash")
        confirm(subject, hash)

        confirm(subject, hash)

        assertThat(tracker.milestones["7"]).containsExactly("started")
        assertThat(eventsOf("MILESTONE_CONFIRMED")).hasSize(1)
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
    }

    @Test
    fun `AC-7 owner-signed confirmed milestone whose write failed is retried without a second confirmation`() {
        val subject = milestones(ownerPolicies("started"))
        subject.reached(cardId, "STARTED")
        val hash = pending(subject).single().getValue("textHash")
        tracker.failNext("writeMilestone")

        confirm(subject, hash)

        assertThat(eventsOf("MILESTONE_CONFIRMED")).hasSize(1)
        assertThat(eventsOf("MILESTONE_WRITTEN")).isEmpty()
        assertThat(tracker.milestones).isEmpty()

        val retry = confirm(subject, hash)

        assertThat(retry).isNotInstanceOf(Response.Error::class.java)
        assertThat(tracker.milestones["7"]).containsExactly("started")
        assertThat(eventsOf("MILESTONE_CONFIRMED")).hasSize(1)
        assertThat(eventsOf("MILESTONE_WRITTEN")).hasSize(1)
    }

    @Test
    fun `AC-9 an unexpected error never escapes and leaves the card as if the milestone were off`() {
        val before = store.events(cardId)
        val failingWrite = object : Tracker by tracker {
            override fun writeMilestone(id: String, milestone: String): Unit = throw IllegalStateException("boom")
        }
        val failingFetch = object : Tracker by tracker {
            override fun fetch(id: String): TrackerItem? = throw IllegalStateException("boom")
        }

        milestones(policies("started"), failingWrite).reached(cardId, "STARTED")
        milestones(policies("started"), failingFetch).reached(cardId, "STARTED")

        assertThat(store.events(cardId)).isEqualTo(before)
        assertThat(tracker.milestones).isEmpty()
        assertThat(logged.count { it.second["card"] == cardId }).isGreaterThanOrEqualTo(2)
        assertThat(requireNotNull(store.card(cardId)).column).isEqualTo(Column.CODING)
    }
}
