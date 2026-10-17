package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AcceptanceRulesTest {
    private val keep = Regex("assertThat|assertEquals")

    @Test
    fun `AC-3 an edit that removes or replaces an assertion line is reported, once through the line it removed`() {
        val diff = sequenceOf(
            "diff --git a/FooTest.kt b/FooTest.kt",
            "--- a/FooTest.kt",
            "+++ b/FooTest.kt",
            "@@ -3,2 +3,2 @@",
            " fun test() {",
            "-    assertThat(x).isEqualTo(1)",
            "+    assertThat(x).isEqualTo(2)",
            "+    val y = 2",
        )

        val touched = AcceptanceRules.assertionEdit(diff, keep)

        assertThat(touched).hasSize(1)
        assertThat(touched.single()).contains("isEqualTo(1)")
    }

    @Test
    fun `AC-3 an assertion that is only added is not an edit, but a disabling marker is`() {
        val diff = sequenceOf(
            "--- a/FooTest.kt",
            "+++ b/FooTest.kt",
            "+    assertThat(y).isEqualTo(2)",
            "+    @Disabled",
            "+    assumeTrue(flag)",
        )

        val touched = AcceptanceRules.assertionEdit(diff, keep)

        assertThat(touched).containsExactly("+    @Disabled", "+    assumeTrue(flag)")
    }

    @Test
    fun `AC-3 an edit that touches no assertion passes, ignoring diff headers and comment-only lines`() {
        val diff = sequenceOf(
            "diff --git a/assertThatFooTest.kt b/assertThatFooTest.kt",
            "--- a/assertThatFooTest.kt",
            "+++ b/assertThatFooTest.kt",
            "@@ -1,2 +1,3 @@",
            " fun test() {",
            "+    // assertThat(x) must fail by assertion",
            "-    val y = 1",
            "+    val y = 2",
            "     assertThat(x).isEqualTo(1)",
        )

        assertThat(AcceptanceRules.assertionEdit(diff, keep)).isEmpty()
    }

    @Test
    fun `AC-4 selector accepts only the allowed characters and never starts with a dash`() {
        listOf("FooTest", "shipfloor.core.FooTest.*", "Foo#bar", "a_b-c", "Foo\$Inner").forEach {
            assertThat(GateInputs.selector(it)).isEqualTo(it)
        }
        listOf("", "-x", "--tests", "a b", "a;b", "a/b", "a\nb").forEach { bad ->
            val error = assertThrows<InvalidGateInputException> { GateInputs.selector(bad) }
            assertThat(error).hasMessageContaining("selector")
        }
    }

    @Test
    fun `AC-4 qaCommit accepts only 7 to 40 lowercase hex characters`() {
        listOf("abc1234", "0123456789abcdef0123456789abcdef01234567").forEach {
            assertThat(GateInputs.qaCommit(it)).isEqualTo(it)
        }
        listOf("", "abc12", "ABCDEF1", "g123456", "a".repeat(41), "--output=x", "abc1234 ").forEach { bad ->
            val error = assertThrows<InvalidGateInputException> { GateInputs.qaCommit(bad) }
            assertThat(error).hasMessageContaining("qaCommit")
        }
    }

    @Test
    fun `AC-4 file refuses a leading dash, parent traversal and control characters`() {
        assertThat(GateInputs.file("core/src/test/FooTest.kt")).isEqualTo("core/src/test/FooTest.kt")
        listOf("-x", "--output=x", "../x", "a/../b", "a\u0000b", "a\nb", "a\tb").forEach { bad ->
            val error = assertThrows<InvalidGateInputException> { GateInputs.file(bad) }
            assertThat(error).hasMessageContaining("files")
        }
    }

    @Test
    fun `AC-5 an invalid regex names the offending field`() {
        assertThat(AcceptancePatterns.of("ok", "ok", "ok").ran.matches("ok")).isTrue()
        mapOf(
            "ran" to { AcceptancePatterns.of("(") },
            "assertions" to { AcceptancePatterns.of("ok", assertions = "[") },
            "setupErrors" to { AcceptancePatterns.of("ok", setupErrors = "*a") },
        ).forEach { (field, build) ->
            val error = assertThrows<InvalidAcceptancePatternException> { build() }
            assertThat(error).hasMessageContaining(field)
        }
    }

    @Test
    fun `AC-5 an output line above 8 KiB is truncated before the patterns are matched`() {
        val patterns = AcceptancePatterns(
            ran = Regex("tests completed"),
            assertions = Regex("ASSERT_FAIL"),
        )
        val hidden = "x".repeat(9 * 1024) + " ASSERT_FAIL"
        val visible = "ASSERT_FAIL at the start " + "x".repeat(9 * 1024)

        val beyondLimit = AcceptanceRules.red(1, sequenceOf("1 tests completed", hidden), patterns)
        val withinLimit = AcceptanceRules.red(1, sequenceOf("1 tests completed", visible), patterns)

        assertThat(beyondLimit).isEqualTo(AcceptanceVerdict.Rejected(AcceptanceReason.NO_ASSERTION_FAILURE))
        assertThat(withinLimit).isEqualTo(AcceptanceVerdict.Ok)
    }
}
