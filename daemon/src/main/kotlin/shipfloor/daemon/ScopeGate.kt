package shipfloor.daemon

sealed interface ScopeVerdict {
    object Pass : ScopeVerdict

    /** Both lists hold the offending paths as they came in the diff, sorted. */
    data class Violations(val forbidden: List<String>, val outsideLayers: List<String>) : ScopeVerdict
}

/** Decides whether a diff stays inside what a stage may touch. Pure: no IO, no clock. */
object ScopeGate {

    // Fixed in code on purpose: the judge cannot depend on a list the agent could edit.
    private val PROTECTED = listOf(".shipfloor", ".github", ".claude", "CLAUDE.md").map(::segments)
    private val SHARED = listOf("gradle/libs.versions.toml", "docs/superpowers").map(::segments)

    fun check(project: Project, changed: List<String>, stageLayers: Set<String>): ScopeVerdict {
        val allowedPrefixes = SHARED + project.layers.filter { it.name in stageLayers }.map { segments(it.path) }
        val forbidden = mutableListOf<String>()
        val outside = mutableListOf<String>()
        for (path in changed.distinct()) {
            val parts = segments(path)
            when {
                isProtected(project, path) -> forbidden += path
                allowedPrefixes.none { startsWith(parts, it) } -> outside += path
            }
        }
        if (forbidden.isEmpty() && outside.isEmpty()) return ScopeVerdict.Pass
        return ScopeVerdict.Violations(forbidden.sorted(), outside.sorted())
    }

    /** True when the path is unsafe or sits under a protected prefix or one of the project's `forbidden` ones. */
    fun isProtected(project: Project, path: String): Boolean {
        val parts = segments(path)
        return isUnsafe(path, parts) || (PROTECTED + project.forbidden.map(::segments)).any { startsWith(parts, it) }
    }

    private fun segments(path: String): List<String> = path.split("/").filter { it.isNotEmpty() && it != "." }

    private fun isUnsafe(path: String, parts: List<String>): Boolean =
        path.startsWith("/") || parts.isEmpty() || ".." in parts || path.any { it.isISOControl() }

    private fun startsWith(parts: List<String>, prefix: List<String>): Boolean =
        prefix.isNotEmpty() && parts.size >= prefix.size && parts.subList(0, prefix.size) == prefix
}
