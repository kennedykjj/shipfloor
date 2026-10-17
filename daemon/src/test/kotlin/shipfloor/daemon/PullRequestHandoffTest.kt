package shipfloor.daemon

import java.lang.reflect.Modifier
import java.nio.file.Path
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
import shipfloor.core.DeliveryMode
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.ports.FakeForge
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class PullRequestHandoffTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val forge = FakeForge()
    private var sequence = 0
    private val policies = ProjectPolicies.DEFAULT.copy(deliveryMode = DeliveryMode.PR_HANDOFF)

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("handoff.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private fun handoff() = PullRequestHandoff(store, recorder, { forge }, { policies }, clock, DaemonLog { _, _ -> })

    private fun record(cardId: String, column: Column, actor: Actor, type: EventType, payload: Map<String, String>) {
        val event = Event(EventId("e-${sequence++}"), clock.instant(), actor, type, payload)
        recorder.record(cardId, Card.create("Handoff", column), event)
    }

    private fun move(cardId: String, from: Column, to: Column, actor: Actor, extra: Map<String, String> = emptyMap()) =
        record(cardId, to, actor, EventType.CARD_MOVED, mapOf("from" to from.name, "to" to to.name) + extra)

    private fun openPr(cardId: String) =
        forge.openPullRequest(PullRequestRequest("feat/$cardId-handoff", "main", "Handoff", "body"))

    /** A card the validate staged with the PR number, still waiting for the owner's approval. */
    private fun stagedCard(cardId: String): PullRequestRef {
        val pr = openPr(cardId)
        record(cardId, Column.VALIDATION, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Handoff"))
        move(
            cardId, Column.VALIDATION, Column.STAGED, Actor.Factory("validate"),
            mapOf("ci" to "green", "head" to pr.head, "pr" to pr.number.toString()),
        )
        return pr
    }

    /** The owner's approval as the board records it: only `from` and `to`. */
    private fun ownerApproves(cardId: String) = move(cardId, Column.STAGED, Column.READY_FOR_PROD, Actor.Owner)

    private fun waitingCard(cardId: String): PullRequestRef {
        val pr = stagedCard(cardId)
        ownerApproves(cardId)
        return pr
    }

    private fun column(cardId: String) = store.card(cardId)?.column

    private fun lastMove(cardId: String) = store.events(cardId).last { it.type == EventType.CARD_MOVED }

    @Test
    fun `AC-1 the owner approval leaves the PR open, never merges and parks the card in Ready for Prod`() {
        val pr = stagedCard("c1")

        ownerApproves("c1")

        assertThat(column("c1")).isEqualTo(Column.READY_FOR_PROD)
        assertThat(forge.merges).isEmpty()
        assertThat(forge.findOpenPullRequest(pr.branch)).isEqualTo(pr)
        assertThat(handoff().reconcile("c1")).isEqualTo(HandoffOutcome.Waiting)
        assertThat(column("c1")).isEqualTo(Column.READY_FOR_PROD)
    }

    @Test
    fun `AC-1 #178 the public API has no approve and keeps reconcile`() {
        val publicMethods = PullRequestHandoff::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }

        assertThat(publicMethods).doesNotContain("approve").contains("reconcile")
    }

    @Test
    fun `AC-2 #178 the owner card move to Ready for Prod lands with the owner event signed`() {
        stagedCard("c1")

        ownerApproves("c1")

        assertThat(column("c1")).isEqualTo(Column.READY_FOR_PROD)
        assertThat(lastMove("c1").actor).isEqualTo(Actor.Owner)
        assertThat(lastMove("c1").payload).containsEntry("from", "STAGED").containsEntry("to", "READY_FOR_PROD")
    }

    @Test
    fun `AC-3 #178 reconcile never calls Forge merge whatever the PR state`() {
        val open = waitingCard("open")
        val merged = waitingCard("merged")
        val closed = waitingCard("closed")
        forge.mergedOutside(merged)
        forge.closeOutside(closed)

        handoff().reconcile("open")
        handoff().reconcile("merged")
        handoff().reconcile("closed")
        forge.failNext("state")
        handoff().reconcile("open")

        assertThat(forge.merges).isEmpty()
        assertThat(forge.findOpenPullRequest(open.branch)).isEqualTo(open)
    }

    @Test
    fun `AC-2 a PR merged by someone else moves the card to Done without a factory merge`() {
        val pr = waitingCard("c1")
        forge.mergedOutside(pr)

        val outcome = handoff().reconcile("c1")

        assertThat(outcome).isEqualTo(HandoffOutcome.Merged)
        assertThat(column("c1")).isEqualTo(Column.DONE)
        assertThat(lastMove("c1").payload).containsEntry("to", "DONE")
        assertThat(lastMove("c1").actor).isInstanceOf(Actor.Factory::class.java)
        assertThat(forge.merges).isEmpty()
    }

    @Test
    fun `AC-3 a PR closed without merge returns the card to Staged with the reason recorded`() {
        val pr = waitingCard("c1")
        forge.closeOutside(pr)

        val outcome = handoff().reconcile("c1")

        assertThat(outcome).isEqualTo(HandoffOutcome.ClosedUnmerged)
        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(lastMove("c1").payload)
            .containsEntry("from", "READY_FOR_PROD")
            .containsEntry("to", "STAGED")
            .containsEntry("reason", "pr_closed_unmerged")
        assertThat(lastMove("c1").actor).isInstanceOf(Actor.Factory::class.java)
    }

    @Test
    fun `AC-3 an open PR or a forge failure leaves the card waiting and the next cycle decides`() {
        val pr = waitingCard("c1")

        assertThat(handoff().reconcile("c1")).isEqualTo(HandoffOutcome.Waiting)

        forge.failNext("state")
        assertThat(handoff().reconcile("c1")).isEqualTo(HandoffOutcome.ForgeFailed)
        assertThat(column("c1")).isEqualTo(Column.READY_FOR_PROD)

        forge.mergedOutside(pr)
        assertThat(handoff().reconcile("c1")).isEqualTo(HandoffOutcome.Merged)
        assertThat(column("c1")).isEqualTo(Column.DONE)
    }

    @Test
    fun `AC-4 a Staged without pr goes back to the owner as no_pr_recorded and touches no PR`() {
        val other = openPr("other")
        forge.mergedOutside(other)
        record("c1", Column.VALIDATION, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Handoff"))
        move("c1", Column.VALIDATION, Column.STAGED, Actor.Factory("validate"), mapOf("ci" to "green"))
        ownerApproves("c1")

        handoff().reconcile("c1")

        assertThat(column("c1")).isEqualTo(Column.STAGED)
        assertThat(lastMove("c1").payload).containsEntry("to", "STAGED")
        assertThat(lastMove("c1").payload.getValue("reason")).contains("no_pr_recorded")
        assertThat(forge.merges).isEmpty()
    }

    @Test
    fun `AC-4 the pr comes from the validate Staged, not from a later return to the owner`() {
        val pr = stagedCard("c1")
        val decoy = openPr("decoy")
        forge.mergedOutside(decoy)
        move(
            "c1", Column.READY_FOR_PROD, Column.STAGED, Actor.Factory("deliver"),
            mapOf("reason" to "head_mismatch", "pr" to decoy.number.toString()),
        )
        ownerApproves("c1")

        val outcome = handoff().reconcile("c1")

        assertThat(outcome).isEqualTo(HandoffOutcome.Waiting)
        assertThat(column("c1")).isEqualTo(Column.READY_FOR_PROD)

        forge.mergedOutside(pr)
        assertThat(handoff().reconcile("c1")).isEqualTo(HandoffOutcome.Merged)
        assertThat(column("c1")).isEqualTo(Column.DONE)
    }
}
