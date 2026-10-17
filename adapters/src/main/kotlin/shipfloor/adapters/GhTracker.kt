package shipfloor.adapters

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shipfloor.ports.CloseResult
import shipfloor.ports.ExternalText
import shipfloor.ports.Tracker
import shipfloor.ports.TrackerException
import shipfloor.ports.TrackerItem
import shipfloor.ports.TrackerItemState
import shipfloor.ports.UnknownTrackerItemException

/**
 * [Tracker] on top of the GitHub CLI, for issues of [repo]. The token lives in `gh`'s own environment and is never read here.
 * Everything that comes from an issue leaves as [ExternalText]. Failures carry the operation and the issue number only,
 * never `gh` output or issue text.
 */
class GhTracker(
    private val repo: String,
    private val gh: String = "gh",
    private val workDir: Path,
    private val tmpDir: Path,
    private val timeout: Duration,
    private val runner: ProcessRunner = ProcessRunnerImpl(),
) : Tracker {

    override fun fetch(id: String): TrackerItem? {
        val subject = "id=$id"
        requireValid("fetch", subject, validRepo() && ISSUE_ID.matches(id))
        val out = try {
            call("fetch", subject, "issue", "view", id, "--repo", repo, "--json", FIELDS)
        } catch (e: UnknownTrackerItemException) {
            return null
        }
        return parse("fetch", subject) { toItem(JSON.parseToJsonElement(out).jsonObject) }
    }

    override fun search(query: String): List<TrackerItem> {
        val subject = "query"
        requireValid("search", subject, validRepo() && validQuery(query))
        // A blank query lists the open issues (what the V0 board import asks for); any other query searches all states.
        val scope = if (query.isBlank()) listOf("--state", "open") else listOf("--state", "all", "--search", query)
        val out = call(
            "search",
            subject,
            "issue", "list", "--repo", repo, *scope.toTypedArray(),
            "--limit", SEARCH_LIMIT.toString(), "--json", FIELDS,
        )
        val items = parse("search", subject) { JSON.parseToJsonElement(out).jsonArray.map { toItem(it.jsonObject) } }
        if (items.size >= SEARCH_LIMIT) throw TrackerException("search falhou ($subject): busca truncada no limite de $SEARCH_LIMIT")
        return items
    }

    override fun writeMilestone(id: String, milestone: String) {
        val subject = "id=$id"
        requireValid("writeMilestone", subject, validRepo() && ISSUE_ID.matches(id))
        val bodyFile = writeTemp("writeMilestone", subject, milestone)
        try {
            call("writeMilestone", subject, "issue", "comment", id, "--repo", repo, "--body-file", bodyFile.toString())
        } finally {
            Files.deleteIfExists(bodyFile)
        }
    }

    override fun close(id: String, comment: String): CloseResult {
        val subject = "id=$id"
        requireValid("close", subject, validRepo() && ISSUE_ID.matches(id))
        val view = call("close", subject, "issue", "view", id, "--repo", repo, "--json", "state")
        val state = parse("close", subject) { JSON.parseToJsonElement(view).jsonObject.text("state") }
        if (state == "CLOSED") return CloseResult.AlreadyClosed
        call("close", subject, "issue", "close", id, "--repo", repo, "--comment", comment)
        return CloseResult.Closed
    }

    private fun toItem(obj: JsonObject): TrackerItem {
        val number = requireNotNull(obj["number"]?.jsonPrimitive?.intOrNull) { "number ausente" }
        return TrackerItem.of(
            id = number.toString(),
            title = ExternalText(obj.text("title").orEmpty()),
            body = ExternalText(obj.text("body").orEmpty()),
            labels = obj["labels"]?.jsonArray.orEmpty().map { ExternalText(it.jsonObject.text("name").orEmpty()) },
            url = obj.text("url").orEmpty(),
            state = if (obj.text("state") == "CLOSED") TrackerItemState.CLOSED else TrackerItemState.OPEN,
        )
    }

    /**
     * Runs `gh` with stdout in a temp file under [tmpDir]; returns what it printed. A missing issue is an
     * [UnknownTrackerItemException] (the caller of [fetch] turns it into null).
     */
    private fun call(operation: String, subject: String, vararg args: String): String {
        val outFile = writeTemp(operation, subject, "")
        try {
            val outcome = try {
                runner.run(listOf(gh) + args, workDir, outFile, timeout, mapOf("GH_PROMPT_DISABLED" to "1"))
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
                    throw UnknownTrackerItemException("$operation: issue desconhecida ($subject)")
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
            Files.writeString(Files.createTempFile(tmpDir, "gh-", ".tmp"), content)
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

    private fun validRepo(): Boolean = REPO.matches(repo)

    private fun validQuery(query: String): Boolean =
        !query.startsWith("-") && query.none { it.isISOControl() } && !SCOPE_QUALIFIER.containsMatchIn(query)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        val ISSUE_ID = Regex("^[1-9][0-9]{0,9}$")
        val SCOPE_QUALIFIER = Regex("\\b(repo|org|user):", RegexOption.IGNORE_CASE)
        const val FIELDS = "number,title,body,labels,url,state"
        const val SEARCH_LIMIT = 100
        const val MAX_OUTPUT_BYTES = 8L * 1024 * 1024
        const val NOT_FOUND_MARKER = "Could not resolve"
    }
}
