package shipfloor.adapters

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.ports.ChecksState
import shipfloor.ports.FailedCheck
import shipfloor.ports.FakeForge
import shipfloor.ports.Forge
import shipfloor.ports.ForgeException
import shipfloor.ports.MergeResult
import shipfloor.ports.MergedPullRequest
import shipfloor.ports.PullRequestRef
import shipfloor.ports.PullRequestRequest
import shipfloor.ports.PullRequestState

/** A simulated `gh`: answers from files under [rec] (`<name>.out/.err/.exit`) and logs every call to `calls`. */
class GhHarness(private val root: Path) {
    val rec: Path = Files.createDirectories(root.resolve("rec"))
    val workDir: Path = Files.createDirectories(root.resolve("work"))
    val bodies: Path = Files.createDirectories(root.resolve("bodies"))
    private val bin: Path = Files.createDirectories(root.resolve("bin"))
    val gh: Path = ClaudeRuntimeFixtures.aScript(bin, "gh", script())

    fun forge(ghPath: String = gh.toString(), repo: String = GhForgeFixtures.REPO): GhForge =
        GhForge(repo, ghPath, workDir, bodies, Duration.ofSeconds(30))

    /** Scripts one gh subcommand: name is `pr-list`, `pr-create`, `pr-view`, `pr-merge`, `api`, or `after-create`/`after-merge`. */
    fun on(name: String, out: String = "", err: String = "", exit: Int = 0) {
        Files.writeString(rec.resolve("$name.out"), out)
        if (err.isNotEmpty()) Files.writeString(rec.resolve("$name.err"), err)
        if (exit != 0) Files.writeString(rec.resolve("$name.exit"), exit.toString())
    }

    fun calls(): List<String> =
        if (Files.exists(rec.resolve("calls"))) Files.readAllLines(rec.resolve("calls")) else emptyList()

    fun callsTo(prefix: String): List<String> = calls().filter { it.startsWith(prefix) }

    fun body(): String = Files.readString(rec.resolve("body"))

    private fun script(): String =
        """
        rec="$rec"
        echo "§*" >> "§rec/calls"
        prev=""
        for a in "§@"; do
          if [ "§prev" = "--body-file" ]; then cp "§a" "§rec/body"; fi
          prev="§a"
        done
        name="§1-§2"
        if [ "§1" = "api" ]; then name=api; fi
        if [ -f "§rec/§name.out" ]; then cat "§rec/§name.out"; fi
        if [ -f "§rec/§name.err" ]; then cat "§rec/§name.err" >&2; fi
        code=0
        if [ -f "§rec/§name.exit" ]; then code=§(cat "§rec/§name.exit"); fi
        if [ "§code" -eq 0 ]; then
          if [ "§name" = "pr-create" ] && [ -f "§rec/after-create.out" ]; then cp "§rec/after-create.out" "§rec/pr-list.out"; fi
          if [ "§name" = "pr-merge" ] && [ -f "§rec/after-merge.out" ]; then cp "§rec/after-merge.out" "§rec/pr-view.out"; fi
        else
          if [ "§name" = "pr-merge" ] && [ -f "§rec/after-merge-failed.out" ]; then cp "§rec/after-merge-failed.out" "§rec/pr-view.out"; fi
        fi
        exit §code
        """.trimIndent().replace('§', '$')
}

object GhForgeFixtures {
    const val REPO = "acme/widgets"
    const val BRANCH = "feat/44-forge"
    const val HEAD = "0123456789abcdef0123456789abcdef01234567"
    const val OTHER_HEAD = "fedcba9876543210fedcba9876543210fedcba98"
    const val MERGE_OID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val SECRET = "ghp_SUPERSECRETTOKEN issue-body-text"

    fun aRequest(branch: String = BRANCH, body: String = "Corpo do PR\n\nCloses #44") =
        PullRequestRequest(branch, "main", "feat(forge): abre PR (#44)", body)

    fun aRef(number: Int = 7, head: String = HEAD) = PullRequestRef(number, BRANCH, head)

    fun listJson(number: Int = 7, head: String = HEAD, branch: String = BRANCH) =
        """[{"number":$number,"headRefName":"$branch","headRefOid":"$head"}]"""

