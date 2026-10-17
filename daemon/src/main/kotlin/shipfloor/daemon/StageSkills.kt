package shipfloor.daemon

/** One ordered step of a stage skill: the [role] that acts and the [gate] that judges it. */
data class SkillStep(val role: String, val gate: String)

/** Loads the instruction skill of a pipeline stage from the daemon's classpath. */
object StageSkills {
    private val STEP = Regex("""^\s*-\s*role:\s*(\S+)\s*$""")
    private val GATE = Regex("""^\s*gate:\s*(\S+)\s*$""")

    fun load(stage: String): String {
        val path = "skills/$stage/SKILL.md"
        val stream = StageSkills::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("skill do estágio '$stage' não existe no classpath ($path)")
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    /** The spec template that ships with the refine skill; null for stages that have none. */
    fun specTemplate(stage: String): String? =
        StageSkills::class.java.classLoader.getResourceAsStream("skills/$stage/spec-template.md")
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** Ordered `steps:` of the skill's front matter; empty when the skill declares none. */
    fun steps(stage: String): List<SkillStep> {
        val lines = load(stage).lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyList()
        val header = lines.drop(1).takeWhile { it.trim() != "---" }
        val steps = mutableListOf<SkillStep>()
        var role: String? = null
        for (line in header) {
            STEP.find(line)?.let { role = it.groupValues[1] }
            GATE.find(line)?.let { match ->
                val current = checkNotNull(role) { "skill do estágio '$stage': gate sem role no front matter" }
                steps += SkillStep(current, match.groupValues[1])
                role = null
            }
        }
        return steps
    }
}
