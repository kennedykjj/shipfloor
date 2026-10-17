package shipfloor.core

enum class DeliveryMode(val key: String) {
    AUTONOMOUS("autonomous"),
    PR_HANDOFF("pr"),
}

enum class ExternalEffect(val key: String) {
    IMPORT("import"),
    MILESTONE("milestone"),
    CLOSE("close"),
}

enum class Signer(val key: String) {
    OWNER("owner"),
    POLICY("policy"),
}

enum class Milestone(val key: String) {
    STARTED("started"),
    PR_OPENED("pr_opened"),
}

enum class DorField(val key: String) {
    TITLE("title"),
    BODY("body"),
    ACCEPTANCE_CRITERIA("acceptance_criteria"),
    LABEL("label"),
}

data class Conventions(
    val commitFormat: String,
    val branchFormat: String,
    val prTemplate: String = ProjectPolicies.DEFAULT_PR_TEMPLATE,
    val language: String = ProjectPolicies.DEFAULT_LANGUAGE,
)

class InvalidPolicyException(val field: String, message: String) : RuntimeException(message)

sealed interface TrackerConfig {
    val provider: String

    data object GitHub : TrackerConfig {
        override val provider: String = "github"
    }

    data class Jira(val site: String, val project: String) : TrackerConfig {
        override val provider: String = "jira"
    }
}

data class ProjectPolicies(
    val deliveryMode: DeliveryMode,
    val signers: Map<ExternalEffect, Signer>,
    val conventions: Conventions,
    val milestones: Set<Milestone> = emptySet(),
    val tracker: TrackerConfig = TrackerConfig.GitHub,
    val dor: Set<DorField> = emptySet(),
) {
    companion object {
        private val JIRA_SITE = Regex("^[a-z0-9][a-z0-9.-]{0,62}$")
        private val JIRA_PROJECT = Regex("^[A-Z][A-Z0-9_]{1,19}$")

        private const val DEFAULT_COMMIT_FORMAT = "{type}({scope}): {subject}"
        private const val DEFAULT_BRANCH_FORMAT = "feat/{id}-{slug}"
        private val PLACEHOLDER = Regex("\\{([^{}]*)}")
        const val DEFAULT_PR_TEMPLATE = ".github/pull_request_template.md"
        const val DEFAULT_LANGUAGE = "en"
        private val LANGUAGES = setOf("en", "pt-BR")
        private val COMMIT_PLACEHOLDERS = setOf("type", "scope", "subject", "id", "ticket")
        private val BRANCH_PLACEHOLDERS = setOf("id", "slug")

        val DEFAULT = ProjectPolicies(
            deliveryMode = DeliveryMode.AUTONOMOUS,
            signers = ExternalEffect.entries.associateWith { Signer.OWNER },
            conventions = Conventions(DEFAULT_COMMIT_FORMAT, DEFAULT_BRANCH_FORMAT),
        )

        fun from(raw: Map<String, Any?>?): ProjectPolicies {
            if (raw == null) return DEFAULT
            val conventions = section(raw, "conventions", "conventions")
            return ProjectPolicies(
                deliveryMode = raw["delivery_mode"]?.let { value ->
                    DeliveryMode.entries.firstOrNull { it.key == value } ?: invalid("delivery_mode", value)
                } ?: DEFAULT.deliveryMode,
                signers = readSigners(section(raw, "signers", "signers")),
                conventions = Conventions(
                    commitFormat = format(
                        conventions, "commit_format", DEFAULT_COMMIT_FORMAT, COMMIT_PLACEHOLDERS, "subject",
                    ),
                    branchFormat = format(
                        conventions, "branch_format", DEFAULT_BRANCH_FORMAT, BRANCH_PLACEHOLDERS, "id",
                    ),
                    prTemplate = text(conventions, "pr_template", DEFAULT_PR_TEMPLATE, null),
                    language = text(conventions, "language", DEFAULT_LANGUAGE, LANGUAGES),
                ),
                milestones = readMilestones(raw["milestones"]),
                tracker = readTracker(raw),
                dor = readDor(raw["dor"]),
            )
        }

        private fun readTracker(raw: Map<String, Any?>): TrackerConfig {
            val value = raw["tracker"] ?: return TrackerConfig.GitHub
            return when (value) {
                "github" -> TrackerConfig.GitHub
                "jira" -> TrackerConfig.Jira(
                    site = jiraField(raw, "jira_site", JIRA_SITE),
                    project = jiraField(raw, "jira_project", JIRA_PROJECT),
                )
                else -> invalid("tracker", value)
            }
        }

        private fun jiraField(raw: Map<String, Any?>, key: String, pattern: Regex): String {
            val value = raw[key] ?: throw InvalidPolicyException(key, "policy field $key is required when tracker is jira")
            if (value !is String || !pattern.matches(value)) invalid(key, value)
            return value
        }

        private fun readDor(raw: Any?): Set<DorField> {
            if (raw == null) return emptySet()
            if (raw !is List<*>) invalid("dor", raw)
            return raw.map { item ->
                DorField.entries.firstOrNull { it.key == item } ?: invalid("dor", item)
            }.toSet()
        }

        private fun readMilestones(raw: Any?): Set<Milestone> {
            if (raw == null) return emptySet()
            if (raw !is List<*>) invalid("milestones", raw)
            return raw.map { item ->
                Milestone.entries.firstOrNull { it.key == item } ?: invalid("milestones", item)
            }.toSet()
        }

        private fun readSigners(raw: Map<*, *>?): Map<ExternalEffect, Signer> {
            if (raw == null) return DEFAULT.signers
            raw.keys.forEach { key ->
                if (ExternalEffect.entries.none { it.key == key }) invalid("signers.$key", key)
            }
            return ExternalEffect.entries.associateWith { effect ->
                val field = "signers.${effect.key}"
                raw[effect.key]?.let { value ->
                    Signer.entries.firstOrNull { it.key == value } ?: invalid(field, value)
                } ?: Signer.OWNER
            }
        }

        private fun text(raw: Map<*, *>?, key: String, default: String, allowed: Set<String>?): String {
            val value = raw?.get(key) ?: return default
            val field = "conventions.$key"
            if (value !is String || value.isBlank()) invalid(field, value)
            if (allowed != null && value !in allowed) invalid(field, value)
            return value
        }

        private fun format(
            raw: Map<*, *>?,
            key: String,
            default: String,
            allowed: Set<String>,
            required: String,
        ): String {
            val value = raw?.get(key) ?: return default
            val field = "conventions.$key"
            if (value !is String || value.isBlank()) invalid(field, value)
            val names = PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toSet()
            if (!allowed.containsAll(names) || required !in names) invalid(field, value)
            return value
        }

        private fun section(raw: Map<String, Any?>, key: String, field: String): Map<*, *>? {
            val value = raw[key] ?: return null
            return value as? Map<*, *> ?: invalid(field, value)
        }

        private fun invalid(field: String, value: Any?): Nothing =
            throw InvalidPolicyException(field, "invalid value for policy field $field: $value")
    }
}
