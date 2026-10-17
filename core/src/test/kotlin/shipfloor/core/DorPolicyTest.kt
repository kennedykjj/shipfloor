package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DorPolicyTest {
    private val all = DorField.entries.toSet()

    @Test
    fun `nothing is missing when the policy requires nothing`() {
        assertThat(DorPolicy.missing(emptySet(), "", "", emptyList())).isEmpty()
    }

    @Test
    fun `missing fields come back in enum order`() {
        assertThat(DorPolicy.missing(all, " ", "", emptyList()))
            .containsExactly(DorField.TITLE, DorField.BODY, DorField.ACCEPTANCE_CRITERIA, DorField.LABEL)
    }

    @Test
    fun `acceptance criteria are matched case-insensitively in both languages`() {
        assertThat(DorPolicy.missing(all, "t", "Critérios de Aceitação: x", listOf("a"))).isEmpty()
        assertThat(DorPolicy.missing(all, "t", "ACCEPTANCE CRITERIA: x", listOf("a"))).isEmpty()
        assertThat(DorPolicy.missing(all, "t", "just text", listOf("a"))).containsExactly(DorField.ACCEPTANCE_CRITERIA)
    }

    @Test
    fun `only required fields are reported`() {
        assertThat(DorPolicy.missing(setOf(DorField.LABEL), "", "", emptyList())).containsExactly(DorField.LABEL)
    }
}
