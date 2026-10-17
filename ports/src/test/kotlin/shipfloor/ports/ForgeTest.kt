package shipfloor.ports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

object ForgeFixtures {
    fun aRequest(
        branch: String = "feat/43-x",
        base: String = "main",
        title: String = "title",
        body: String = "secret-body-text",
    ): PullRequestRequest = PullRequestRequest(branch, base, title, body)
}

class ForgeTest {

    @Test
    fun `AC-1 openPullRequest returns the ref with source branch and head`() {
        val forge = FakeForge()

        val ref = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/43-x"))

        assertThat(ref.branch).isEqualTo("feat/43-x")
        assertThat(ref.head).isNotBlank()
        assertThat(forge.opened).hasSize(1)
        assertThat(forge.state(ref)).isEqualTo(PullRequestState.Open(ref.head))
    }

    @Test
    fun `AC-2 checks return the state of the head with name and summary of failures capped at 2048 bytes`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest())
        val longSummary = "x".repeat(5000)
        forge.scriptChecks(
            ref.head,
            ChecksState.Failed(listOf(FailedCheck("unit", "boom"), FailedCheck("lint", longSummary))),
        )

        val state = forge.checks(ref.head)

        assertThat(state).isInstanceOf(ChecksState.Failed::class.java)
        val failing = (state as ChecksState.Failed).failing
        assertThat(failing.map { it.name }).containsExactly("unit", "lint")
        assertThat(failing[0].summary).isEqualTo("boom")
        assertThat(failing[1].summary.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(2048)
        assertThat(failing[1].summary).isNotEmpty()
        assertThat(forge.checks("other-head-without-script")).isEqualTo(ChecksState.Pending)
    }

    @Test
    fun `AC-3 merge with the current head merges the PR and returns the merge commit`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest())

        val result = forge.merge(ref, ref.head)

        assertThat(result).isInstanceOf(MergeResult.Merged::class.java)
        assertThat((result as MergeResult.Merged).mergeCommit).isNotBlank()
        assertThat(forge.state(ref)).isEqualTo(PullRequestState.Merged)
        assertThat(forge.merges).hasSize(1)
    }

    @Test
    fun `AC-4 merge with a stale head is refused and the PR stays open`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest())
        forge.pushHead(ref, "new-head-sha")

        val result = forge.merge(ref, ref.head)

        assertThat(result).isEqualTo(MergeResult.HeadMismatch("new-head-sha"))
        assertThat(forge.state(ref)).isEqualTo(PullRequestState.Open("new-head-sha"))
        assertThat(forge.merges).isEmpty()
    }

    @Test
    fun `AC-5 state distinguishes open, merged and closed without merge`() {
        val forge = FakeForge()
        val open = forge.openPullRequest(ForgeFixtures.aRequest(branch = "b1"))
        val merged = forge.openPullRequest(ForgeFixtures.aRequest(branch = "b2"))
        val closed = forge.openPullRequest(ForgeFixtures.aRequest(branch = "b3"))
        forge.merge(merged, merged.head)
        forge.closeOutside(closed)

        assertThat(forge.state(open)).isEqualTo(PullRequestState.Open(open.head))
        assertThat(forge.state(merged)).isEqualTo(PullRequestState.Merged)
        assertThat(forge.state(closed)).isEqualTo(PullRequestState.ClosedUnmerged)
    }

    @Test
    fun `AC-6 fake returns scripted green, red, merged-outside and closed scenarios`() {
        val forge = FakeForge()
        val green = forge.openPullRequest(ForgeFixtures.aRequest(branch = "g"))
        val red = forge.openPullRequest(ForgeFixtures.aRequest(branch = "r"))
        val outside = forge.openPullRequest(ForgeFixtures.aRequest(branch = "m"))
        val closed = forge.openPullRequest(ForgeFixtures.aRequest(branch = "c"))
        forge.scriptChecks(green.head, ChecksState.Passed)
        forge.scriptChecks(red.head, ChecksState.Failed(listOf(FailedCheck("build", "broken"))))
        forge.mergedOutside(outside)
        forge.closeOutside(closed)

        assertThat(forge.checks(green.head)).isEqualTo(ChecksState.Passed)
        assertThat(forge.checks(red.head))
            .isEqualTo(ChecksState.Failed(listOf(FailedCheck("build", "broken"))))
        assertThat(forge.state(outside)).isEqualTo(PullRequestState.Merged)
        assertThat(forge.state(closed)).isEqualTo(PullRequestState.ClosedUnmerged)
        assertThat(forge.merge(outside, outside.head)).isInstanceOf(MergeResult.NotOpen::class.java)
    }

    @Test
    fun `AC-7 merge of a conflicting PR returns Conflict and the PR stays open`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest())
        forge.scriptConflict(ref)

        val result = forge.merge(ref, ref.head)

        assertThat(result).isEqualTo(MergeResult.Conflict)
        assertThat(forge.state(ref)).isEqualTo(PullRequestState.Open(ref.head))
        assertThat(forge.merges).isEmpty()
    }

    @Test
    fun `AC-8 opening a PR for a branch with an open PR returns the existing one and findOpenPullRequest finds it`() {
        val forge = FakeForge()
        val first = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/a", title = "t1", body = "b1"))

        val second = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/a", title = "t2", body = "b2"))

        assertThat(second).isEqualTo(first)
        assertThat(forge.findOpenPullRequest("feat/a")).isEqualTo(first)
        assertThat(forge.findOpenPullRequest("feat/none")).isNull()
        assertThat(forge.opened.map { it.title }).contains("t2")
        assertThat(forge.opened.filter { it.branch == "feat/a" }.map { it.title }).doesNotContain("t1")
    }

    @Test
    fun `AC-8 findOpenPullRequest ignores a merged PR`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/m"))
        forge.merge(ref, ref.head)

        assertThat(forge.findOpenPullRequest("feat/m")).isNull()
    }

    @Test
    fun `findMergedPullRequest finds a PR merged outside by branch with merge commit and head`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/m"))
        forge.mergedOutside(ref)

        assertThat(forge.findMergedPullRequest("feat/m"))
            .isEqualTo(MergedPullRequest(ref.number, "merge-${ref.number}", ref.head, null))
    }

    @Test
    fun `findMergedPullRequest returns null for open, closed and unknown branches`() {
        val forge = FakeForge()
        forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/open"))
        val closed = forge.openPullRequest(ForgeFixtures.aRequest(branch = "feat/closed"))
        forge.closeOutside(closed)

        assertThat(forge.findMergedPullRequest("feat/open")).isNull()
        assertThat(forge.findMergedPullRequest("feat/closed")).isNull()
        assertThat(forge.findMergedPullRequest("feat/none")).isNull()
    }

    @Test
    fun `failNext findMergedPullRequest throws ForgeException once`() {
        val forge = FakeForge()
        forge.failNext("findMergedPullRequest")

        assertThrows<ForgeException> { forge.findMergedPullRequest("feat/m") }
        assertThat(forge.findMergedPullRequest("feat/m")).isNull()
    }

    @Test
    fun `AC-9 failNext makes the next call throw ForgeException naming operation and PR number without the body`() {
        val forge = FakeForge()
        val ref = forge.openPullRequest(ForgeFixtures.aRequest(body = "secret-body-text"))
        forge.failNext("merge")

        val error = assertThrows<ForgeException> { forge.merge(ref, ref.head) }

        assertThat(error.message).contains("merge").contains(ref.number.toString())
        assertThat(error.message).doesNotContain("secret-body-text")
        assertThat(forge.state(ref)).isEqualTo(PullRequestState.Open(ref.head))
        assertThat(forge.merges).isEmpty()
        assertThat(forge.merge(ref, ref.head)).isInstanceOf(MergeResult.Merged::class.java)
    }

    @Test
    fun `AC-9 a failed openPullRequest records nothing and unknown PR throws UnknownPullRequestException`() {
        val forge = FakeForge()
        forge.failNext("openPullRequest")

        assertThrows<ForgeException> { forge.openPullRequest(ForgeFixtures.aRequest()) }

        assertThat(forge.opened).isEmpty()
        assertThat(forge.findOpenPullRequest("feat/43-x")).isNull()
        val error = assertThrows<UnknownPullRequestException> {
            forge.state(PullRequestRef(99, "nope", "h"))
        }
        assertThat(error.message).contains("99")
    }

    @Test
    fun `listPullRequests lists every state with the login as author and settable fork and author`() {
        val forge = FakeForge()
        val open = forge.openPullRequest(ForgeFixtures.aRequest(branch = "a"))
        val merged = forge.openPullRequest(ForgeFixtures.aRequest(branch = "b"))
        val forked = forge.openPullRequest(ForgeFixtures.aRequest(branch = "c"))
        forge.mergedOutside(merged)
        forge.setAuthor(forked, "stranger")
        forge.setFromFork(forked, true)

        val listed = forge.listPullRequests()

        assertThat(listed.map { it.branch to it.state }).containsExactly(
            "a" to PullRequestState.Open(open.head),
            "b" to PullRequestState.Merged,
            "c" to PullRequestState.Open(forked.head),
        )
        assertThat(listed.map { it.author }).containsExactly(forge.authenticatedLogin(), forge.authenticatedLogin(), "stranger")
        assertThat(listed.map { it.fromFork }).containsExactly(false, false, true)
    }

    @Test
    fun `a failed listPullRequests names the operation`() {
        val forge = FakeForge()
        forge.failNext("listPullRequests")

        val error = assertThrows<ForgeException> { forge.listPullRequests() }

        assertThat(error.message).contains("listPullRequests")
    }

    @Test
    fun `rerunChecks records heads in order and a failed call records nothing`() {
        val forge = FakeForge()
        forge.failNext("rerunChecks")

        val error = assertThrows<ForgeException> { forge.rerunChecks("h0") }
        forge.rerunChecks("h1")
        forge.rerunChecks("h2")

        assertThat(error.message).contains("rerunChecks").contains("h0")
        assertThat(forge.reruns).containsExactly("h1", "h2")
    }

    @Test
    fun `revert opens a new PR on a revert branch and records the reverted PR`() {
        val forge = FakeForge()
        val original = forge.openPullRequest(ForgeFixtures.aRequest())

        val reverted = forge.revert(original)

        assertThat(reverted.number).isNotEqualTo(original.number)
        assertThat(reverted.branch).isEqualTo("revert-${original.number}")
        assertThat(forge.reverts).containsExactly(original)
        assertThat(forge.state(reverted)).isEqualTo(PullRequestState.Open(reverted.head))
    }

    @Test
    fun `failed revert records nothing and revert of an unknown PR throws UnknownPullRequestException`() {
        val forge = FakeForge()
        val original = forge.openPullRequest(ForgeFixtures.aRequest())
        forge.failNext("revert")

        val failure = assertThrows<ForgeException> { forge.revert(original) }
        val unknown = assertThrows<UnknownPullRequestException> { forge.revert(PullRequestRef(99, "nope", "h")) }

        assertThat(failure.message).contains("revert").contains(original.number.toString())
        assertThat(unknown.message).contains("99")
        assertThat(forge.reverts).isEmpty()
        assertThat(forge.findOpenPullRequest("revert-${original.number}")).isNull()
    }
}
