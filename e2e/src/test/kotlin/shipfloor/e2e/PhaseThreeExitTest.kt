package shipfloor.e2e

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
import shipfloor.core.DeliveryMode
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.daemon.AutonomousDelivery
import shipfloor.daemon.DaemonLog
import shipfloor.daemon.EventRecorder
import shipfloor.daemon.EventStream
import shipfloor.daemon.OwnerProof
import shipfloor.daemon.ProjectConfigSource
import shipfloor.daemon.ProjectRegistry
import shipfloor.daemon.PullRequestHandoff
import shipfloor.daemon.Response
import shipfloor.daemon.WorktreeManager
import shipfloor.daemon.parseRequest
import shipfloor.ports.ChecksState
import shipfloor.ports.FakeForge
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.store.CardEventStore
import shipfloor.store.Database

/** Phase 3 exit: both delivery modes reach Done on the fake forge, and a deleted database is rebuilt from that forge. */
class PhaseThreeExitTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val silent = DaemonLog { _, _ -> }
    private val forge = FakeForge()
    private var sequence = 0
    private val opened = mutableListOf<Database>()
    private val streams = mutableListOf<EventStream>()

    @BeforeEach
    fun reset() {
        sequence = 0
    }

    @AfterEach
    fun close() {
        streams.forEach { it.close() }
        opened.forEach { it.close() }
    }

    private class World(val store: CardEventStore, val recorder: EventRecorder)

    private fun world(name: String): World {
        val database = Database.open(dir.resolve("$name.db")).also { opened += it }
        val store = CardEventStore(database)
        val stream = EventStream(store).also { streams += it }
        return World(store, EventRecorder(store, stream))
    }

    private fun title(id: String) = "Card ${id.take(8)}"

    /** A card the validate staged on the PR's head, as the real pipeline records it. */
    private fun staged(world: World, id: String, pr: PullRequestRef) {
        fun record(column: Column, actor: Actor, type: EventType, payload: Map<String, String>) =
            world.recorder.record(id, Card.create(title(id), column), Event(EventId("e-${sequence++}"), clock.instant(), actor, type, payload))
        record(Column.VALIDATION, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to title(id)))
        record(
            Column.STAGED,
            Actor.Factory("validate"),
            EventType.CARD_MOVED,
            mapOf("from" to "VALIDATION", "to" to "STAGED", "ci" to "green", "head" to pr.head, "pr" to pr.number.toString()),
        )
    }

    private fun openPr(id: String): PullRequestRef {
        val branch = WorktreeManager.branchName(ProjectPolicies.DEFAULT.conventions.branchFormat, id, title(id))
        return forge.openPullRequest(PullRequestRequest(branch, "main", title(id), "body"))
    }

    private fun toReadyForProd(world: World, id: String) =
        world.recorder.record(
            id,
            Card.create(title(id), Column.READY_FOR_PROD),
            Event(EventId("e-${sequence++}"), clock.instant(), Actor.Owner, EventType.CARD_MOVED, mapOf("from" to "STAGED", "to" to "READY_FOR_PROD")),
        )

    @Test
    fun `AC-8 both delivery modes reach Done and a deleted database is rebuilt to the same stages`() {
        val original = world("original")
        val autonomous = UUID.randomUUID().toString()
        val handoff = UUID.randomUUID().toString()
        val inFlight = UUID.randomUUID().toString()

        val autonomousPr = openPr(autonomous)
        staged(original, autonomous, autonomousPr)
        toReadyForProd(original, autonomous)
        forge.scriptChecks("merge-${autonomousPr.number}", ChecksState.Passed)
        AutonomousDelivery(original.store, original.recorder, { forge }, { ProjectPolicies.DEFAULT }, { }, 3, clock, silent)
            .deliver(autonomous)

        val handoffPr = openPr(handoff)
        staged(original, handoff, handoffPr)
        val prPolicies = ProjectPolicies.DEFAULT.copy(deliveryMode = DeliveryMode.PR_HANDOFF)
        val pullRequestHandoff = PullRequestHandoff(original.store, original.recorder, { forge }, { prPolicies }, clock, silent)
        toReadyForProd(original, handoff)
        forge.mergedOutside(handoffPr)
        pullRequestHandoff.reconcile(handoff)

        val inFlightPr = openPr(inFlight)
        staged(original, inFlight, inFlightPr)
        original.recorder.record(
            inFlight,
            Card.create(title(inFlight), Column.VALIDATION),
            Event(EventId("e-${sequence++}"), clock.instant(), Actor.Factory("validate"), EventType.CARD_MOVED, mapOf("from" to "STAGED", "to" to "VALIDATION")),
        )

        assertThat(original.store.card(autonomous)?.column).isEqualTo(Column.DONE)
        assertThat(original.store.card(handoff)?.column).isEqualTo(Column.DONE)

        val lost = world("lost")
        assertThat(lost.store.cards()).isEmpty()
        val repo = Files.createDirectories(dir.resolve("alpha"))
        Files.createDirectories(repo.resolve(".git"))
        val yaml = "project:\n  name: alpha\nlayers:\n  - name: daemon\n    path: daemon/\n"
        val registry = ProjectRegistry(object : ProjectConfigSource {
            override fun read(repo: Path): String = yaml
        })
        registry.register(repo)
        val proof = OwnerProof.create(dir.resolve("run").resolve("owner.token"), SecureRandom())
        val token = Files.readString(dir.resolve("run").resolve("owner.token"))

        // Looked up by name so a missing class fails an assertion, not the compilation.
        val type = runCatching { Class.forName("shipfloor.daemon.CardRebuild") }.getOrNull()
        assertThat(type).describedAs("class shipfloor.daemon.CardRebuild exists").isNotNull()
        val instance = requireNotNull(type).constructors.single { it.parameterCount == 7 }
            .newInstance(lost.store, lost.recorder, registry, forge, proof, clock, silent)
        val handle = instance.javaClass.methods.single { it.name == "handle" }
        val response = handle.invoke(instance, parseRequest("""{"cmd":"card.rebuild","actor":"owner","proof":"$token"}"""))

        assertThat(response).isInstanceOf(Response.CardsResult::class.java)
        assertThat(lost.store.card(autonomous)?.column).isEqualTo(Column.DONE)
        assertThat(lost.store.card(handoff)?.column).isEqualTo(Column.DONE)
        assertThat(lost.store.card(inFlight)?.column).isEqualTo(Column.VALIDATION)
        assertThat(lost.store.card(inFlight)?.title).isEqualTo(title(inFlight))
        // The validate reopens the same PR for the rebuilt card, so it finds it by the branch the card derives.
        val branch = WorktreeManager.branchName(ProjectPolicies.DEFAULT.conventions.branchFormat, inFlight, title(inFlight))
        assertThat(forge.findOpenPullRequest(branch)?.number).isEqualTo(inFlightPr.number)
    }
}
