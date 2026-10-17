package shipfloor.core

import java.util.regex.PatternSyntaxException

/** Extra per-layer patterns; they add to the default patterns. */
data class AcceptancePatterns(
    val ran: Regex,
    val assertions: Regex? = null,
    val setupErrors: Regex? = null,
) {
    companion object {
        fun of(ran: String, assertions: String? = null, setupErrors: String? = null): AcceptancePatterns =
            AcceptancePatterns(
                ran = compile("ran", ran),
                assertions = assertions?.let { compile("assertions", it) },
                setupErrors = setupErrors?.let { compile("setupErrors", it) },
            )

        private fun compile(field: String, pattern: String): Regex =
            try {
                Regex(pattern)
            } catch (e: PatternSyntaxException) {
                throw InvalidAcceptancePatternException("invalid regex in field $field: ${e.description}")
            }
    }
}

sealed interface AcceptanceVerdict {
    data object Ok : AcceptanceVerdict

    data class Rejected(val reason: AcceptanceReason) : AcceptanceVerdict
}

enum class AcceptanceReason {
    TESTS_PASSED_BEFORE_IMPL,
    SETUP_ERROR,
    NO_ASSERTION_FAILURE,
    TESTS_FAILED,
    NO_TEST_RAN,
    ASSERTION_EDITED,
}

object AcceptanceRules {
    const val MAX_LINE_LENGTH = 8 * 1024

    private val DEFAULT_SETUP_ERRORS = Regex(
        "compilation (error|failure)|unresolved reference|cannot find module|syntaxerror|error TS[0-9]+|" +
            "could not resolve|transform failed|is not a function|is not defined|ERR_CONNECTION|ECONNREFUSED|" +
            "failed to load applicationcontext|beancreationexception|Errors: [1-9]|^ERROR: ",
        RegexOption.IGNORE_CASE,
    )
    private val DEFAULT_ASSERTIONS = Regex(
        "AssertionFailedError|AssertionError|expected: ?<.*> but was|" +
            "expect\\(.*\\)\\.[A-Za-z]+\\(.*\\) failed|^\\s*Received:",
    )
    private val DISABLERS = Regex("@Disabled|@Ignore|@Test\\(enabled|assumeTrue|assumeFalse|Assumptions\\.")
    private val COMMENT_ONLY = Regex("^[+-][ \\t]*(//|/\\*|\\*|#|--|<!--)")

    fun red(exitCode: Int?, output: Sequence<String>, patterns: AcceptancePatterns): AcceptanceVerdict {
        if (exitCode == 0) return AcceptanceVerdict.Rejected(AcceptanceReason.TESTS_PASSED_BEFORE_IMPL)
        val setup = patterns.setupErrors?.let { Regex(it.pattern, it.options + RegexOption.IGNORE_CASE) }
        var setupError = false
        var assertion = false
        output.map(::truncate).forEach { line ->
            if (DEFAULT_SETUP_ERRORS.containsMatchIn(line) || setup?.containsMatchIn(line) == true) setupError = true
            if (DEFAULT_ASSERTIONS.containsMatchIn(line) || patterns.assertions?.containsMatchIn(line) == true) {
                assertion = true
            }
        }
        return when {
            setupError -> AcceptanceVerdict.Rejected(AcceptanceReason.SETUP_ERROR)
            !assertion -> AcceptanceVerdict.Rejected(AcceptanceReason.NO_ASSERTION_FAILURE)
            else -> AcceptanceVerdict.Ok
        }
    }

    fun green(exitCode: Int?, output: Sequence<String>, patterns: AcceptancePatterns): AcceptanceVerdict {
        if (exitCode != 0) return AcceptanceVerdict.Rejected(AcceptanceReason.TESTS_FAILED)
        val ran = output.map(::truncate).any { patterns.ran.containsMatchIn(it) }
        return if (ran) AcceptanceVerdict.Ok else AcceptanceVerdict.Rejected(AcceptanceReason.NO_TEST_RAN)
    }

    /**
     * The diff lines that weaken the QA's tests: an assertion line removed or replaced ([keep] on a `-` line) and a
     * disabling marker added. Assertions that only get added (by a QA repair, or by a merge of the base) do not count.
     */
    fun assertionEdit(diff: Sequence<String>, keep: Regex): List<String> =
        diff.map(::truncate)
            .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++ ") && !it.startsWith("--- ") }
            .filterNot { COMMENT_ONLY.containsMatchIn(it) }
            .filter { line -> if (line.startsWith("-")) keep.containsMatchIn(line) else DISABLERS.containsMatchIn(line) }
            .toList()

    private fun truncate(line: String): String = if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) else line
}

object GateInputs {
    private val SELECTOR = Regex("[A-Za-z0-9_.*#$-]+")
    private val QA_COMMIT = Regex("[0-9a-f]{7,40}")

    fun selector(value: String): String {
        if (!SELECTOR.matches(value) || value.startsWith("-")) {
            throw InvalidGateInputException("selector is not allowed: ${describe(value)}")
        }
        return value
    }

    fun qaCommit(value: String): String {
        if (!QA_COMMIT.matches(value)) {
            throw InvalidGateInputException("qaCommit must be 7 to 40 lowercase hex characters: ${describe(value)}")
        }
        return value
    }

    fun file(value: String): String {
        val bad = value.isEmpty() || value.startsWith("-") || value.split('/').contains("..") ||
            value.any { it.isISOControl() }
        if (bad) throw InvalidGateInputException("files entry is not allowed: ${describe(value)}")
        return value
    }

    private fun describe(value: String): String = "length=${value.length}"
}

class InvalidAcceptancePatternException(message: String) : RuntimeException(message)

class InvalidGateInputException(message: String) : RuntimeException(message)
