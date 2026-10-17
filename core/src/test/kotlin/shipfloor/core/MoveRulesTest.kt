package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import shipfloor.core.Column.CODING
import shipfloor.core.Column.DONE
import shipfloor.core.Column.INBOX
import shipfloor.core.Column.READY_FOR_DEV
import shipfloor.core.Column.READY_FOR_PROD
import shipfloor.core.Column.READY_FOR_REFINEMENT
import shipfloor.core.Column.REFINEMENT
import shipfloor.core.Column.SPEC_REVIEW
import shipfloor.core.Column.STAGED
import shipfloor.core.Column.VALIDATION
import shipfloor.core.MoveAuthority.FACTORY_OR_OWNER
import shipfloor.core.MoveAuthority.OWNER_ONLY

class MoveRulesTest {
    private val agent = Actor.Agent("engineer")
    private val factory = Actor.Factory("dispatcher")
    private val adapter = Actor.Adapter("github")
    private val nonOwners = listOf(agent, factory, adapter)
    private val allActors = nonOwners + Actor.Owner

    @Test
    fun `AC-1 refuses agent moving Spec Review to Ready for Dev saying only the owner approves`() {
        val decision = MoveRules.check(SPEC_REVIEW, READY_FOR_DEV, agent)

        assertThat(decision).isInstanceOf(MoveDecision.Refused::class.java)
        assertThat((decision as MoveDecision.Refused).reason).containsIgnoringCase("only the owner approves")
    }

    @Test
    fun `AC-2 accepts owner approving Spec Review to Ready for Dev`() {
        assertThat(MoveRules.check(SPEC_REVIEW, READY_FOR_DEV, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
    }

    @Test
    fun `AC-3 refuses factory releasing Staged to Ready for Prod and accepts the owner`() {
        nonOwners.forEach { actor ->
            assertThat(MoveRules.check(STAGED, READY_FOR_PROD, actor)).isInstanceOf(MoveDecision.Refused::class.java)
        }
        assertThat(MoveRules.check(STAGED, READY_FOR_PROD, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
    }

    @Test
    fun `factory closes a released card moving Ready for Prod to Done`() {
        assertThat(MoveRules.check(READY_FOR_PROD, DONE, Actor.Factory("deploy"))).isEqualTo(MoveDecision.Allowed)
    }

    @Test
    fun `AC-4 answers allowed or refused for every pair and actor and refuses pairs outside the table`() {
        for (from in Column.entries) {
            for (to in Column.entries) {
                for (actor in allActors) {
                    val decision = MoveRules.check(from, to, actor)
                    assertThat(decision).isInstanceOfAny(MoveDecision.Allowed::class.java, MoveDecision.Refused::class.java)
                    if ((from to to) !in MoveRules.table) {
                        assertThat(decision).isInstanceOf(MoveDecision.Refused::class.java)
                    }
                }
            }
        }
        val refused = MoveRules.check(INBOX, DONE, Actor.Owner) as MoveDecision.Refused
        assertThat(refused.reason).isEqualTo("move from Inbox to Done is not allowed")
        assertThat(MoveRules.check(DONE, DONE, Actor.Owner)).isInstanceOf(MoveDecision.Refused::class.java)
    }

    @Test
    fun `AC-5 returns an identical result for the same input`() {
        for (from in Column.entries) {
            for (to in Column.entries) {
                for (actor in allActors) {
                    assertThat(MoveRules.check(from, to, actor)).isEqualTo(MoveRules.check(from, to, actor))
                }
            }
        }
    }

    @Test
    fun `a card blocked in Refinement or Coding goes back to its ready column by the owner, and the owner stays the only judge of the others`() {
        assertThat(MoveRules.check(REFINEMENT, READY_FOR_REFINEMENT, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
        assertThat(MoveRules.check(CODING, READY_FOR_DEV, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
        assertThat(MoveRules.check(READY_FOR_DEV, READY_FOR_REFINEMENT, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
        assertThat(MoveRules.check(CODING, READY_FOR_REFINEMENT, Actor.Owner)).isEqualTo(MoveDecision.Allowed)
        assertThat(MoveRules.check(SPEC_REVIEW, READY_FOR_DEV, Actor.Agent("develop"))).isInstanceOf(MoveDecision.Refused::class.java)
    }

    @Test
    fun `AC-6 table is the single source and every column and actor pair follows it`() {
        val expected = mapOf(
            (INBOX to READY_FOR_REFINEMENT) to OWNER_ONLY,
            (READY_FOR_REFINEMENT to REFINEMENT) to FACTORY_OR_OWNER,
            (REFINEMENT to SPEC_REVIEW) to FACTORY_OR_OWNER,
            (REFINEMENT to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
            (SPEC_REVIEW to READY_FOR_DEV) to OWNER_ONLY,
            (SPEC_REVIEW to READY_FOR_REFINEMENT) to OWNER_ONLY,
            (READY_FOR_DEV to CODING) to FACTORY_OR_OWNER,
            (CODING to READY_FOR_DEV) to FACTORY_OR_OWNER,
            (READY_FOR_DEV to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
            (CODING to READY_FOR_REFINEMENT) to FACTORY_OR_OWNER,
            (CODING to VALIDATION) to FACTORY_OR_OWNER,
            (VALIDATION to CODING) to FACTORY_OR_OWNER,
            (VALIDATION to STAGED) to FACTORY_OR_OWNER,
            (STAGED to READY_FOR_PROD) to OWNER_ONLY,
            (STAGED to CODING) to FACTORY_OR_OWNER,
            (READY_FOR_PROD to DONE) to FACTORY_OR_OWNER,
            (READY_FOR_PROD to STAGED) to FACTORY_OR_OWNER,
            (READY_FOR_PROD to VALIDATION) to FACTORY_OR_OWNER,
        )
        assertThat(MoveRules.table).isEqualTo(expected)

        for (from in Column.entries) {
            for (to in Column.entries) {
                for (actor in allActors) {
                    val authority = MoveRules.table[from to to]
                    val allowed = authority != null && (authority == FACTORY_OR_OWNER || actor == Actor.Owner)
                    val decision = MoveRules.check(from, to, actor)
                    if (allowed) {
                        assertThat(decision).isEqualTo(MoveDecision.Allowed)
                    } else {
                        assertThat(decision).isInstanceOf(MoveDecision.Refused::class.java)
                    }
                }
            }
        }
    }
}
