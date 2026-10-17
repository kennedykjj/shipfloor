package shipfloor.daemon

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
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.core.ProjectPolicies
import shipfloor.ports.ChecksState
import shipfloor.ports.FailedCheck
import shipfloor.ports.FakeForge
import shipfloor.ports.Forge
import shipfloor.ports.MergedPullRequest
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.store.CardEventStore
import shipfloor.store.Database

class AutonomousDeliveryTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var database: Database
    private lateinit var store: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var recorder: EventRecorder
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)
    private val forge = FakeForge()
    private var pauses = 0
    private var pauseHook: () -> Unit = {}
    private var sequence = 0
    private val policies = ProjectPolicies.DEFAULT

    @BeforeEach
    fun open() {
        database = Database.open(dir.resolve("delivery.db"))
        store = CardEventStore(database)
        stream = EventStream(store)
        recorder = EventRecorder(store, stream)
    }

    @AfterEach
    fun close() {
        stream.close()
        database.close()
    }

    private val pushed = mutableListOf<String>()
    private var resolverOutcome: ConflictOutcome = ConflictOutcome.OverLimit
    private val resolverCalls = mutableListOf<String>()
    private var onPush: () -> Unit = {}

    private fun delivery(maxPolls: Int = 3) = AutonomousDelivery(
        store, recorder, { forge }, { policies }, { pauses++; pauseHook() }, maxPolls, clock, DaemonLog { _, _ -> },
        conflicts = ConflictResolver { cardId, _ ->
            resolverCalls.add(cardId)
            resolverOutcome
        },
        publisher = BranchPublisher { _, branch ->
            pushed.add(branch)
            onPush()
            PushResult.Pushed
        },
        worktreeOf = { dir.resolve("wt-$it") },
    )

    private fun record(cardId: String, column: Column, actor: Actor, type: EventType, payload: Map<String, String>) {
        val event = Event(EventId("e-${sequence++}"), clock.instant(), actor, type, payload)
        recorder.record(cardId, Card.create("Delivery", column), event)
    }

    private fun move(cardId: String, from: Column, to: Column, actor: Actor, extra: Map<String, String> = emptyMap()) =
        record(cardId, to, actor, EventType.CARD_MOVED, mapOf("from" to from.name, "to" to to.name) + extra)

    private fun approve(cardId: String) = move(cardId, Column.STAGED, Column.READY_FOR_PROD, Actor.Owner)

    /** A card the validate staged on the PR's current head and the owner moved to Ready for Prod. */
    private fun approvedCard(cardId: String): PullRequestRef {
        val pr = forge.openPullRequest(PullRequestRequest("feat/$cardId-delivery", "main", "Delivery", "body"))
        record(cardId, Column.VALIDATION, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Delivery"))
        move(cardId, Column.VALIDATION, Column.STAGED, Actor.Factory("validate"), mapOf("ci" to "green", "head" to pr.head))
        approve(cardId)
        return pr
    }

    private fun prMergedType(): EventType {
        val type = EventType.entries.firstOrNull { it.name == "PR_MERGED" }
        assertThat(type).`as`("EventType has PR_MERGED").isNotNull()
        return requireNotNull(type)
    }

    private fun merged(cardId: String) = store.events(cardId).filter { it.type.name == "PR_MERGED" }

    private fun column(cardId: String) = store.card(cardId)?.column

    private fun lastMove(cardId: String) = store.events(cardId).last { it.type == EventType.CARD_MOVED }

    private fun greenOn(pr: PullRequestRef) = forge.scriptChecks("merge-${pr.number}", ChecksState.Passed)

    /**
     * Main is red on the merge commit. Once a rerun was requested the forge first keeps showing the old red, then
     * shows Pending on the first pause and [afterPending] from the second pause on (an extra pause is harmless).
     */
    private fun redThenRerun(pr: PullRequestRef, red: ChecksState, afterPending: ChecksState) {
        val commit = "merge-${pr.number}"
        forge.scriptChecks(commit, red)
        var pausesSinceRerun = 0
        pauseHook = {
            if (forge.reruns.isNotEmpty()) {
                pausesSinceRerun++
                forge.scriptChecks(commit, if (pausesSinceRerun == 1) ChecksState.Pending else afterPending)
            }
        }
    }

    private fun unitTestsRed(summary: String = "boom") = ChecksState.Failed(listOf(FailedCheck("unit-tests", summary)))

    private fun returned(outcome: DeliveryOutcome, cardId: String, vararg parts: String) {
        assertThat(outcome).isInstanceOf(DeliveryOutcome.ReturnedToOwner::class.java)
        val reason = (outcome as DeliveryOutcome.ReturnedToOwner).reason
        assertThat(reason).contains(*parts)
        assertThat(column(cardId)).isEqualTo(Column.STAGED)
        assertThat(lastMove(cardId).payload["to"]).isEqualTo("STAGED")
        assertThat(lastMove(cardId).actor).isEqualTo(Actor.Factory("deliver"))
        assertThat(lastMove(cardId).payload["reason"]).contains(*parts)
    }

    @Test
    fun `AC-1 merges the approved head and closes the card on a green main`() {
        val pr = approvedCard("c1")
        greenOn(pr)

        val outcome = delivery().deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges.map { it.head }).containsExactly(pr.head)
        assertThat(column("c1")).isEqualTo(Column.DONE)
        assertThat(lastMove("c1").payload).containsEntry("merge_commit", "merge-${pr.number}").containsEntry("ci", "green")
        assertThat(lastMove("c1").actor).isEqualTo(Actor.Factory("deliver"))
    }

    @Test
    fun `AC-2 a head that is not the approved one is never merged and goes back to the owner with both shas`() {
        val pr = approvedCard("c1")
        forge.pushHead(pr, "sha-pushed-later")

        val outcome = delivery().deliver("c1")

        assertThat(forge.merges).isEmpty()
        returned(outcome, "c1", "head_mismatch", pr.head, "sha-pushed-later")
        assertThat(merged("c1")).isEmpty()
    }

    @Test
    fun `AC-3 conflict, closed PR and forge failure send the card back to the owner without a merge`() {
        val conflicted = approvedCard("c1")
        forge.scriptConflict(conflicted)
        returned(delivery().deliver("c1"), "c1", "merge_conflict")

        val closed = approvedCard("c2")
        forge.closeOutside(closed)
        returned(delivery().deliver("c2"), "c2", "pr_not_open")

        approvedCard("c3")
        forge.failNext("merge")
        returned(delivery().deliver("c3"), "c3", "forge_failed")

        assertThat(forge.merges).isEmpty()
        listOf("c1", "c2", "c3").forEach { assertThat(merged(it)).isEmpty() }
    }

    @Test
    fun `AC-4 only the green of the merge commit on main closes the card`() {
        val pr = approvedCard("c1")
        forge.scriptChecks(pr.head, ChecksState.Passed)

        val outcome = delivery().deliver("c1")

        returned(outcome, "c1", "main_ci_timeout")
        assertThat(merged("c1")).hasSize(1)
        assertThat(store.events("c1").none { it.type == EventType.CARD_MOVED && it.payload["to"] == "DONE" }).isTrue()
    }

    @Test
    fun `AC-5 a red main that stays red after the rerun goes back to the owner with the failing check names and a capped summary`() {
        val pr = approvedCard("c1")
        redThenRerun(pr, unitTestsRed("x".repeat(5000)), unitTestsRed("x".repeat(5000)))

        val outcome = delivery(maxPolls = 8).deliver("c1")

        returned(outcome, "c1", "main_ci_red", "unit-tests")
        assertThat(lastMove("c1").payload.getValue("reason").toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(2048)
        assertThat(column("c1")).isNotEqualTo(Column.DONE)
    }

    @Test
    fun `AC-1 a red main is rerun once and a green rerun closes the card without a revert`() {
        val pr = approvedCard("c1")
        redThenRerun(pr, unitTestsRed(), ChecksState.Passed)

        val outcome = delivery(maxPolls = 8).deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.reruns).containsExactly("merge-${pr.number}")
        assertThat(forge.reverts).isEmpty()
        assertThat(column("c1")).isEqualTo(Column.DONE)
    }

    @Test
    fun `AC-2 a second red after the rerun opens a revert PR and records its number on the card`() {
        val pr = approvedCard("c1")
        redThenRerun(pr, unitTestsRed(), unitTestsRed())

        val outcome = delivery(maxPolls = 8).deliver("c1")

        returned(outcome, "c1", "main_ci_red", "unit-tests")
        assertThat(forge.reruns).containsExactly("merge-${pr.number}")
        assertThat(forge.reverts.map { it.number }).containsExactly(pr.number)
        val revertPr = lastMove("c1").payload.getValue("revert_pr").toInt()
        assertThat(revertPr).isNotEqualTo(pr.number).isPositive()
        assertThat(merged("c1")).hasSize(1)
    }

    @Test
    fun `AC-2 a rerun already recorded before a restart goes straight to the revert on red`() {
        val pr = approvedCard("c1")
        record(
            "c1", Column.READY_FOR_PROD, Actor.Factory("deliver"), prMergedType(),
            mapOf("pr" to pr.number.toString(), "merge_commit" to "merge-${pr.number}", "head" to pr.head),
        )
        record(
            "c1", Column.READY_FOR_PROD, Actor.Factory("deliver"), EventType.MAIN_CI_RERUN,
            mapOf("merge_commit" to "merge-${pr.number}", "pr" to pr.number.toString()),
        )
        forge.scriptChecks("merge-${pr.number}", unitTestsRed())

        val outcome = delivery().deliver("c1")

        returned(outcome, "c1", "main_ci_red")
        assertThat(forge.reruns).isEmpty()
        assertThat(forge.reverts.map { it.number }).containsExactly(pr.number)
        assertThat(lastMove("c1").payload).containsKey("revert_pr")
    }

    @Test
    fun `AC-3 a rerun still running does not close the card, is not rerun again and survives a restart`() {
        val pr = approvedCard("c1")
        forge.scriptChecks("merge-${pr.number}", unitTestsRed())
        pauseHook = {
            if (forge.reruns.isNotEmpty()) forge.scriptChecks("merge-${pr.number}", ChecksState.Pending)
        }

        val outcome = delivery(maxPolls = 6).deliver("c1")

        returned(outcome, "c1", "main_ci_timeout")
        assertThat(forge.reruns).containsExactly("merge-${pr.number}")
        assertThat(forge.reverts).isEmpty()
        assertThat(store.events("c1").none { it.type == EventType.CARD_MOVED && it.payload["to"] == "DONE" }).isTrue()

        approve("c1")
        val afterRestart = delivery(maxPolls = 6).deliver("c1")

        returned(afterRestart, "c1", "main_ci_timeout")
        assertThat(forge.reruns).hasSize(1)
        assertThat(forge.reverts).isEmpty()
        assertThat(store.events("c1").count { it.type == EventType.MAIN_CI_RERUN }).isEqualTo(1)
    }

    @Test
    fun `AC-4 the owner sees the rerun event and the return event with the red checks and the revert PR`() {
        val pr = approvedCard("c1")
        redThenRerun(pr, unitTestsRed(), unitTestsRed())

        delivery(maxPolls = 8).deliver("c1")

        val events = store.events("c1")
        val rerun = events.single { it.type == EventType.MAIN_CI_RERUN }
        assertThat(rerun.actor).isEqualTo(Actor.Factory("deliver"))
        assertThat(rerun.payload).containsEntry("merge_commit", "merge-${pr.number}").containsEntry("pr", pr.number.toString())
        val back = lastMove("c1")
        assertThat(back.actor).isEqualTo(Actor.Factory("deliver"))
        assertThat(back.payload.getValue("reason")).contains("main_ci_red", "unit-tests")
        assertThat(back.payload.getValue("revert_pr")).matches("[0-9]+")
        assertThat(events.indexOf(rerun)).isLessThan(events.indexOf(back))
    }

    @Test
    fun `AC-5 the old red of the same commit is not a second red when no pending shows up after the rerun`() {
        val pr = approvedCard("c1")
        forge.scriptChecks("merge-${pr.number}", unitTestsRed())

        val outcome = delivery(maxPolls = 4).deliver("c1")

        returned(outcome, "c1", "rerun_not_started")
        assertThat(forge.reruns).containsExactly("merge-${pr.number}")
        assertThat(forge.reverts).isEmpty()
        assertThat(column("c1")).isEqualTo(Column.STAGED)
    }

    @Test
    fun `AC-6 a failed rerun call does not spend the rerun and opens no revert`() {
        val pr = approvedCard("c1")
        forge.scriptChecks("merge-${pr.number}", unitTestsRed())
        forge.failNext("rerunChecks")

        returned(delivery(maxPolls = 8).deliver("c1"), "c1", "forge_failed")
        assertThat(forge.reruns).isEmpty()
        assertThat(forge.reverts).isEmpty()
        assertThat(store.events("c1").none { it.type == EventType.MAIN_CI_RERUN }).isTrue()

        redThenRerun(pr, unitTestsRed(), ChecksState.Passed)
        approve("c1")
        val outcome = delivery(maxPolls = 8).deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.reruns).containsExactly("merge-${pr.number}")
        assertThat(forge.reverts).isEmpty()
    }

    @Test
    fun `AC-5 a main without result within the polls goes back to the owner`() {
        approvedCard("c1")

        val outcome = delivery(maxPolls = 3).deliver("c1")

        returned(outcome, "c1", "main_ci_timeout")
        assertThat(pauses).isGreaterThan(0)
    }

    @Test
    fun `AC-7 a merge already recorded is not repeated after a restart and only the main CI is followed`() {
        val pr = approvedCard("c1")
        record(
            "c1", Column.READY_FOR_PROD, Actor.Factory("deliver"), prMergedType(),
            mapOf("pr" to pr.number.toString(), "merge_commit" to "merge-${pr.number}", "head" to pr.head),
        )
        greenOn(pr)

        val outcome = delivery().deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges).isEmpty()
        assertThat(merged("c1")).hasSize(1)
        assertThat(delivery().deliver("c1")).isEqualTo(DeliveryOutcome.Skipped)
        assertThat(merged("c1")).hasSize(1)
    }

    @Test
    fun `AC-8 an approval after a red main reuses the validate head, does not merge twice and closes on green`() {
        val pr = approvedCard("c1")
        redThenRerun(pr, unitTestsRed(), unitTestsRed())
        returned(delivery(maxPolls = 8).deliver("c1"), "c1", "main_ci_red")

        pauseHook = {}
        greenOn(pr)
        approve("c1")
        val outcome = delivery().deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges).hasSize(1)
        assertThat(merged("c1")).hasSize(1)
        assertThat(column("c1")).isEqualTo(Column.DONE)
    }

    @Test
    fun `AC-8 an approval after a head mismatch merges the validate head and ignores the return event`() {
        val pr = approvedCard("c1")
        forge.pushHead(pr, "sha-pushed-later")
        returned(delivery().deliver("c1"), "c1", "head_mismatch")

        forge.pushHead(pr, pr.head)
        greenOn(pr)
        approve("c1")
        val outcome = delivery().deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges.map { it.head }).containsExactly(pr.head)
    }

    /** The fake forge, but the merged lookup reports [mergedBy], and a found open PR may get merged outside. */
    private inner class OutsideForge(
        private val mergedBy: String?,
        private val mergeOutsideAfterFind: Boolean = false,
    ) : Forge by forge {
        override fun findMergedPullRequest(branch: String): MergedPullRequest? =
            forge.findMergedPullRequest(branch)?.copy(mergedBy = mergedBy)

        override fun findOpenPullRequest(branch: String): PullRequestRef? =
            forge.findOpenPullRequest(branch)?.also { if (mergeOutsideAfterFind) forge.mergedOutside(it) }
    }

    private val logs = mutableListOf<Pair<String, Map<String, String>>>()

    private fun deliveryOn(other: Forge) = AutonomousDelivery(
        store, recorder, { other }, { policies }, { pauses++ }, 3, clock, DaemonLog { event, fields -> logs += event to fields },
    )

    private fun assertDoneOutside(outcome: DeliveryOutcome, cardId: String, pr: PullRequestRef) {
        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges).isEmpty()
        assertThat(pauses).isZero()
        assertThat(column(cardId)).isEqualTo(Column.DONE)
        val event = merged(cardId).single()
        assertThat(event.payload)
            .containsEntry("pr", pr.number.toString())
            .containsEntry("merge_commit", "merge-${pr.number}")
            .containsEntry("head", pr.head)
            .containsEntry("origin", "external")
        assertThat(lastMove(cardId).payload)
            .containsEntry("to", "DONE")
            .containsEntry("merge_commit", "merge-${pr.number}")
            .containsEntry("origin", "external")
            .containsEntry("reason", "merged_outside_factory")
        assertThat(logs).anySatisfy {
            assertThat(it.first).isEqualTo("delivery_done")
            assertThat(it.second).containsEntry("origin", "external")
        }
    }

    @Test
    fun `AC-1 AC-2 a PR merged outside closes the card without a merge or the main CI and records the external merge`() {
        val pr = approvedCard("c1")
        forge.mergedOutside(pr)

        val outcome = deliveryOn(OutsideForge(mergedBy = null)).deliver("c1")

        assertDoneOutside(outcome, "c1", pr)
        assertThat(merged("c1").single().payload).doesNotContainKey("merged_by")
    }

    @Test
    fun `AC-3 a PR merged outside between the lookup and the merge ends like one found merged`() {
        val pr = approvedCard("c1")

        val outcome = deliveryOn(OutsideForge(mergedBy = null, mergeOutsideAfterFind = true)).deliver("c1")

        assertDoneOutside(outcome, "c1", pr)
    }

    @Test
    fun `AC-4 a merge made by the factory is not marked external`() {
        val pr = approvedCard("c1")
        greenOn(pr)

        assertThat(delivery().deliver("c1")).isEqualTo(DeliveryOutcome.Done)

        assertThat(merged("c1").single().payload).doesNotContainKey("origin")
        assertThat(lastMove("c1").payload).doesNotContainKey("origin")
    }

    @Test
    fun `AC-5 a PR closed without merge goes back to the owner as pr_not_open with no external merge`() {
        val pr = approvedCard("c1")
        forge.closeOutside(pr)

        returned(deliveryOn(OutsideForge(mergedBy = null)).deliver("c1"), "c1", "pr_not_open")

        assertThat(merged("c1")).isEmpty()
    }

    @Test
    fun `AC-6 a forge failure on the merged lookup goes back to the owner as forge_failed`() {
        val pr = approvedCard("c1")
        forge.closeOutside(pr)
        forge.failNext("findMergedPullRequest")

        returned(delivery().deliver("c1"), "c1", "forge_failed")

        assertThat(merged("c1")).isEmpty()
    }

    @Test
    fun `AC-7 a PR merged outside with another head is not delivered and keeps the external merge for the owner`() {
        val pr = approvedCard("c1")
        forge.pushHead(pr, "sha-pushed-later")
        forge.mergedOutside(pr)

        val outcome = deliveryOn(OutsideForge(mergedBy = null)).deliver("c1")

        returned(outcome, "c1", "merged_head_differs", pr.head, "sha-pushed-later")
        assertThat(store.events("c1").none { it.type == EventType.CARD_MOVED && it.payload["to"] == "DONE" }).isTrue()
        val event = merged("c1").single()
        assertThat(event.payload).containsEntry("origin", "external").containsEntry("head", "sha-pushed-later")
    }

    @Test
    fun `AC-8 the external merge records who merged it in the event and never in the log`() {
        val pr = approvedCard("c1")
        forge.mergedOutside(pr)

        val outcome = deliveryOn(OutsideForge(mergedBy = "octo-cat")).deliver("c1")

        assertDoneOutside(outcome, "c1", pr)
        assertThat(merged("c1").single().payload).containsEntry("merged_by", "octo-cat")
        assertThat(logs.toString()).doesNotContain("octo-cat")
    }

    @Test
    fun `AC-4 a resolved conflict publishes the new head and returns the card to Validation, it is not merged before the gates are green again`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.Resolved("head-2")

        delivery().deliver("c1")

        val branch = WorktreeManager.branchName(policies.conventions.branchFormat, "c1", "Delivery")
        assertThat(resolverCalls).containsExactly("c1")
        assertThat(pushed).containsExactly(branch)
        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(lastMove("c1").payload).containsEntry("to", "VALIDATION").containsEntry("reason", "conflict_round")
        assertThat(lastMove("c1").payload).containsKey("round")
        assertThat(lastMove("c1").actor).isEqualTo(Actor.Factory("deliver"))
        assertThat(merged("c1")).isEmpty()
        assertThat(delivery().deliver("c1")).isEqualTo(DeliveryOutcome.Skipped)
        assertThat(column("c1")).isNotEqualTo(Column.DONE)
    }

    @Test
    fun `a branch that only fell behind main keeps the approval once the new head is green, and is merged`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.Resolved("head-2", cleanFrom = pr.head)
        onPush = { forge.pushHead(pr, "head-2") }
        forge.scriptChecks("head-2", ChecksState.Passed)
        greenOn(pr)

        val outcome = delivery().deliver("c1")

        assertThat(outcome).isEqualTo(DeliveryOutcome.Done)
        assertThat(forge.merges.map { it.head }).containsExactly("head-2")
        assertThat(column("c1")).isEqualTo(Column.DONE)
        assertThat(store.events("c1").filter { it.payload["reason"] == "approval_carried" }.single().payload)
            .containsEntry("head", "head-2").containsEntry("previous_head", pr.head)
        assertThat(store.events("c1").none { it.payload["to"] == "VALIDATION" }).isTrue()
        assertThat(resolverCalls).containsExactly("c1")
    }

    @Test
    fun `a clean merge whose new head is not green goes back to Validation instead of keeping the approval`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.Resolved("head-2", cleanFrom = pr.head)
        onPush = { forge.pushHead(pr, "head-2") }
        forge.scriptChecks("head-2", unitTestsRed())

        delivery().deliver("c1")

        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(merged("c1")).isEmpty()
        assertThat(store.events("c1").none { it.payload["reason"] == "approval_carried" }).isTrue()
    }

    @Test
    fun `a clean merge over a head that was not the approved one never keeps the approval`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.Resolved("head-2", cleanFrom = "some-unreviewed-commit")
        onPush = { forge.pushHead(pr, "head-2") }
        forge.scriptChecks("head-2", ChecksState.Passed)

        delivery().deliver("c1")

        assertThat(column("c1")).isEqualTo(Column.VALIDATION)
        assertThat(merged("c1")).isEmpty()
    }

    @Test
    fun `AC-2 a conflict over the limits goes back to the owner as merge_conflict`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.OverLimit

        returned(delivery().deliver("c1"), "c1", "merge_conflict")

        assertThat(pushed).isEmpty()
    }

    @Test
    fun `AC-3 a conflict that exhausted the rounds blocks the card with an explicit reason`() {
        val pr = approvedCard("c1")
        forge.scriptConflict(pr)
        resolverOutcome = ConflictOutcome.RoundsExhausted

        delivery().deliver("c1")

        val blocked = store.events("c1").last { it.type == EventType.CARD_BLOCKED }
        assertThat(requireNotNull(blocked.payload["reason"])).contains("conflict_rounds_exhausted")
        assertThat(pushed).isEmpty()
        assertThat(merged("c1")).isEmpty()
    }

    @Test
    fun `AC-8 a card without a validate head goes back to the owner as no_approved_head`() {
        forge.openPullRequest(PullRequestRequest("feat/c1-delivery", "main", "Delivery", "body"))
        record("c1", Column.STAGED, Actor.Owner, EventType.CARD_CREATED, mapOf("title" to "Delivery"))
        approve("c1")

        returned(delivery().deliver("c1"), "c1", "no_approved_head")

        assertThat(forge.merges).isEmpty()
    }
}
