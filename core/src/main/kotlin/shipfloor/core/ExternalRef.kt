package shipfloor.core

import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class TextHash(val value: String) {
    companion object {
        fun of(text: String): TextHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return TextHash(digest.joinToString("") { "%02x".format(it) })
        }
    }
}

data class ExternalRef(
    val provider: String,
    val externalId: String,
    val url: String,
    val importedAt: Instant,
    val textHash: TextHash,
)

class DuplicateExternalRefException(val provider: String, val externalId: String) :
    RuntimeException("External reference already on card (provider: $provider, externalId: $externalId)")
