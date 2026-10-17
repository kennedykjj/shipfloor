package shipfloor.daemon

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import shipfloor.core.AssistantState

/** Card references the phrases may cite: `#<issue number>` or the first 8 hex characters of a card UUID. */
private val CARD_LABEL = Regex("^#[0-9]{1,10}$|^[0-9a-f]{8}$")

/** Numbers and card labels: the tokens a rewrite must keep exactly. */
private val TOKEN = Regex("\\b[0-9a-f]{8}\\b|[0-9]+")

private const val MAX_REWRITE_LENGTH = 280

/** Voice calls run on daemon threads, so an abandoned call never keeps the process alive. */
private val voiceExecutor: ExecutorService = Executors.newCachedThreadPool { task ->
    Thread(task, "assistant-voice").apply { isDaemon = true }
}

/**
 * What the assistant may cite. Card ids are UUIDs, so each item is a label built by the caller:
 * `#<issue number>` or the first 8 hex characters of the id. Never a title or issue text.
 */
data class AssistantFacts(
    val waitingOwner: List<String>,
    val blocked: List<String>,
    val working: List<String>,
    val recentFailures: Int,
) {
    init {
        (waitingOwner + blocked + working).forEach { label ->
            require(CARD_LABEL.matches(label)) { "card label is not #<number> or 8 hex characters" }
        }
        require(recentFailures >= 0) { "recentFailures must not be negative" }
    }
}

/** One deterministic phrase per assistant state, built from the facts only. */
object AssistantPhrases {
    fun assemble(state: AssistantState, facts: AssistantFacts): String = when (state) {
        AssistantState.IDLE -> "Nada pedindo a sua atenção agora."
        AssistantState.WORKING -> "Em andamento: ${listOrNone(facts.working)}."
        AssistantState.WAITING_OWNER -> "Esperando você em: ${listOrNone(facts.waitingOwner)}."
        AssistantState.BLOCKED -> "Bloqueado: ${listOrNone(facts.blocked)}."
        AssistantState.NIGHT -> "Hora de descansar. Nada novo até amanhã."
        AssistantState.TIRED -> "${facts.recentFailures} falhas seguidas. Vale uma pausa."
    }

    private fun listOrNone(labels: List<String>): String =
        if (labels.isEmpty()) "nenhum card" else labels.joinToString(", ")
}

/** Port of the voice model: it rewrites an assembled phrase. */
fun interface AssistantVoice {
    fun rewrite(phrase: String): String
}

enum class VoiceMode { OFF, ON }

/**
 * Says the assembled phrase, or a voice rewrite of it when the voice is on and the rewrite passes
 * [acceptable]. Any other outcome (no voice, timeout, failure, rejected rewrite) gives the assembled phrase.
 */
class AssistantSpeaker(
    private val voice: AssistantVoice?,
    private val voiceEnabled: VoiceMode,
    private val deadline: Duration = Duration.ofSeconds(3),
    private val log: DaemonLog = StderrDaemonLog,
) {
    fun say(state: AssistantState, facts: AssistantFacts): String {
        val phrase = AssistantPhrases.assemble(state, facts)
        if (voiceEnabled == VoiceMode.OFF || voice == null) return phrase
        return try {
            val future = voiceExecutor.submit(Callable { voice.rewrite(phrase) })
            val rewrite = try {
                future.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                future.cancel(true)
                throw timeout
            }
            if (acceptable(phrase, rewrite)) {
                rewrite
            } else {
                fallback(state, "rewrite_rejected")
                phrase
            }
        } catch (timeout: TimeoutException) {
            fallback(state, "timeout")
            phrase
        } catch (failure: ExecutionException) {
            fallback(state, "voice_failed")
            phrase
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            fallback(state, "interrupted")
            phrase
        }
    }

    private fun fallback(state: AssistantState, reason: String) {
        log.log("assistant.voice.fallback", mapOf("state" to state.name, "reason" to reason))
    }

    private fun acceptable(phrase: String, rewrite: String): Boolean =
        rewrite.isNotBlank() &&
            rewrite.length <= MAX_REWRITE_LENGTH &&
            rewrite.none { it == '\n' || it == '\r' } &&
            !rewrite.contains("://") &&
            rewrite.none { it == '`' || it == '[' || it == ']' || it == '<' } &&
            tokens(rewrite) == tokens(phrase)

    private fun tokens(text: String): Set<String> = TOKEN.findAll(text).map { it.value }.toSet()
}
