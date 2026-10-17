package shipfloor.adapters

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import shipfloor.ports.ChecksState
import shipfloor.ports.FailedCheck
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.MergeResult
import shipfloor.ports.MergedPullRequest
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.PullRequestState
import shipfloor.ports.PullRequestSummary
import shipfloor.ports.UnknownPullRequestException

/**
 * [Forge] on top of the GitHub CLI. The token lives in `gh`'s own environment and is never read here.
 * Failures carry the operation and the PR number or branch only, never `gh` output.
 */
class GhForge(
    private val repo: String,
    private val gh: String = "gh",
    private val workDir: Path,
    private val tmpDir: Path,
    private val timeout: Duration,
    private val runner: ProcessRunner = ProcessRunnerImpl(),
) : Forge {

    override fun findOpenPullRequest(branch: String): PullRequestRef? {
        val subject = "branch=$branch"
        requireValid("findOpenPullRequest", validRepo() && validBranch(branch))
        val out = call(
            "findOpenPullRequest",
            subject,
            "pr", "list", "--repo", repo, "--head", branch, "--state", "open",
            "--json", "number,headRefName,headRefOid",
        )
        return parse("findOpenPullRequest", subject) {
            JSON.parseToJsonElement(out).jsonArray.firstOrNull()?.let { toRef(it.jsonObject) }
        }
    }

    override fun findMergedPullRequest(branch: String): MergedPullRequest? {
        val subject = "branch=$branch"
        requireValid("findMergedPullRequest", validRepo() && validBranch(branch))
        val out = call(
            "findMergedPullRequest",
            subject,
            "pr", "list", "--repo", repo, "--head", branch, "--state", "merged",
            "--json", "number,mergeCommit,headRefOid,mergedBy",
        )
        return parse("findMergedPullRequest", subject) {
            JSON.parseToJsonElement(out).jsonArray.firstOrNull()?.jsonObject?.let { toMerged(it, subject) }
        }
    }

    override fun listPullRequests(): List<PullRequestSummary> {
        val subject = "repo"
        requireValid("listPullRequests", validRepo())
        val out = call(
            "listPullRequests",
            subject,
            "pr", "list", "--repo", repo, "--state", "all", "--limit", "1000",
            "--json", "number,headRefName,headRefOid,title,state,author,isCrossRepository",
        )
        return parse("listPullRequests", subject) {
            JSON.parseToJsonElement(out).jsonArray.map { toSummary(it.jsonObject) }
        }
    }

    override fun authenticatedLogin(): String {
        val subject = "user"
        val out = call("authenticatedLogin", subject, "api", "user", "--jq", ".login").trim()
        if (!LOGIN.matches(out)) throw ForgeException("authenticatedLogin falhou ($subject): resposta inválida")
        return out
    }

    private fun toSummary(obj: JsonObject): PullRequestSummary =
        PullRequestSummary(
            number = requireNotNull(obj["number"]?.jsonPrimitive?.intOrNull) { "number ausente" },
            branch = obj.text("headRefName").orEmpty(),
            title = obj.text("title").orEmpty(),
            state = when (obj.text("state")) {
                "OPEN" -> PullRequestState.Open(obj.text("headRefOid").orEmpty())
                "MERGED" -> PullRequestState.Merged
                else -> PullRequestState.ClosedUnmerged
            },
            author = obj["author"]?.asObject()?.text("login").orEmpty(),
            fromFork = (obj["isCrossRepository"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

    private fun toMerged(obj: JsonObject, subject: String): MergedPullRequest {
        val oid = obj["mergeCommit"]?.asObject()?.text("oid")
        if (oid.isNullOrEmpty()) throw ForgeException("findMergedPullRequest falhou ($subject): merge commit ausente")
        val login = obj["mergedBy"]?.asObject()?.text("login")?.takeIf { LOGIN.matches(it) }
        return MergedPullRequest(
            number = requireNotNull(obj["number"]?.jsonPrimitive?.intOrNull) { "number ausente" },
            mergeCommit = oid,
            head = obj.text("headRefOid").orEmpty(),
            mergedBy = login,
        )
    }

    override fun openPullRequest(request: PullRequestRequest): PullRequestRef {
        val subject = "branch=${request.branch}"
        requireValid("openPullRequest", validRepo() && validBranch(request.branch) && validBranch(request.base))
        findOpenPullRequest(request.branch)?.let { return it }
        val bodyFile = writeTemp("openPullRequest", subject, request.body)
        try {
            call(
                "openPullRequest",
                subject,
                "pr", "create", "--repo", repo, "--head", request.branch, "--base", request.base,
                "--title", request.title, "--body-file", bodyFile.toString(),
            )
        } finally {
            Files.deleteIfExists(bodyFile)
        }
        return findOpenPullRequest(request.branch)
            ?: throw ForgeException("openPullRequest falhou ($subject)")
    }

    override fun checks(head: String): ChecksState {
        val subject = "head"
        requireValid("checks", validRepo() && SHA.matches(head))
        val out = call(
            "checks",
            subject,
            "api", "--paginate", "repos/$repo/commits/$head/check-runs?per_page=100",
        )
        val runs = parse("checks", subject) {
            splitObjects(out).flatMap { JSON.parseToJsonElement(it).jsonObject["check_runs"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }
        }
        val failing = runs
            .filter { it.text("status") == "completed" && it.text("conclusion") in FAILED_CONCLUSIONS }
            .map { FailedCheck(it.text("name").orEmpty(), capSummary(it["output"]?.asObject()?.text("summary").orEmpty())) }
        if (failing.isNotEmpty()) return ChecksState.Failed(failing)
        val allPassed = runs.isNotEmpty() &&
            runs.all { it.text("status") == "completed" && it.text("conclusion") in PASSED_CONCLUSIONS }
        return if (allPassed) ChecksState.Passed else ChecksState.Pending
    }

    override fun merge(pr: PullRequestRef, expectedHead: String): MergeResult {
        val subject = "pr=${pr.number}"
        requireValid("merge", validRepo() && SHA.matches(expectedHead))
        val view = view("merge", pr)
        val state = stateOf(view)
        if (state !is PullRequestState.Open) return MergeResult.NotOpen(state)
        if (state.head != expectedHead) return MergeResult.HeadMismatch(state.head)
        // A branch behind the base cannot be merged either (the repository requires it up to date): the same way out as a conflict.
        if (isBehindOrConflicting(view)) return MergeResult.Conflict
        try {
            call(
                "merge",
                subject,
                "pr", "merge", pr.number.toString(), "--repo", repo, "--squash", "--match-head-commit", expectedHead,
            )
        } catch (e: ForgeException) {
            // GitHub computes mergeability lazily: the view above may still say UNKNOWN for a branch that is behind.
            if (isBehindOrConflicting(view("merge", pr))) return MergeResult.Conflict
            throw e
        }
        val oid = view("merge", pr)["mergeCommit"]?.asObject()?.text("oid")
        if (oid.isNullOrEmpty()) throw ForgeException("merge falhou ($subject): merge commit ausente")
        return MergeResult.Merged(oid)
    }

    private fun isBehindOrConflicting(view: JsonObject): Boolean =
        view.text("mergeable") == "CONFLICTING" || view.text("mergeStateStatus") == "BEHIND"

    override fun rerunChecks(head: String) {
        val subject = "head"
        requireValid("rerunChecks", validRepo() && SHA.matches(head))
        val out = call("rerunChecks", subject, "api", "repos/$repo/actions/runs?head_sha=$head&per_page=100")
        val ids = parse("rerunChecks", subject) {
            JSON.parseToJsonElement(out).jsonObject["workflow_runs"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it.text("status") == "completed" && it.text("conclusion") == "failure" }
                .map { requireNotNull((it["id"] as? JsonPrimitive)?.longOrNull) { "id ausente" } }
        }
        ids.forEach { id ->
            call("rerunChecks", subject, "api", "-X", "POST", "repos/$repo/actions/runs/$id/rerun-failed-jobs")
        }
    }

    override fun revert(pr: PullRequestRef): PullRequestRef {
        val subject = "pr=${pr.number}"
        requireValid("revert", validRepo())
        val viewOut = call(
            "revert",
            subject,
            "pr", "view", pr.number.toString(), "--repo", repo, "--json", "id",
            unknownPrMarker = NOT_FOUND_MARKER,
            unknownPr = pr.number,
        )
        val nodeId = parse("revert", subject) { JSON.parseToJsonElement(viewOut).jsonObject.text("id").orEmpty() }
        requireValid("revert", NODE_ID.matches(nodeId))
        val out = call(
            "revert",
            subject,
            "api", "graphql", "-f", "query=$REVERT_MUTATION", "-f", "id=$nodeId",
        )
        return parse("revert", subject) {
            val opened = requireNotNull(
                JSON.parseToJsonElement(out).jsonObject["data"]?.asObject()
                    ?.get("revertPullRequest")?.asObject()
                    ?.get("revertPullRequest")?.asObject(),
            ) { "revert ausente" }
            PullRequestRef(
                number = requireNotNull(opened["number"]?.jsonPrimitive?.intOrNull) { "number ausente" },
                branch = opened.text("headRefName").orEmpty(),
                head = opened.text("headRefOid").orEmpty(),
            )
        }
    }

    override fun state(pr: PullRequestRef): PullRequestState {
        requireValid("state", validRepo())
        return stateOf(view("state", pr))
    }

    private fun view(operation: String, pr: PullRequestRef): JsonObject {
        val subject = "pr=${pr.number}"
        val out = call(
            operation,
            subject,
            "pr", "view", pr.number.toString(), "--repo", repo, "--json", "state,headRefOid,mergeable,mergeStateStatus,mergeCommit",
            unknownPrMarker = NOT_FOUND_MARKER,
            unknownPr = pr.number,
        )
        return parse(operation, subject) { JSON.parseToJsonElement(out).jsonObject }
    }

    private fun stateOf(view: JsonObject): PullRequestState =
        when (view.text("state")) {
            "OPEN" -> PullRequestState.Open(view.text("headRefOid").orEmpty())
            "MERGED" -> PullRequestState.Merged
            else -> PullRequestState.ClosedUnmerged
        }

    private fun toRef(obj: JsonObject): PullRequestRef =
        PullRequestRef(
            number = requireNotNull(obj["number"]?.jsonPrimitive?.intOrNull) { "number ausente" },
            branch = obj.text("headRefName").orEmpty(),
            head = obj.text("headRefOid").orEmpty(),
        )

    /** Runs `gh` with stdout in a temp file under [tmpDir]; returns what it printed. */
    private fun call(
        operation: String,
        subject: String,
        vararg args: String,
        unknownPrMarker: String? = null,
        unknownPr: Int = 0,
    ): String {
        val outFile = writeTemp(operation, subject, "")
        try {
            val outcome = try {
                runner.run(listOf(gh) + args, workDir, outFile, timeout, mapOf("GH_PROMPT_DISABLED" to "1"))
            } catch (e: IOException) {
                throw ForgeException("$operation falhou ($subject)")
            }
            val text = Files.readString(outFile)
            if (outcome != ProcessOutcome.Exited(0)) {
                if (unknownPrMarker != null && text.contains(unknownPrMarker)) {
                    throw UnknownPullRequestException("$operation: PR desconhecido (pr=$unknownPr)")
                }
                throw ForgeException("$operation falhou ($subject)")
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
            throw ForgeException("$operation falhou ($subject)")
        }

    private fun <T> parse(operation: String, subject: String, block: () -> T): T =
        try {
            block()
        } catch (e: SerializationException) {
            throw ForgeException("$operation falhou ($subject): resposta inválida")
        } catch (e: IllegalArgumentException) {
            throw ForgeException("$operation falhou ($subject): resposta inválida")
        } catch (e: IllegalStateException) {
            throw ForgeException("$operation falhou ($subject): resposta inválida")
        }

    private fun requireValid(operation: String, valid: Boolean) {
        if (!valid) throw ForgeException("$operation recusou entrada inválida")
    }

    private fun validRepo(): Boolean = REPO.matches(repo)

    private fun validBranch(branch: String): Boolean =
        branch.isNotEmpty() && !branch.startsWith("-") && !branch.contains("..") &&
            branch.none { it.isWhitespace() || it.isISOControl() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun capSummary(summary: String): String {
        val out = StringBuilder()
        var bytes = 0
        for (cp in summary.codePoints().toArray()) {
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > SUMMARY_MAX_BYTES) break
            out.appendCodePoint(cp)
            bytes += size
        }
        return out.toString()
    }

    /** `gh api --paginate` prints one JSON object per page, back to back. */
    private fun splitObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { i, c ->
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0) objects += text.substring(start, i + 1)
                }
            }
        }
        if (depth != 0 || inString) throw ForgeException("checks falhou (head): resposta inválida")
        return objects
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SHA = Regex("^[0-9a-f]{40}$")
        val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        val LOGIN = Regex("^[A-Za-z0-9-]{1,39}$")
        val PASSED_CONCLUSIONS = setOf("success", "neutral", "skipped")
        val FAILED_CONCLUSIONS = setOf("failure", "cancelled", "timed_out", "action_required", "stale")
        val NODE_ID = Regex("^[A-Za-z0-9_=-]+$")
        const val REVERT_MUTATION =
            "mutation(\$id: ID!) { revertPullRequest(input: {pullRequestId: \$id}) " +
                "{ revertPullRequest { number headRefName headRefOid } } }"
        const val SUMMARY_MAX_BYTES = 2048
        const val NOT_FOUND_MARKER = "Could not resolve to a PullRequest"
    }
}
