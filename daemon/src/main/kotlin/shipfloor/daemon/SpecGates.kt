package shipfloor.daemon

data class SpecLayer(val name: String, val path: String, val tests: List<String>)

enum class SpecWeight { LIGHT, STANDARD, HEAVY }

enum class SpecGate { TEST_PLAN, WEIGHT }

data class SpecFinding(val gate: SpecGate, val ac: String?, val message: String)

data class SpecCheck(val weight: SpecWeight, val findings: List<SpecFinding>)

/** One row of the spec's Test plan table: the criterion, its layer and its test file ("" when absent). */
data class PlanRow(val ac: String, val layer: String, val testFile: String)

/** Gates de spec: plano de teste e peso. Função pura: o texto da spec e as camadas entram por parâmetro. */
object SpecGates {
    private val criterion = Regex("""^\s*(?:-\s+)?\*{0,2}(AC-\d+)\*{0,2}\s*[—:]""")
    private val backticked = Regex("`([^`]+)`")
    private val declaredWeight = Regex("""(?m)^\s*\*\*Weight:\*\*\s*(\S+)""")
    private val touchesUi = Regex("""\*\*Touches UI:\*\*\s*(\S+)""")
    private val acId = Regex("""AC-\d+""")

    /** Rows of the Test plan table that start with an AC id, in table order. */
    fun plan(specText: String): List<PlanRow> =
        planCells(specText)
            .filter { acId.matches(it.firstOrNull().orEmpty()) }
            .map { PlanRow(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }.trim('`').trim()) }

    fun check(specText: String, layers: List<SpecLayer>): SpecCheck {
        val findings = mutableListOf<SpecFinding>()

        val criteria = section(specText, "Product").lines()
            .mapNotNull { criterion.find(it)?.groupValues?.get(1) }
            .distinct()
        if (criteria.isEmpty()) {
            findings += SpecFinding(SpecGate.TEST_PLAN, null, "nenhum critério AC-n reconhecível na seção Product")
        }

        val rows = planCells(specText)
        criteria.forEach { ac ->
            val row = rows.firstOrNull { it.firstOrNull() == ac }
            if (row == null) {
                findings += testPlanFinding(ac, "sem linha no plano de teste")
                return@forEach
            }
            val layerName = row.getOrElse(1) { "" }
            if (layerName == "manual") return@forEach
            val path = row.getOrElse(2) { "" }.trim('`').trim()
            val layer = layers.firstOrNull { it.name == layerName }
            when {
                layer == null -> findings += testPlanFinding(ac, "camada '$layerName' não existe no projeto")
                path.isEmpty() -> findings += testPlanFinding(ac, "sem caminho de teste para a camada '$layerName'")
                layer.tests.none { globMatches(it, path) } ->
                    findings += testPlanFinding(ac, "caminho '$path' não casa com os testes da camada '$layerName'")
            }
        }

        val calculated = calculate(
            architecture = section(specText, "Architecture"),
            security = section(specText, "Security"),
            rollback = section(specText, "Rollback"),
            touchesUi = touchesUi.find(specText)?.groupValues?.get(1)?.lowercase(),
            layers = layers,
        )
        val declaredText = declaredWeight.find(header(specText))?.groupValues?.get(1)?.trim('`')?.lowercase()
        val declared = SpecWeight.values().firstOrNull { it.name.equals(declaredText, ignoreCase = true) }
        when {
            declaredText == null ->
                findings += weightFinding("peso ausente no cabeçalho; calculado: ${calculated.label()}")
            declared == null ->
                findings += weightFinding(
                    "peso declarado '$declaredText' inválido (use light, standard ou heavy); calculado: ${calculated.label()}",
                )
            declared.ordinal < calculated.ordinal ->
                findings += weightFinding(
                    "peso declarado ${declared.label()} abaixo do calculado ${calculated.label()}",
                )
        }

        return SpecCheck(calculated, findings)
    }

    private fun calculate(
        architecture: String,
        security: String,
        rollback: String,
        touchesUi: String?,
        layers: List<SpecLayer>,
    ): SpecWeight {
        val paths = backticked.findAll(architecture).map { it.groupValues[1] }.filter { '/' in it }.toList()
        val touched = layers.filter { layer -> paths.any { it.startsWith(layer.path) } }
        val risky = touched.size >= 2 || isFilled(security) || isFilled(rollback) || paths.size > 8
        return when {
            risky -> SpecWeight.HEAVY
            touched.size == 1 && paths.size <= 3 && touchesUi == "no" -> SpecWeight.LIGHT
            else -> SpecWeight.STANDARD
        }
    }

    private fun isFilled(body: String): Boolean =
        body.lines()
            .map { it.replace("_Cybersec_", "").trim().trimStart('—', '-', ' ').trim() }
            .any { it.isNotEmpty() && !it.equals("n/a", ignoreCase = true) }

    private fun globMatches(glob: String, path: String): Boolean {
        val regex = glob.split("**").joinToString(".*") { part ->
            part.split("*").joinToString("[^/]*") { Regex.escape(it) }
        }
        return Regex(regex).matches(path)
    }

    private fun planCells(specText: String): List<List<String>> =
        section(specText, "Test plan").lines()
            .filter { it.trim().startsWith("|") }
            .map { cells(it) }

    private fun section(specText: String, title: String): String {
        val lines = specText.lines()
        val start = lines.indexOfFirst { it.trim() == "## $title" }
        if (start < 0) return ""
        return lines.drop(start + 1).takeWhile { !isSectionHeader(it) }.joinToString("\n")
    }

    private fun header(specText: String): String =
        specText.lines().takeWhile { !isSectionHeader(it) }.joinToString("\n")

    private fun isSectionHeader(line: String): Boolean = line.trimStart().startsWith("## ")

    private fun cells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    private fun testPlanFinding(ac: String, reason: String) =
        SpecFinding(SpecGate.TEST_PLAN, ac, "$ac: $reason")

    private fun weightFinding(message: String) = SpecFinding(SpecGate.WEIGHT, null, message)

    private fun SpecWeight.label(): String = name.lowercase()
}
