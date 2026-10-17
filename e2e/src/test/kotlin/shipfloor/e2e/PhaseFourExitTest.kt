package shipfloor.e2e

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
import shipfloor.core.ProjectPolicies
import shipfloor.daemon.AutonomousDelivery
import shipfloor.daemon.CardClosure
import shipfloor.daemon.CardImport
import shipfloor.daemon.DaemonLog
import shipfloor.daemon.EventRecorder
import shipfloor.daemon.EventStream
import shipfloor.daemon.OwnerProof
import shipfloor.daemon.ProjectConfigSource
import shipfloor.daemon.ProjectRegistry
import shipfloor.daemon.Response
import shipfloor.daemon.WorktreeManager
import shipfloor.daemon.parseRequest
import shipfloor.ports.ChecksState
import shipfloor.ports.ExternalText
import shipfloor.ports.FakeForge
import shipfloor.ports.FakeTracker
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.store.CardEventStore
import shipfloor.store.Database

/** Phase 4 exit: a card is imported, delivered and closed on confirmation, and the tracker is touched only by those two commands. */
class PhaseFourExitTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val silent = DaemonLog { _, _ -> }
    private val tracker = FakeTracker()
    private val forge = FakeForge()
    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private var sequence = 0

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("factory.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    /** Moves the stored card to [column] keeping its external references, as the factory does. */
    private fun record(id: String, column: Column, actor: Actor, type: EventType, payload: Map<String, String>) {
        val current = requireNotNull(store.card(id)) { "card $id not found" }
        val moved = current.references.fold(Card.create(current.title, column)) { card, ref -> card.withReference(ref) }
        recorder.record(id, moved, Event(EventId("e-${sequence++}"), clock.instant(), actor, type, payload))
    }

    @Test
    fun `AC-3 the tracker is not called between the import and the confirmed closure`() {
        val repo = Files.createDirectories(dir.resolve("alpha"))
        Files.createDirectories(repo.resolve(".git"))
        val registry = ProjectRegistry(object : ProjectConfigSource {
            override fun read(repo: Path): String = "project:\n  name: alpha\nlayers:\n  - name: daemon\n    path: daemon/\n"
        })
        registry.register(repo)
        val tokenFile = dir.resolve("run").resolve("owner.token")
        val proof = OwnerProof.create(tokenFile, SecureRandom())
        val token = Files.readString(tokenFile)
        tracker.seed(
            TrackerItem.of(
                "7", ExternalText("Issue title"), ExternalText("body"), emptyList(),
                "https://github.com/acme/alpha/issues/7", TrackerItemState.OPEN,
            ),
        )

        val imported = CardImport(store, recorder, registry, tracker, proof, clock, silent)
            .handle(parseRequest("""{"cmd":"card.import","actor":"owner","project":"alpha","id":"7","proof":"$token"}"""))
        val id = (imported as Response.CardsResult).cards.single().id
        val afterImport = tracker.calls.toMap()
        assertThat(afterImport).isEqualTo(mapOf("fetch" to 1))

        // Work: validate stages the card on a PR of the fake forge, the owner releases it and the autonomous delivery merges it.
        val title = requireNotNull(store.card(id)).title
        val branch = WorktreeManager.branchName(ProjectPolicies.DEFAULT.conventions.branchFormat, id, title)
        val pr = forge.openPullRequest(PullRequestRequest(branch, "main", title, "body"))
        record(
            id, Column.STAGED, Actor.Factory("validate"), EventType.CARD_MOVED,
            mapOf("from" to "VALIDATION", "to" to "STAGED", "ci" to "green", "head" to pr.head, "pr" to pr.number.toString()),
        )
        record(id, Column.READY_FOR_PROD, Actor.Owner, EventType.CARD_MOVED, mapOf("from" to "STAGED", "to" to "READY_FOR_PROD"))
        forge.scriptChecks("merge-${pr.number}", ChecksState.Passed)
        AutonomousDelivery(store, recorder, { forge }, { ProjectPolicies.DEFAULT }, { }, 3, clock, silent).deliver(id)
        assertThat(store.card(id)?.column).isEqualTo(Column.DONE)
        assertThat(tracker.calls).isEqualTo(afterImport)

        val closure = CardClosure(store, recorder, registry, tracker, { _, number -> "https://github.com/acme/alpha/pull/$number" }, proof, clock, silent)
        val draft = closure.handle(parseRequest("""{"cmd":"card.close.draft","id":"$id"}"""))
        assertThat(draft).isInstanceOf(Response.CloseDraftResult::class.java)
        assertThat(tracker.calls).isEqualTo(afterImport)

        val hash = (draft as Response.CloseDraftResult).draftHash
        val confirmed = closure.handle(parseRequest("""{"cmd":"card.close.confirm","actor":"owner","id":"$id","draftHash":"$hash","proof":"$token"}"""))
        assertThat(confirmed).isInstanceOf(Response.CloseDraftResult::class.java)
        assertThat(tracker.calls).isEqualTo(afterImport + ("close" to 1))
        assertThat(tracker.closures).containsOnlyKeys("7")

        closure.handle(parseRequest("""{"cmd":"card.close.confirm","actor":"owner","id":"$id","draftHash":"$hash","proof":"$token"}"""))
        assertThat(tracker.calls["close"]).isEqualTo(1)
    }
}
