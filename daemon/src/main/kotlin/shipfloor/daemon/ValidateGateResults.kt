package shipfloor.daemon

/** Rounds the validate pipeline runs before it blocks the card: the shared [DEFAULT_MAX_ROUNDS], not a second value. */
const val DEFAULT_VALIDATE_MAX_ROUNDS = DEFAULT_MAX_ROUNDS

/** The gates' word on a card: the single place that turns gate results into the state and the failed names. */
class ValidateGateResults(val results: List<GateResult>) {
    val state: GatesState get() = if (results.any { !it.passed }) GatesState.RED else GatesState.GREEN

    val failedNames: List<String> get() = results.filter { !it.passed }.map { it.name }

    operator fun plus(other: ValidateGateResults) = ValidateGateResults(results + other.results)

    companion object {
        fun fromExecutor(name: String, verdict: GateVerdict) = ValidateGateResults(
            listOf(
                when (verdict) {
                    GateVerdict.Pass -> GateResult(name, true, null)
                    is GateVerdict.Fail -> GateResult(name, false, verdict.reason)
                },
            ),
        )

        fun fromScope(verdict: ScopeVerdict) = ValidateGateResults(
            listOf(
                when (verdict) {
                    ScopeVerdict.Pass -> GateResult("scope", true, null)
                    is ScopeVerdict.Violations ->
                        GateResult("scope", false, "forbidden=${verdict.forbidden.size} outside=${verdict.outsideLayers.size}")
                },
            ),
        )

        fun fromSpec(check: SpecCheck) = ValidateGateResults(
            SpecGate.entries.map { gate ->
                val findings = check.findings.filter { it.gate == gate }
                GateResult(
                    gate.name.lowercase().replace('_', '-'),
                    findings.isEmpty(),
                    findings.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.message },
                )
            },
        )
    }
}
