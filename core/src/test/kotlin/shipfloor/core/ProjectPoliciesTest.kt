package shipfloor.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectPoliciesTest {

    private val defaults = ProjectPolicies.DEFAULT

    @Test
    fun `AC-1 absent policies yield the defaults`() {
        assertThat(ProjectPolicies.from(null)).isEqualTo(defaults)
        assertThat(ProjectPolicies.from(emptyMap())).isEqualTo(defaults)
        assertThat(defaults.deliveryMode).isEqualTo(DeliveryMode.AUTONOMOUS)
        assertThat(defaults.signers).isEqualTo(ExternalEffect.entries.associateWith { Signer.OWNER })
        assertThat(defaults.conventions.commitFormat).isEqualTo("{type}({scope}): {subject}")
        assertThat(defaults.conventions.branchFormat).isEqualTo("feat/{id}-{slug}")
    }

    @Test
    fun `AC-2 delivery mode is exposed as a typed enum`() {
        assertThat(ProjectPolicies.from(mapOf("delivery_mode" to "autonomous")).deliveryMode)
            .isEqualTo(DeliveryMode.AUTONOMOUS)
        assertThat(ProjectPolicies.from(mapOf("delivery_mode" to "pr")).deliveryMode)
            .isEqualTo(DeliveryMode.PR_HANDOFF)
    }

    @Test
    fun `AC-3 signer is exposed per external effect`() {
        val policies = ProjectPolicies.from(
            mapOf("signers" to mapOf("import" to "owner", "milestone" to "policy", "close" to "policy")),
        )

        assertThat(policies.signers[ExternalEffect.IMPORT]).isEqualTo(Signer.OWNER)
        assertThat(policies.signers[ExternalEffect.MILESTONE]).isEqualTo(Signer.POLICY)
        assertThat(policies.signers[ExternalEffect.CLOSE]).isEqualTo(Signer.POLICY)
    }

    @Test
    fun `AC-4 commit and branch formats are exposed`() {
        val policies = ProjectPolicies.from(
            mapOf(
                "conventions" to mapOf(
                    "commit_format" to "[{id}] {subject}",
                    "branch_format" to "work/{id}",
                ),
            ),
        )

        assertThat(policies.conventions.commitFormat).isEqualTo("[{id}] {subject}")
        assertThat(policies.conventions.branchFormat).isEqualTo("work/{id}")
    }

    @Test
    fun `AC-5 invalid delivery mode names the field`() {
        val ex = assertThrows<InvalidPolicyException> {
            ProjectPolicies.from(mapOf("delivery_mode" to "yolo"))
        }
        assertThat(ex.field).isEqualTo("delivery_mode")
        assertThat(ex.message).contains("delivery_mode").contains("yolo")
    }

    @Test
    fun `AC-5 invalid signer and unknown effect name the field`() {
        val badSigner = assertThrows<InvalidPolicyException> {
            ProjectPolicies.from(mapOf("signers" to mapOf("close" to "nobody")))
        }
        assertThat(badSigner.field).isEqualTo("signers.close")
        assertThat(badSigner.message).contains("signers.close")

        val unknown = assertThrows<InvalidPolicyException> {
            ProjectPolicies.from(mapOf("signers" to mapOf("merge" to "owner")))
        }
        assertThat(unknown.field).isEqualTo("signers.merge")
    }

    @Test
    fun `AC-5 invalid formats name the field`() {
        val invalidCommits = listOf("", "{type}: text", "{subject} {nope}")
        invalidCommits.forEach { value ->
            val ex = assertThrows<InvalidPolicyException> {
                ProjectPolicies.from(mapOf("conventions" to mapOf("commit_format" to value)))
            }
            assertThat(ex.field).isEqualTo("conventions.commit_format")
        }
        val invalidBranches = listOf("", "work/{slug}", "{id}-{nope}")
        invalidBranches.forEach { value ->
            val ex = assertThrows<InvalidPolicyException> {
                ProjectPolicies.from(mapOf("conventions" to mapOf("branch_format" to value)))
            }
            assertThat(ex.field).isEqualTo("conventions.branch_format")
        }
    }

    // Issue #90: Conventions(commitFormat, branchFormat, prTemplate, language). Read by getter name so this file
    // compiles before the two new properties exist.
    private fun convention(policies: ProjectPolicies, property: String): Any? =
        policies.conventions.javaClass.getMethod("get$property").invoke(policies.conventions)

    @Test
    fun `AC-5 pr_template and language default to the factory values and accept configured ones`() {
        assertThat(convention(defaults, "PrTemplate")).isEqualTo(".github/pull_request_template.md")
        assertThat(convention(defaults, "Language")).isEqualTo("en")

        val configured = ProjectPolicies.from(
            mapOf("conventions" to mapOf("pr_template" to "docs/pr.md", "language" to "pt-BR", "commit_format" to "[{ticket}] {subject}")),
        )

        assertThat(convention(configured, "PrTemplate")).isEqualTo("docs/pr.md")
        assertThat(convention(configured, "Language")).isEqualTo("pt-BR")
        assertThat(configured.conventions.commitFormat).isEqualTo("[{ticket}] {subject}")
    }

    @Test
    fun `AC-5 an invalid pr_template or language names the field and an unknown placeholder still does`() {
        listOf(
            "pr_template" to "",
            "language" to "fr",
            "language" to "",
            "commit_format" to "{ticket} {nope} {subject}",
        ).forEach { (key, value) ->
            val ex = assertThrows<InvalidPolicyException> {
                ProjectPolicies.from(mapOf("conventions" to mapOf(key to value)))
            }
            assertThat(ex.field).describedAs("$key=$value").isEqualTo("conventions.$key")
        }
    }

    @Test
    fun `AC-6 milestones list turns milestones on and no list leaves all off`() {
        assertThat(milestonesOf(defaults)).isEmpty()
        assertThat(milestonesOf(ProjectPolicies.from(mapOf("delivery_mode" to "pr")))).isEmpty()
        assertThat(milestonesOf(ProjectPolicies.from(mapOf("milestones" to listOf("started", "pr_opened")))))
            .containsExactlyInAnyOrder("STARTED", "PR_OPENED")
        assertThat(milestonesOf(ProjectPolicies.from(mapOf("milestones" to listOf("pr_opened")))))
            .containsExactly("PR_OPENED")
        assertThat(milestonesOf(ProjectPolicies.from(mapOf("milestones" to emptyList<String>())))).isEmpty()
    }

    /** Reads the new field by name so its absence fails an assertion, not the compilation. */
    private fun milestonesOf(policies: ProjectPolicies): Set<String> {
        val getter = policies.javaClass.methods.firstOrNull { it.name == "getMilestones" }
        assertThat(getter).describedAs("ProjectPolicies.milestones exists").isNotNull()
        return (requireNotNull(getter).invoke(policies) as Set<*>).map { (it as Enum<*>).name }.toSet()
    }

    @Test
    fun `AC-6 milestones that are not a list or name an unknown milestone are rejected`() {
        listOf<Any>("started", mapOf("started" to true), listOf("started", "merged")).forEach { value ->
            val ex = assertThrows<InvalidPolicyException> {
                ProjectPolicies.from(mapOf("milestones" to value))
            }
            assertThat(ex.field).describedAs("value $value").isEqualTo("milestones")
        }
    }

    /** Reads the DoR field by name so its absence fails an assertion, not the compilation. */
    private fun dorOf(policies: ProjectPolicies): Set<String> {
        val getter = policies.javaClass.methods.firstOrNull { it.name == "getDor" }
        assertThat(getter).describedAs("ProjectPolicies.dor exists").isNotNull()
        return (requireNotNull(getter).invoke(policies) as Set<*>).map { (it as Enum<*>).name }.toSet()
    }

    @Test
    fun `AC-3 of #86 the DoR policy is off by default and a dor list turns on the named fields`() {
        assertThat(dorOf(defaults)).isEmpty()
        assertThat(dorOf(ProjectPolicies.from(mapOf("delivery_mode" to "pr")))).isEmpty()
        assertThat(dorOf(ProjectPolicies.from(mapOf("dor" to emptyList<String>())))).isEmpty()
        assertThat(dorOf(ProjectPolicies.from(mapOf("dor" to listOf("title", "acceptance_criteria")))))
            .containsExactlyInAnyOrder("TITLE", "ACCEPTANCE_CRITERIA")
        listOf<Any>("title", listOf("title", "owner")).forEach { value ->
            val ex = assertThrows<InvalidPolicyException> { ProjectPolicies.from(mapOf("dor" to value)) }
            assertThat(ex.field).describedAs("value $value").startsWith("dor")
        }
    }

    @Test
    fun `tracker defaults to github and jira carries validated site and project`() {
        assertThat(defaults.tracker).isEqualTo(TrackerConfig.GitHub)
        assertThat(ProjectPolicies.from(mapOf("tracker" to "github")).tracker.provider).isEqualTo("github")
        val jira = ProjectPolicies.from(
            mapOf("tracker" to "jira", "jira_site" to "acme.atlassian.net", "jira_project" to "PROJ"),
        ).tracker
        assertThat(jira).isEqualTo(TrackerConfig.Jira("acme.atlassian.net", "PROJ"))
        assertThat(jira.provider).isEqualTo("jira")
    }

    @Test
    fun `invalid tracker settings name the field`() {
        val site = "acme.atlassian.net"
        listOf(
            mapOf<String, Any?>("tracker" to "gitlab") to "tracker",
            mapOf<String, Any?>("tracker" to "jira", "jira_project" to "PROJ") to "jira_site",
            mapOf<String, Any?>("tracker" to "jira", "jira_site" to site) to "jira_project",
            mapOf<String, Any?>("tracker" to "jira", "jira_site" to "Bad Site", "jira_project" to "PROJ") to "jira_site",
            mapOf<String, Any?>("tracker" to "jira", "jira_site" to site, "jira_project" to "proj") to "jira_project",
        ).forEach { (raw, field) ->
            assertThat(assertThrows<InvalidPolicyException> { ProjectPolicies.from(raw) }.field).isEqualTo(field)
        }
    }

    @Test
    fun `AC-6 partial policies keep present fields and default the rest`() {
        val policies = ProjectPolicies.from(
            mapOf(
                "delivery_mode" to "pr",
                "signers" to mapOf("milestone" to "policy"),
                "conventions" to mapOf("branch_format" to "work/{id}"),
            ),
        )

        assertThat(policies.deliveryMode).isEqualTo(DeliveryMode.PR_HANDOFF)
        assertThat(policies.signers).isEqualTo(
            mapOf(
                ExternalEffect.IMPORT to Signer.OWNER,
                ExternalEffect.MILESTONE to Signer.POLICY,
                ExternalEffect.CLOSE to Signer.OWNER,
            ),
        )
        assertThat(policies.conventions.branchFormat).isEqualTo("work/{id}")
        assertThat(policies.conventions.commitFormat).isEqualTo(defaults.conventions.commitFormat)
    }
}
