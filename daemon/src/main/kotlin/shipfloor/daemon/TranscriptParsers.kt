package shipfloor.daemon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class TranscriptUnreadableException(message: String) : RuntimeException(message)

/**
 * Reads token usage from agent transcripts, line by line. Only numbers and model names are read:
 * no transcript text and no reported cost ever leaves this class.
 */
object TranscriptParsers {
    const val MAX_LINE_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** Throws [TranscriptUnreadableException] when the file is missing, unreadable or too large. */
    fun parse(runtime: String, transcript: Path, fallbackModel: String): Map<String, TokenCounts> {
        val size = try {
            Files.size(transcript)
        } catch (e: IOException) {
            throw TranscriptUnreadableException("transcript missing or unreadable")
        }
        if (size > MAX_FILE_BYTES) throw TranscriptUnreadableException("transcript larger than 64 MiB")
        val usage = LinkedHashMap<String, TokenCounts>()
        val seenMessages = HashSet<String>()
        try {
            forEachLine(transcript) { line ->
                val obj = parseObject(line) ?: return@forEachLine
                when (runtime) {
                    "claude" -> addClaude(obj, usage, seenMessages, fallbackModel)
                    "codex" -> addCodex(obj, usage, fallbackModel)
                }
            }
        } catch (e: IOException) {
            throw TranscriptUnreadableException("transcript missing or unreadable")
        }
        return usage
    }

    private fun addClaude(
        obj: JsonObject,
        usage: MutableMap<String, TokenCounts>,
        seen: MutableSet<String>,
        fallbackModel: String,
    ) {
        if (text(obj["type"]) != "assistant") return
        val message = obj["message"] as? JsonObject ?: return
        val raw = message["usage"] as? JsonObject ?: return
        val id = text(message["id"])
        if (id != null && !seen.add(id)) return
        val creation = raw["cache_creation"] as? JsonObject
        val write5m = number(creation?.get("ephemeral_5m_input_tokens"))
        val write1h = number(creation?.get("ephemeral_1h_input_tokens"))
        val counts = TokenCounts(
            input = number(raw["input_tokens"]),
            output = number(raw["output_tokens"]),
            cacheRead = number(raw["cache_read_input_tokens"]),
            cacheWrite5m = if (creation == null) number(raw["cache_creation_input_tokens"]) else write5m,
            cacheWrite1h = write1h,
        )
        val model = text(message["model"]) ?: fallbackModel
        usage.merge(model, counts) { a, b -> a + b }
    }

    private fun addCodex(obj: JsonObject, usage: MutableMap<String, TokenCounts>, fallbackModel: String) {
        if (text(obj["type"]) != "turn.completed") return
        val raw = obj["usage"] as? JsonObject ?: return
        val input = number(raw["input_tokens"])
        val cached = number(raw["cached_input_tokens"])
        val counts = TokenCounts(
            input = (input - cached).coerceAtLeast(0),
            output = number(raw["output_tokens"]),
            cacheRead = cached,
        )
        usage.merge(fallbackModel, counts) { a, b -> a + b }
    }

    private fun parseObject(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun number(element: JsonElement?): Long =
        ((element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L).coerceAtLeast(0)

    /** Streams lines; a line above [MAX_LINE_BYTES] is skipped without being held in memory. */
    private fun forEachLine(file: Path, action: (String) -> Unit) {
        Files.newInputStream(file).buffered().use { input ->
            val buffer = ByteArrayOutputStream()
            var overflow = false
            fun flush() {
                if (!overflow && buffer.size() > 0) action(buffer.toString(StandardCharsets.UTF_8))
                buffer.reset()
                overflow = false
            }
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                if (byte == '\n'.code) {
                    flush()
                } else if (!overflow) {
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        overflow = true
                        buffer.reset()
                    } else {
                        buffer.write(byte)
                    }
                }
            }
            flush()
        }
    }
}
