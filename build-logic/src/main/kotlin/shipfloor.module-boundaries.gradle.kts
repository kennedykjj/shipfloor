// Enforces the module dependencies written in ARCHITECTURE.md. Applied once, on the root project.
// A module may depend only on the modules listed for it; anything else fails the build at configuration time.
// Changing this table is an architecture decision: it goes with ARCHITECTURE.md in the same change, by hand.

val allowed: Map<String, Set<String>> = mapOf(
    ":core" to emptySet(),
    ":store" to setOf(":core"),
    ":ports" to setOf(":core"),
    ":adapters" to setOf(":core", ":ports"),
    ":daemon" to setOf(":core", ":store", ":ports", ":adapters"),
    ":cli" to setOf(":core"),
    ":app" to setOf(":core"),
    ":e2e" to setOf(":core", ":store", ":ports", ":adapters", ":daemon", ":cli"),
)

gradle.projectsEvaluated {
    val violations = rootProject.subprojects.flatMap { module ->
        val permitted = allowed[module.path]
            ?: return@flatMap listOf("${module.path} is not in the module table of build-logic")
        module.configurations
            .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>() }
            .map { dependency -> dependency.path }
            .filter { target -> target != module.path && target !in permitted }
            .distinct()
            .map { target -> "${module.path} -> $target" }
    }
    if (violations.isNotEmpty()) {
        throw GradleException(
            "Module dependencies not allowed by ARCHITECTURE.md:\n" + violations.joinToString("\n") { "  $it" },
        )
    }
}
