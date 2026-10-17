package shipfloor.ports

import java.nio.file.Path

sealed interface SessionScript {
    data object Succeed : SessionScript

    data class Fail(val exitCode: Int) : SessionScript

    data object Hang : SessionScript
}

class ScriptExhaustedException(callIndex: Int) :
    RuntimeException("FakeRuntime script exhausted at call index $callIndex")

/** Scripted runtime for tests: no IO, no clock, no network; a hang returns at once. */
class FakeRuntime(private val script: List<SessionScript>) : Runtime {
    private val requests = mutableListOf<SessionRequest>()

    val launched: List<SessionRequest> get() = requests.toList()

    override fun launch(request: SessionRequest): SessionResult {
        val index = requests.size
        val step = script.getOrNull(index) ?: throw ScriptExhaustedException(index)
        requests += request
        val transcript = Path.of("fake-transcripts", "${request.stage}-$index.jsonl")
        return when (step) {
            SessionScript.Succeed -> SessionResult.Exited(0, transcript)
            is SessionScript.Fail -> SessionResult.Exited(step.exitCode, transcript)
            SessionScript.Hang -> SessionResult.Hung(transcript)
        }
    }
}
