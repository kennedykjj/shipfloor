package shipfloor.daemon

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import shipfloor.core.AssistantState

class AssistantPhrasesTest {

    private val facts = AssistantFacts(
        waitingOwner = listOf("#12", "0a1b2c3d"),
        blocked = listOf("#7"),
        working = listOf("deadbeef"),
        recentFailures = 3,
    )

    private fun speakerWith(voice: AssistantVoice?, mode: VoiceMode = VoiceMode.ON) =
        AssistantSpeaker(voice, mode)

    private fun assembled(state: AssistantState = AssistantState.WAITING_OWNER) =
        AssistantPhrases.assemble(state, facts)

    @Test
    fun `AC-1 assembles a deterministic non-blank phrase per state from the facts`() {
        AssistantState.values().forEach { state ->
            val first = AssistantPhrases.assemble(state, facts)
            assertThat(first).isNotBlank()
            assertThat(AssistantPhrases.assemble(state, facts)).isEqualTo(first)
        }
        assertThat(assembled(AssistantState.WAITING_OWNER)).contains("#12", "0a1b2c3d")
        assertThat(assembled(AssistantState.BLOCKED)).contains("#7")
        assertThat(assembled(AssistantState.WORKING)).contains("deadbeef")
        assertThat(assembled(AssistantState.TIRED)).contains("3")
        assertThat(assembled(AssistantState.WAITING_OWNER))
            .isNotEqualTo(assembled(AssistantState.BLOCKED))
    }

    @Test
    fun `AC-2 voice receives only the assembled phrase and its valid rewrite is used`() {
        val received = mutableListOf<String>()
        val voice = AssistantVoice { phrase ->
            received += phrase
            "Olha so: $phrase"
        }

        val said = speakerWith(voice).say(AssistantState.WAITING_OWNER, facts)

        assertThat(received).containsExactly(assembled())
        assertThat(said).isEqualTo("Olha so: ${assembled()}")
    }

    @Test
    fun `AC-2 voice failure falls back to the assembled phrase`() {
        val voice = AssistantVoice { throw IllegalStateException("model down") }

        assertThat(speakerWith(voice).say(AssistantState.WAITING_OWNER, facts)).isEqualTo(assembled())
    }

    @Test
    fun `AC-3 voice off never calls the voice and returns the assembled phrase`() {
        val calls = AtomicInteger()
        val voice = AssistantVoice { calls.incrementAndGet(); "outra frase" }

        val said = speakerWith(voice, VoiceMode.OFF).say(AssistantState.WAITING_OWNER, facts)

        assertThat(said).isEqualTo(assembled())
        assertThat(calls.get()).isEqualTo(0)
        assertThat(speakerWith(null, VoiceMode.ON).say(AssistantState.WAITING_OWNER, facts))
            .isEqualTo(assembled())
    }

    @Test
    fun `AC-4 rewrite that adds or drops an id or number falls back`() {
        val phrase = assembled()
        val rewrites = listOf(
            "$phrase 99",
            "$phrase #13",
            "$phrase deadbeef",
            phrase.replace("#12", ""),
            phrase.replace("0a1b2c3d", ""),
        )
        rewrites.forEach { rewrite ->
            val said = speakerWith(AssistantVoice { rewrite }).say(AssistantState.WAITING_OWNER, facts)
            assertThat(said).isEqualTo(phrase)
        }
    }

    @Test
    fun `AC-4 rewrite with extra length, line break, url or markup falls back`() {
        val phrase = assembled()
        val rewrites = listOf(
            phrase + " " + "a".repeat(300),
            "$phrase\nmais uma linha",
            "$phrase https://example.com",
            "$phrase ftp://example.com",
            "$phrase `code`",
            "$phrase [link]",
            "$phrase <b>",
        )
        rewrites.forEach { rewrite ->
            val said = speakerWith(AssistantVoice { rewrite }).say(AssistantState.WAITING_OWNER, facts)
            assertThat(said).isEqualTo(phrase)
        }
    }

    @Test
    fun `AC-4 voice that exceeds the deadline falls back to the assembled phrase`() {
        val never = CountDownLatch(1)
        val voice = AssistantVoice {
            never.await()
            "nunca chega"
        }
        // third argument is the deadline, shortened so the test does not wait 3 seconds
        val speaker = AssistantSpeaker(voice, VoiceMode.ON, Duration.ofMillis(100))

        try {
            assertThat(speaker.say(AssistantState.WAITING_OWNER, facts)).isEqualTo(assembled())
        } finally {
            never.countDown()
        }
    }

    @Test
    fun `AC-5 facts accept only external number labels or 8 hex characters never a title`() {
        val ok = AssistantFacts(listOf("#12", "0a1b2c3d"), emptyList(), emptyList(), 0)
        assertThat(ok.waitingOwner).containsExactly("#12", "0a1b2c3d")

        listOf("Corrigir o login", "ABCDEF01", "1234567", "#", "#abc", "#12345678901", "0a1b2c3d4").forEach { bad ->
            assertThrows<RuntimeException> {
                AssistantFacts(emptyList(), listOf(bad), emptyList(), 0)
            }
        }
    }

    @Test
    fun `AC-5 phrase cites cards by their label only`() {
        val phrase = AssistantPhrases.assemble(
            AssistantState.BLOCKED,
            AssistantFacts(emptyList(), listOf("#7", "cafe0123"), emptyList(), 0),
        )

        assertThat(phrase).contains("#7", "cafe0123")
    }
}