    fun viewJson(
        state: String = "OPEN",
        head: String = HEAD,
        mergeable: String = "MERGEABLE",
        mergeOid: String? = null,
        mergeState: String = "CLEAN",
    ) =
        """{"state":"$state","headRefOid":"$head","mergeable":"$mergeable","mergeStateStatus":"$mergeState","mergeCommit":${
            if (mergeOid == null) "null" else """{"oid":"$mergeOid"}"""
        }}"""

    fun run(name: String, status: String = "completed", conclusion: String? = "success", summary: String = "") =
        """{"name":"$name","status":"$status","conclusion":${
            if (conclusion == null) "null" else "\"$conclusion\""
        },"output":{"summary":"$summary"}}"""

    fun page(vararg runs: String) = """{"total_count":${runs.size},"check_runs":[${runs.joinToString(",")}]}"""
}

class GhForgeTest {

    @TempDir
    lateinit var tmp: Path

    private fun harness() = GhHarness(tmp)

    @Test
    fun `AC-1 opens the PR with title and body-file and returns number and head`() {
        val h = harness()
        h.on("pr-list", "[]")
        h.on("after-create", GhForgeFixtures.listJson())
        h.on("pr-create", "https://github.com/acme/widgets/pull/7\n")
        val request = GhForgeFixtures.aRequest()

        val ref = h.forge().openPullRequest(request)

        assertThat(ref).isEqualTo(GhForgeFixtures.aRef())
        val create = h.callsTo("pr create").single()
        assertThat(create).contains("--repo ${GhForgeFixtures.REPO}", "--head ${GhForgeFixtures.BRANCH}", "--base main")
        assertThat(create).contains(request.title)
        assertThat(create).doesNotContain("Closes #44")
        assertThat(h.body()).isEqualTo(request.body)
        assertThat(Files.list(h.bodies).use { it.count() }).isZero()
    }

