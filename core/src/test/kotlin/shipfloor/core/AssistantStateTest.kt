package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

object AssistantStateFixtures {
    val noon: Instant = Instant.parse("2026-10-16T12:00:00Z")
    val rules = AssistantStateRules(zone = ZoneOffset.UTC)
    private var seq = 0

    fun anEvent(
        type: EventType,
        at: Instant,
        actor: Actor = Actor.Factory("daemon"),
        payload: Map<String, String> = emptyMap(),
    ): Event = Event(EventId("e${seq++}"), at, actor, type, payload)

    fun aCard(column: Column, vararg events: Event): AssistantCard = AssistantCard(column, events.toList())

    fun minutesAgo(minutes: Long): Instant = noon.minus(Duration.ofMinutes(minutes))

    fun blocked(minutes: Long = 120): Event = anEvent(EventType.CARD_BLOCKED, minutesAgo(minutes))

    fun gateFail(minutes: Long): Event =
        anEvent(EventType.GATE_FINISHED, minutesAgo(minutes), payload = mapOf("verdict" to "FAIL"))

    fun gatePass(minutes: Long): Event =
        anEvent(EventType.GATE_FINISHED, minutesAgo(minutes), payload = mapOf("verdict" to "PASS"))

    fun threeGateFailures(): AssistantCard = aCard(Column.DONE, gateFail(10), gateFail(20), gateFail(30))
}

class AssistantStateTest {
    private val f = AssistantStateFixtures

    private fun derive(vararg cards: AssistantCard, now: Instant = f.noon, rules: AssistantStateRules = f.rules) =
        AssistantStates.derive(cards.toList(), now, rules)

    @Test
    fun `AC-1 no card in progress, waiting or blocked outside the night is idle`() {
        val cards = arrayOf(f.aCard(Column.INBOX), f.aCard(Column.READY_FOR_DEV), f.aCard(Column.DONE))
        assertThat(derive(*cards)).isEqualTo(AssistantState.IDLE)
    }

    @Test
    fun `AC-2 a card in a factory work column is working`() {
        listOf(Column.REFINEMENT, Column.CODING, Column.VALIDATION, Column.READY_FOR_PROD).forEach { column ->
            assertThat(derive(f.aCard(column))).describedAs(column.name).isEqualTo(AssistantState.WORKING)
        }
    }

    @Test
    fun `AC-3 a card in Spec Review or Staged waits for the owner`() {
        listOf(Column.SPEC_REVIEW, Column.STAGED).forEach { column ->
            assertThat(derive(f.aCard(column))).describedAs(column.name).isEqualTo(AssistantState.WAITING_OWNER)
        }
    }

    @Test
    fun `AC-2 the single public owner wait set is Spec Review and Staged`() {
        assertThat(OWNER_WAIT_COLUMNS).containsExactlyInAnyOrder(Column.SPEC_REVIEW, Column.STAGED)
    }

    @Test
    fun `AC-2 a card in each owner wait column derives waiting for the owner`() {
        assertThat(OWNER_WAIT_COLUMNS).isNotEmpty()
        OWNER_WAIT_COLUMNS.forEach { column ->
            assertThat(derive(f.aCard(column))).describedAs(column.name).isEqualTo(AssistantState.WAITING_OWNER)
        }
    }

