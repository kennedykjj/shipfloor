package shipfloor.adapters

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import shipfloor.ports.CloseResult
import shipfloor.ports.ExternalText
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.ports.UnknownTrackerItemException

/**
 * [Tracker] on top of the Atlassian CLI (`acli`), for tickets of the Jira [project] on [site]: every search is scoped to the
 * project and every ticket key must belong to it, so a blank query never lists the whole site. The credential lives in `acli`'s own
 * config and is never read here; the child gets a filtered environment. Everything that comes from a ticket leaves as
 * [ExternalText]. Failures carry the operation and the ticket key only, never `acli` output or ticket text.
 */
class AcliTracker(
    private val site: String,
    private val project: String,
    private val acli: String = "acli",
    private val workDir: Path,
    private val tmpDir: Path,
    private val timeout: Duration,
    private val runner: ProcessRunner = ProcessRunnerImpl(),
) : Tracker {

    override fun fetch(id: String): TrackerItem? {
        val subject = "id=$id"
        requireValid("fetch", subject, validSite() && validProject() && ownKey(id))
        val out = try {
            call("fetch", subject, "jira", "workitem", "view", id, "--fields", FIELDS, "--json")
        } catch (e: UnknownTrackerItemException) {
            return null
        }
        return parse("fetch", subject) { toItem(JSON.parseToJsonElement(out).jsonObject) }
    }

    override fun search(query: String): List<TrackerItem> {
        val subject = "query"
        requireValid("search", subject, validSite() && validProject() && validQuery(query))
        // A blank query lists the open tickets (what the board import asks for); any other query searches all states.
        val scope = "project = \"$project\" AND "
        val jql = scope + if (query.isBlank()) "statusCategory != Done" else "text ~ \"${escape(query)}\""
        val out = call(
            "search",
            subject,
            "jira", "workitem", "search", "--jql", jql, "--fields", FIELDS,
            "--limit", SEARCH_LIMIT.toString(), "--json",
        )
        val items = parse("search", subject) { JSON.parseToJsonElement(out).jsonArray.map { toItem(it.jsonObject) } }
        if (items.size >= SEARCH_LIMIT) throw TrackerException("search falhou ($subject): busca truncada no limite de $SEARCH_LIMIT")
        return items
    }

    override fun writeMilestone(id: String, milestone: String) {
        val subject = "id=$id"
        requireValid("writeMilestone", subject, validSite() && validProject() && ownKey(id))
        comment("writeMilestone", subject, id, milestone)
    }

    override fun close(id: String, comment: String): CloseResult {
        val subject = "id=$id"
        requireValid("close", subject, validSite() && validProject() && ownKey(id))
        val view = call("close", subject, "jira", "workitem", "view", id, "--fields", FIELDS, "--json")
        val state = parse("close", subject) { toItem(JSON.parseToJsonElement(view).jsonObject).state }
        if (state == TrackerItemState.CLOSED) return CloseResult.AlreadyClosed
        comment("close", subject, id, comment)
        val out = call(
            "close",
            subject,
            "jira", "workitem", "transition", "--key", id, "--status", DONE_STATUS, "--yes", "--json",
        )
        val reached = parse("close", subject) { (JSON.parseToJsonElement(out).jsonObject["status"] as? JsonPrimitive)?.contentOrNull }
        if (reached != null && reached != DONE_STATUS) throw TrackerException("close falhou ($subject): destino inesperado")
        return CloseResult.Closed
    }

    private fun comment(operation: String, subject: String, id: String, text: String) {
        val bodyFile = writeTemp(operation, subject, text)
        try {
            call(operation, subject, "jira", "workitem", "comment", "create", "--key", id, "--body-file", bodyFile.toString())
        } finally {
            Files.deleteIfExists(bodyFile)
        }
    }

    private fun toItem(obj: JsonObject): TrackerItem {
        val key = requireNotNull((obj["key"] as? JsonPrimitive)?.contentOrNull) { "key ausente" }
        require(ISSUE_KEY.matches(key)) { "key inválida" }
        val fields = obj["fields"]?.jsonObject ?: JsonObject(emptyMap())
        val category = ((fields["status"] as? JsonObject)?.get("statusCategory") as? JsonObject)?.get("key")
        val labels = (fields["labels"] as? JsonArray).orEmpty().map { ExternalText((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
        return TrackerItem.of(
            id = key,
            title = ExternalText((fields["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            body = ExternalText(plainText(fields["description"])),
            labels = labels,
            url = "https://$site/browse/$key",
            state = if ((category as? JsonPrimitive)?.contentOrNull == "done") TrackerItemState.CLOSED else TrackerItemState.OPEN,
        )
    }

    /** A description is a plain string or an ADF document; either way the result is plain text. */
    private fun plainText(element: JsonElement?): String =
        when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> StringBuilder().also { appendAdf(element, it) }.toString().trimEnd()
        }

    private fun appendAdf(node: JsonElement, out: StringBuilder) {
        when (node) {
            is JsonArray -> node.forEach { appendAdf(it, out) }
            is JsonObject -> {
                val type = (node["type"] as? JsonPrimitive)?.contentOrNull
                when (type) {
                    "text" -> out.append((node["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    "hardBreak" -> out.append('\n')
                    else -> node["content"]?.let { appendAdf(it, out) }
                }
                if (type in BLOCK_TYPES) out.append('\n')
            }
            else -> Unit
        }
    }

    /**
     * Runs `acli` with stdout in a temp file under [tmpDir]; returns what it printed. A missing ticket is an
     * [UnknownTrackerItemException] (the caller of [fetch] turns it into null).
     */
    private fun call(operation: String, subject: String, vararg args: String): String {
        val outFile = writeTemp(operation, subject, "")
        try {
            val outcome = try {
                runner.run(listOf(acli) + args, workDir, outFile, timeout, emptyMap(), EnvMode.FILTERED)
            } catch (e: IOException) {
                throw TrackerException("$operation falhou ($subject)")
            }
            val size = try {
                Files.size(outFile)
            } catch (e: IOException) {
                throw TrackerException("$operation falhou ($subject)")
            }
            if (size > MAX_OUTPUT_BYTES) throw TrackerException("$operation falhou ($subject): resposta acima de 8 MiB")
            val text = try {
                Files.readString(outFile)
            } catch (e: IOException) {
                throw TrackerException("$operation falhou ($subject): resposta inválida")
            }
            if (outcome != ProcessOutcome.Exited(0)) {
                if (text.contains(NOT_FOUND_MARKER)) {
                    throw UnknownTrackerItemException("$operation: ticket desconhecido ($subject)")
                }
                throw TrackerException("$operation falhou ($subject)")
            }
            return text
        } finally {
            Files.deleteIfExists(outFile)
        }
    }

    private fun writeTemp(operation: String, subject: String, content: String): Path =
        try {
            Files.writeString(Files.createTempFile(tmpDir, "acli-", ".tmp"), content)
        } catch (e: IOException) {
            throw TrackerException("$operation falhou ($subject)")
        }

    private fun <T> parse(operation: String, subject: String, block: () -> T): T =
        try {
            block()
        } catch (e: SerializationException) {
            throw TrackerException("$operation falhou ($subject): resposta inválida")
        } catch (e: IllegalArgumentException) {
            throw TrackerException("$operation falhou ($subject): resposta inválida")
        } catch (e: IllegalStateException) {
            throw TrackerException("$operation falhou ($subject): resposta inválida")
        }

    private fun requireValid(operation: String, subject: String, valid: Boolean) {
        if (!valid) throw TrackerException("$operation recusou entrada inválida ($subject)")
    }

    private fun validSite(): Boolean = SITE.matches(site)

    private fun validProject(): Boolean = PROJECT.matches(project)

    private fun ownKey(id: String): Boolean = ISSUE_KEY.matches(id) && id.startsWith("$project-")

    private fun validQuery(query: String): Boolean = !query.startsWith("-") && query.none { it.isISOControl() }

    private fun escape(query: String): String = query.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val ISSUE_KEY = Regex("^[A-Z][A-Z0-9_]{1,19}-[0-9]{1,9}$")
        val SITE = Regex("^[a-z0-9][a-z0-9.-]{0,62}$")
        val PROJECT = Regex("^[A-Z][A-Z0-9_]{1,19}$")
        val BLOCK_TYPES = setOf("paragraph", "heading", "codeBlock", "blockquote", "listItem", "rule")
        const val FIELDS = "key,summary,description,labels,status"
        const val DONE_STATUS = "Done"
        const val SEARCH_LIMIT = 100
        const val MAX_OUTPUT_BYTES = 8L * 1024 * 1024
        const val NOT_FOUND_MARKER = "does not exist or you do not have permission"
    }
}