    @Test
    fun `AC-2 merges as squash guarded by the expected head`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson())
        h.on("after-merge", GhForgeFixtures.viewJson(state = "MERGED", mergeOid = GhForgeFixtures.MERGE_OID))

        val result = h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD)

        assertThat(result).isEqualTo(MergeResult.Merged(GhForgeFixtures.MERGE_OID))
        val merge = h.callsTo("pr merge").single()
        assertThat(merge).contains("7", "--squash", "--match-head-commit ${GhForgeFixtures.HEAD}")
    }

    @Test
    fun `AC-2 fails when the merge commit is still empty after a successful merge`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson())
        h.on("after-merge", GhForgeFixtures.viewJson(state = "MERGED"))

        val e = assertThrows<ForgeException> { h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD) }

        assertThat(e.message).contains("7")
    }

    @Test
    fun `AC-3 refuses to merge when the head differs and leaves the PR untouched`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson(head = GhForgeFixtures.OTHER_HEAD))

        val result = h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD)

        assertThat(result).isEqualTo(MergeResult.HeadMismatch(GhForgeFixtures.OTHER_HEAD))
        assertThat(h.callsTo("pr merge")).isEmpty()
    }

    @Test
    fun `AC-2 reports Conflict and NotOpen without merging`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson(mergeable = "CONFLICTING"))
        assertThat(h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD)).isEqualTo(MergeResult.Conflict)

        h.on("pr-view", GhForgeFixtures.viewJson(state = "CLOSED"))
        assertThat(h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD))
            .isEqualTo(MergeResult.NotOpen(PullRequestState.ClosedUnmerged))
        assertThat(h.callsTo("pr merge")).isEmpty()
    }

    @Test
    fun `AC-2 a merge that fails on a branch the view still called unknown becomes Conflict once the view says behind`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson(mergeState = "UNKNOWN"))
        h.on("pr-merge", err = "not up to date", exit = 1)
        h.on("after-merge-failed", GhForgeFixtures.viewJson(mergeState = "BEHIND"))

        assertThat(h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD)).isEqualTo(MergeResult.Conflict)
        assertThat(h.callsTo("pr merge")).hasSize(1)
    }

    @Test
    fun `AC-2 a merge that fails on a branch that is not behind keeps failing`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson())
        h.on("pr-merge", err = "boom", exit = 1)

        assertThrows<ForgeException> { h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD) }
    }

    @Test
    fun `AC-2 a branch behind the base is reported as Conflict and nothing is merged`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson(mergeState = "BEHIND"))

        assertThat(h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD)).isEqualTo(MergeResult.Conflict)
        assertThat(h.callsTo("pr merge")).isEmpty()
    }

    @Test
    fun `AC-4 gh failure names the PR without leaking stderr and leaves no body file`() {
        val h = harness()
        h.on("pr-view", err = GhForgeFixtures.SECRET, exit = 1)
        h.on("pr-list", "[]")
        h.on("pr-create", err = GhForgeFixtures.SECRET, exit = 1)

        val merge = assertThrows<ForgeException> { h.forge().merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD) }
        val state = assertThrows<ForgeException> { h.forge().state(GhForgeFixtures.aRef()) }
        val open = assertThrows<ForgeException> { h.forge().openPullRequest(GhForgeFixtures.aRequest()) }

        assertThat(merge.message).contains("7").doesNotContain("ghp_", "issue-body-text")
        assertThat(state.message).contains("7").doesNotContain("ghp_", "issue-body-text")
        assertThat(open.message).contains(GhForgeFixtures.BRANCH).doesNotContain("ghp_", "issue-body-text")
        assertThat(Files.list(h.bodies).use { it.count() }).isZero()
        assertThat(h.callsTo("pr merge")).isEmpty()
    }

    private fun mergedJson(login: String? = "octocat", oid: String? = GhForgeFixtures.MERGE_OID) =
        """[{"number":7,"mergeCommit":${if (oid == null) "{}" else """{"oid":"$oid"}"""},""" +
            """"headRefOid":"${GhForgeFixtures.HEAD}","mergedBy":${if (login == null) "null" else """{"login":"$login"}"""}}]"""

    @Test
    fun `AC-9 finds the merged PR of a branch with number, merge commit, head and mergedBy`() {
        val h = harness()
        h.on("pr-list", mergedJson())

        val merged = h.forge().findMergedPullRequest(GhForgeFixtures.BRANCH)

        assertThat(merged).isEqualTo(
            MergedPullRequest(7, GhForgeFixtures.MERGE_OID, GhForgeFixtures.HEAD, "octocat"),
        )
        assertThat(h.callsTo("pr list").single())
            .contains("--repo ${GhForgeFixtures.REPO}", "--head ${GhForgeFixtures.BRANCH}", "--state merged")
    }

    @Test
    fun `AC-9 no merged PR is null and a login out of format is omitted`() {
        val h = harness()
        h.on("pr-list", "[]")
        assertThat(h.forge().findMergedPullRequest(GhForgeFixtures.BRANCH)).isNull()

        h.on("pr-list", mergedJson(login = "bad login;rm"))
        assertThat(h.forge().findMergedPullRequest(GhForgeFixtures.BRANCH)?.mergedBy).isNull()
        assertThat(h.forge().findMergedPullRequest(GhForgeFixtures.BRANCH)?.number).isEqualTo(7)
    }

    @Test
    fun `AC-9 refuses an invalid branch or repo without calling gh and fails on a missing merge commit`() {
        val h = harness()
        h.on("pr-list", mergedJson())
        assertThrows<ForgeException> { h.forge().findMergedPullRequest("-evil") }
        assertThrows<ForgeException> { h.forge(repo = "no-slash").findMergedPullRequest(GhForgeFixtures.BRANCH) }
        assertThat(h.calls()).isEmpty()

        h.on("pr-list", mergedJson(oid = null))
        assertThrows<ForgeException> { h.forge().findMergedPullRequest(GhForgeFixtures.BRANCH) }
    }

    @Test
    fun `AC-4 a missing gh binary becomes a ForgeException`() {
        val h = harness()

        val e = assertThrows<ForgeException> {
            h.forge(ghPath = tmp.resolve("no-such-gh").toString()).state(GhForgeFixtures.aRef())
        }

        assertThat(e.message).contains("7")
    }

    @Test
    fun `AC-5 retrying the open returns the existing PR without creating another`() {
        val h = harness()
        h.on("pr-list", GhForgeFixtures.listJson())

        val ref = h.forge().openPullRequest(GhForgeFixtures.aRequest())

        assertThat(ref).isEqualTo(GhForgeFixtures.aRef())
        assertThat(h.callsTo("pr create")).isEmpty()
    }

    @Test
    fun `AC-7 no checks registered is Pending never Passed`() {
        val h = harness()
        h.on("api", GhForgeFixtures.page())

        assertThat(h.forge().checks(GhForgeFixtures.HEAD)).isEqualTo(ChecksState.Pending)
        assertThat(h.callsTo("api").single()).contains("--paginate", "repos/${GhForgeFixtures.REPO}/commits/${GhForgeFixtures.HEAD}/check-runs")
    }

    @Test
    fun `AC-7 a failure on the second page is Failed with the failing check`() {
        val h = harness()
        val firstPage = GhForgeFixtures.page(*Array(30) { GhForgeFixtures.run("ok-$it") })
        val secondPage = GhForgeFixtures.page(GhForgeFixtures.run("late-red", conclusion = "failure", summary = "boom"))
        h.on("api", firstPage + secondPage)

        assertThat(h.forge().checks(GhForgeFixtures.HEAD))
            .isEqualTo(ChecksState.Failed(listOf(FailedCheck("late-red", "boom"))))
    }

    @Test
    fun `AC-7 cancelled and timed_out are Failed, in-progress is Pending, success neutral skipped pass`() {
        val h = harness()
        h.on(
            "api",
            GhForgeFixtures.page(
                GhForgeFixtures.run("a", conclusion = "cancelled"),
                GhForgeFixtures.run("b", conclusion = "timed_out"),
                GhForgeFixtures.run("c"),
            ),
        )
        val failed = h.forge().checks(GhForgeFixtures.HEAD)
        assertThat(failed).isInstanceOf(ChecksState.Failed::class.java)
        assertThat((failed as ChecksState.Failed).failing.map { it.name }).containsExactlyInAnyOrder("a", "b")

        h.on(
            "api",
            GhForgeFixtures.page(
                GhForgeFixtures.run("a"),
                GhForgeFixtures.run("b", status = "in_progress", conclusion = null),
            ),
        )
        assertThat(h.forge().checks(GhForgeFixtures.HEAD)).isEqualTo(ChecksState.Pending)

        h.on(
            "api",
            GhForgeFixtures.page(
                GhForgeFixtures.run("a"),
                GhForgeFixtures.run("b", conclusion = "neutral"),
                GhForgeFixtures.run("c", conclusion = "skipped"),
            ),
        )
        assertThat(h.forge().checks(GhForgeFixtures.HEAD)).isEqualTo(ChecksState.Passed)
    }

    @Test
    fun `AC-7 failing summary is cut to 2048 UTF-8 bytes`() {
        val h = harness()
        h.on("api", GhForgeFixtures.page(GhForgeFixtures.run("big", conclusion = "failure", summary = "é".repeat(2000))))

        val failed = h.forge().checks(GhForgeFixtures.HEAD) as ChecksState.Failed

        assertThat(failed.failing.single().summary.toByteArray(Charsets.UTF_8).size).isEqualTo(2048)
    }

    @Test
    fun `AC-8 invalid inputs fail with ForgeException before calling gh and without echoing the value`() {
        val h = harness()
        h.on("pr-view", GhForgeFixtures.viewJson())
        h.on("pr-list", "[]")
        h.on("api", GhForgeFixtures.page())
        val evil = "--evil;rm-rf"
        val forge = h.forge()
        val attempts = listOf<() -> Unit>(
            { forge.checks("not-a-sha-$evil") },
            { forge.checks("../../x") },
            { forge.merge(GhForgeFixtures.aRef(), "ABC$evil") },
            { forge.merge(GhForgeFixtures.aRef(), GhForgeFixtures.HEAD.uppercase()) },
            { forge.findOpenPullRequest("-$evil") },
            { forge.findOpenPullRequest("a..b$evil") },
            { forge.findOpenPullRequest("with space$evil") },
            { forge.findOpenPullRequest("ctrl\u0007$evil") },
            { forge.openPullRequest(GhForgeFixtures.aRequest(branch = "-$evil")) },
            { h.forge(repo = "no-slash$evil").checks(GhForgeFixtures.HEAD) },
            { h.forge(repo = "a/b/c$evil").findOpenPullRequest(GhForgeFixtures.BRANCH) },
            { h.forge(repo = "a/b c").checks(GhForgeFixtures.HEAD) },
        )

        attempts.forEach { attempt ->
            val e = assertThrows<ForgeException> { attempt() }
            assertThat(e.message).doesNotContain("evil", "rm-rf", "not-a-sha", "no-slash", "../")
        }
        assertThat(h.calls()).isEmpty()
    }

    @Test
    fun `AC-6 the same sequence gives equivalent results on the fake and on gh`() {
        val h = harness()
        h.on("pr-list", "[]")
        h.on("after-create", GhForgeFixtures.listJson())
        h.on("pr-create", "https://github.com/acme/widgets/pull/7\n")
        h.on("pr-view", GhForgeFixtures.viewJson())
        h.on("after-merge", GhForgeFixtures.viewJson(state = "MERGED", mergeOid = GhForgeFixtures.MERGE_OID))
        h.on("api", GhForgeFixtures.page())

        val fake = FakeForge()
        val fromFake = sequence(fake) { fake.pushHeadSha(it) }
        val fromGh = sequence(h.forge()) { }

        assertThat(fromGh).isEqualTo(fromFake)
    }

    @Test
    fun `rerunChecks posts rerun-failed-jobs only for completed failed runs using integer ids`() {
        val h = harness()
        h.on(
            "api",
            """{"workflow_runs":[{"id":111,"status":"completed","conclusion":"failure"},""" +
                """{"id":222,"status":"completed","conclusion":"success"},""" +
                """{"id":333,"status":"in_progress","conclusion":null},""" +
                """{"id":444,"status":"completed","conclusion":"failure"}]}""",
        )

        h.forge().rerunChecks(GhForgeFixtures.HEAD)

        assertThat(h.callsTo("api -X POST"))
            .containsExactly(
                "api -X POST repos/acme/widgets/actions/runs/111/rerun-failed-jobs",
                "api -X POST repos/acme/widgets/actions/runs/444/rerun-failed-jobs",
            )
    }

    @Test
    fun `rerunChecks refuses an invalid sha without calling gh`() {
        val h = harness()

        assertThrows<ForgeException> { h.forge().rerunChecks("main; rm -rf /") }

        assertThat(h.calls()).isEmpty()
    }

    @Test
    fun `rerunChecks failure carries the operation and no gh output`() {
        val h = harness()
        h.on("api", err = GhForgeFixtures.SECRET, exit = 1)

        val e = assertThrows<ForgeException> { h.forge().rerunChecks(GhForgeFixtures.HEAD) }

        assertThat(e.message).contains("rerunChecks").doesNotContain("SUPERSECRET")
    }

    @Test
    fun `revert opens the PR through the mutation and returns the opened ref`() {
        val h = harness()
        h.on("pr-view", """{"id":"PR_kwDOabc123"}""")
        h.on(
            "api",
            """{"data":{"revertPullRequest":{"revertPullRequest":""" +
                """{"number":9,"headRefName":"revert-7-feat","headRefOid":"${GhForgeFixtures.OTHER_HEAD}"}}}}""",
        )

        val ref = h.forge().revert(GhForgeFixtures.aRef(number = 7))

        assertThat(ref).isEqualTo(PullRequestRef(9, "revert-7-feat", GhForgeFixtures.OTHER_HEAD))
        assertThat(h.callsTo("api graphql").single()).contains("revertPullRequest").contains("id=PR_kwDOabc123")
    }

    @Test
    fun `revert failure names the PR and hides gh output`() {
        val h = harness()
        h.on("pr-view", """{"id":"PR_kwDOabc123"}""")
        h.on("api", err = GhForgeFixtures.SECRET, exit = 1)

        val e = assertThrows<ForgeException> { h.forge().revert(GhForgeFixtures.aRef(number = 7)) }

        assertThat(e.message).contains("revert").contains("pr=7").doesNotContain("SUPERSECRET")
    }

    /** Observable outcomes of one delivery sequence, with SHAs and numbers reduced to their kind. */
    private fun sequence(forge: Forge, afterOpen: (PullRequestRef) -> Unit): List<String> {
        val out = mutableListOf<String>()
        out += "find-before=${forge.findOpenPullRequest(GhForgeFixtures.BRANCH)}"
        val ref = forge.openPullRequest(GhForgeFixtures.aRequest())
        afterOpen(ref)
        out += "opened-branch=${ref.branch}"
        out += "find-after-matches=${forge.findOpenPullRequest(GhForgeFixtures.BRANCH)?.number == ref.number}"
        out += "checks=${forge.checks(GhForgeFixtures.HEAD)}"
        val mismatch = forge.merge(ref, GhForgeFixtures.OTHER_HEAD)
        out += "mismatch=${mismatch::class.simpleName}:${(mismatch as? MergeResult.HeadMismatch)?.actualHead == GhForgeFixtures.HEAD}"
        out += "state-open=${forge.state(ref) is PullRequestState.Open}"
        out += "merge=${forge.merge(ref, GhForgeFixtures.HEAD)::class.simpleName}"
        out += "state-after=${forge.state(ref)}"
        out += "again=${forge.merge(ref, GhForgeFixtures.HEAD)}"
        return out
    }

    @Test
    fun `listPullRequests maps state, author and fork flag of every PR`() {
        val h = harness()
        h.on(
            "pr-list",
            """[
            {"number":1,"headRefName":"feat/a","headRefOid":"${GhForgeFixtures.HEAD}","title":"A","state":"OPEN","author":{"login":"octocat"},"isCrossRepository":false},
            {"number":2,"headRefName":"feat/b","headRefOid":"x","title":"B","state":"MERGED","author":{"login":"octocat"},"isCrossRepository":false},
            {"number":3,"headRefName":"feat/c","headRefOid":"y","title":"C","state":"CLOSED","author":{"login":"stranger"},"isCrossRepository":true}
            ]""",
        )

        val listed = h.forge().listPullRequests()

        assertThat(listed.map { it.number }).containsExactly(1, 2, 3)
        assertThat(listed.map { it.state }).containsExactly(
            PullRequestState.Open(GhForgeFixtures.HEAD),
            PullRequestState.Merged,
            PullRequestState.ClosedUnmerged,
        )
        assertThat(listed.map { it.author }).containsExactly("octocat", "octocat", "stranger")
        assertThat(listed.map { it.fromFork }).containsExactly(false, false, true)
        assertThat(listed[0].title).isEqualTo("A")
        assertThat(listed[0].branch).isEqualTo("feat/a")
        assertThat(h.callsTo("pr list").single()).contains("--state all", "--limit 1000")
    }

    @Test
    fun `listPullRequests failure names the operation without leaking stderr`() {
        val h = harness()
        h.on("pr-list", err = GhForgeFixtures.SECRET, exit = 1)

        val e = assertThrows<ForgeException> { h.forge().listPullRequests() }

        assertThat(e.message).contains("listPullRequests").doesNotContain("ghp_")
    }

    @Test
    fun `authenticatedLogin returns the validated login`() {
        val h = harness()
        h.on("api", "octocat\n")

        assertThat(h.forge().authenticatedLogin()).isEqualTo("octocat")
        assertThat(h.callsTo("api").single()).contains("user", "--jq .login")
    }

    @Test
    fun `authenticatedLogin rejects an invalid login and names the operation on failure`() {
        val h = harness()
        h.on("api", "bad login; rm -rf\n")
        val invalid = assertThrows<ForgeException> { h.forge().authenticatedLogin() }
        assertThat(invalid.message).contains("authenticatedLogin")

        h.on("api", err = GhForgeFixtures.SECRET, exit = 1)
        val failed = assertThrows<ForgeException> { h.forge().authenticatedLogin() }
        assertThat(failed.message).contains("authenticatedLogin").doesNotContain("ghp_")
    }

    private fun FakeForge.pushHeadSha(ref: PullRequestRef) = pushHead(ref, GhForgeFixtures.HEAD)
}