    @Test
    fun `AC-4 CARD_BLOCKED without a later owner move is blocked`() {
        assertThat(derive(f.aCard(Column.CODING, f.blocked()))).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-4 a factory move does not unblock`() {
        val move = f.anEvent(EventType.CARD_MOVED, f.minutesAgo(100), Actor.Factory("daemon"))
        assertThat(derive(f.aCard(Column.CODING, f.blocked(), move))).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-4 an owner move or approval after the block unblocks it`() {
        val moved = f.anEvent(EventType.CARD_MOVED, f.minutesAgo(100), Actor.Owner)
        val approved = f.anEvent(EventType.OWNER_APPROVED, f.minutesAgo(100), Actor.Owner)
        assertThat(derive(f.aCard(Column.CODING, f.blocked(), moved))).isEqualTo(AssistantState.WORKING)
        assertThat(derive(f.aCard(Column.CODING, f.blocked(), approved))).isEqualTo(AssistantState.WORKING)
    }

    @Test
    fun `AC-4 an owner move before the block does not unblock it`() {
        val moved = f.anEvent(EventType.CARD_MOVED, f.minutesAgo(200), Actor.Owner)
        assertThat(derive(f.aCard(Column.CODING, moved, f.blocked(100)))).isEqualTo(AssistantState.BLOCKED)
    }

    private fun unblocked(minutes: Long, actor: Actor): Event {
        val type = EventType.entries.firstOrNull { it.name == "CARD_UNBLOCKED" }
        assertThat(type).describedAs("EventType.CARD_UNBLOCKED exists").isNotNull()
        return f.anEvent(requireNotNull(type) { "CARD_UNBLOCKED missing" }, f.minutesAgo(minutes), actor)
    }

    @Test
    fun `AC-1 an owner CARD_UNBLOCKED after the block closes it and the state is no longer blocked`() {
        val card = f.aCard(Column.CODING, f.blocked(120), unblocked(100, Actor.Owner))
        assertThat(derive(card)).isEqualTo(AssistantState.WORKING)
    }

    @Test
    fun `AC-1 a CARD_UNBLOCKED by a factory or an agent actor does not close the block`() {
        val byFactory = f.aCard(Column.CODING, f.blocked(120), unblocked(100, Actor.Factory("daemon")))
        val byAgent = f.aCard(Column.CODING, f.blocked(120), unblocked(100, Actor.Agent("engineer")))
        assertThat(derive(byFactory)).isEqualTo(AssistantState.BLOCKED)
        assertThat(derive(byAgent)).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-1 an owner CARD_UNBLOCKED before the latest block does not close it`() {
        val card = f.aCard(Column.CODING, unblocked(200, Actor.Owner), f.blocked(100))
        assertThat(derive(card)).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-5 inside the night window is night and outside never is`() {
        val at2330 = Instant.parse("2026-10-16T23:30:00Z")
        val at0300 = Instant.parse("2026-10-17T03:00:00Z")
        val at2200 = Instant.parse("2026-10-16T22:00:00Z")
        val at0700 = Instant.parse("2026-10-17T07:00:00Z")
        assertThat(derive(now = at2330)).isEqualTo(AssistantState.NIGHT)
        assertThat(derive(now = at0300)).isEqualTo(AssistantState.NIGHT)
        assertThat(derive(now = at2200)).isEqualTo(AssistantState.NIGHT)
        assertThat(derive(now = at0700)).isEqualTo(AssistantState.IDLE)
        assertThat(derive(f.aCard(Column.CODING), now = f.noon)).isNotEqualTo(AssistantState.NIGHT)
    }

    @Test
    fun `AC-5 the window is read in the given zone and may not cross midnight`() {
        val saoPaulo = f.rules.copy(zone = ZoneId.of("America/Sao_Paulo"))
        // 01:00Z is 22:00 in Sao Paulo (UTC-3)
        assertThat(derive(now = Instant.parse("2026-10-17T01:00:00Z"), rules = saoPaulo)).isEqualTo(AssistantState.NIGHT)
        assertThat(derive(now = Instant.parse("2026-10-16T23:00:00Z"), rules = saoPaulo)).isEqualTo(AssistantState.IDLE)
        val sameDay = f.rules.copy(nightStart = LocalTime.of(1, 0), nightEnd = LocalTime.of(5, 0))
        assertThat(derive(now = Instant.parse("2026-10-16T03:00:00Z"), rules = sameDay)).isEqualTo(AssistantState.NIGHT)
        assertThat(derive(now = Instant.parse("2026-10-16T23:00:00Z"), rules = sameDay)).isEqualTo(AssistantState.IDLE)
    }

    @Test
    fun `AC-6 N failures inside the window are tired and older ones do not count`() {
        assertThat(derive(f.threeGateFailures())).isEqualTo(AssistantState.TIRED)
        val mixed = f.aCard(Column.DONE, f.gateFail(10), f.blocked(20), f.gateFail(30))
        assertThat(derive(mixed)).isEqualTo(AssistantState.TIRED)
        val oneOld = f.aCard(Column.DONE, f.gateFail(10), f.gateFail(20), f.gateFail(90))
        assertThat(derive(oneOld)).isEqualTo(AssistantState.IDLE)
        val passing = f.aCard(Column.DONE, f.gateFail(10), f.gateFail(20), f.gatePass(30))
        assertThat(derive(passing)).isEqualTo(AssistantState.IDLE)
    }

    @Test
    fun `AC-6 failures from several cards add up and the thresholds are parameters`() {
        val a = f.aCard(Column.DONE, f.gateFail(10), f.gateFail(20))
        val b = f.aCard(Column.DONE, f.gateFail(30))
        assertThat(derive(a, b)).isEqualTo(AssistantState.TIRED)
        val strict = f.rules.copy(tiredFailures = 2, tiredWindow = Duration.ofMinutes(15))
        assertThat(derive(f.aCard(Column.DONE, f.gateFail(5), f.gateFail(10)), rules = strict))
            .isEqualTo(AssistantState.TIRED)
        assertThat(derive(f.aCard(Column.DONE, f.gateFail(5), f.gateFail(20)), rules = strict))
            .isEqualTo(AssistantState.IDLE)
    }

    @Test
    fun `AC-7 night beats tired`() {
        val night = Instant.parse("2026-10-16T23:00:00Z")
        val failures = f.aCard(Column.DONE, *failuresAround(night))
        assertThat(derive(failures, now = night)).isEqualTo(AssistantState.NIGHT)
    }

    @Test
    fun `AC-7 tired beats blocked`() {
        val blockedCard = f.aCard(Column.CODING, f.blocked(100))
        assertThat(derive(blockedCard, f.threeGateFailures())).isEqualTo(AssistantState.TIRED)
    }

    @Test
    fun `AC-7 blocked beats waiting for the owner`() {
        val blockedCard = f.aCard(Column.CODING, f.blocked(120))
        assertThat(derive(blockedCard, f.aCard(Column.SPEC_REVIEW))).isEqualTo(AssistantState.BLOCKED)
    }

    @Test
    fun `AC-7 waiting for the owner beats working`() {
        assertThat(derive(f.aCard(Column.CODING), f.aCard(Column.STAGED))).isEqualTo(AssistantState.WAITING_OWNER)
    }

    @Test
    fun `AC-7 working beats idle`() {
        assertThat(derive(f.aCard(Column.INBOX), f.aCard(Column.VALIDATION))).isEqualTo(AssistantState.WORKING)
    }

    @Test
    fun `AC-8 same input gives the same result even with shuffled events`() {
        val events = listOf(
            f.anEvent(EventType.CARD_CREATED, f.minutesAgo(300)),
            f.blocked(200),
            f.anEvent(EventType.CARD_MOVED, f.minutesAgo(100), Actor.Owner),
        )
        val ordered = AssistantCard(Column.CODING, events)
        val shuffled = AssistantCard(Column.CODING, listOf(events[2], events[0], events[1]))
        val first = AssistantStates.derive(listOf(ordered), f.noon, f.rules)
        assertThat(AssistantStates.derive(listOf(ordered), f.noon, f.rules)).isEqualTo(first)
        assertThat(AssistantStates.derive(listOf(shuffled), f.noon, f.rules)).isEqualTo(first)
        assertThat(first).isEqualTo(AssistantState.WORKING)
    }

    @Test
    fun `AC-8 an empty log is idle, or night inside the window`() {
        assertThat(AssistantStates.derive(emptyList(), f.noon, f.rules)).isEqualTo(AssistantState.IDLE)
        val night = Instant.parse("2026-10-16T23:00:00Z")
        assertThat(AssistantStates.derive(emptyList(), night, f.rules)).isEqualTo(AssistantState.NIGHT)
    }

    private fun failuresAround(now: Instant): Array<Event> =
        arrayOf(10L, 20L, 30L).map { m ->
            f.anEvent(EventType.GATE_FINISHED, now.minus(Duration.ofMinutes(m)), payload = mapOf("verdict" to "FAIL"))
        }.toTypedArray()
}
