package shipfloor.core

object DorPolicy {
    private val CRITERIA = Regex("crit[ée]rios de aceita[çc][ãa]o|acceptance criteria", RegexOption.IGNORE_CASE)

    /** Returns the required fields the ticket lacks, in enum order. */
    fun missing(required: Set<DorField>, title: String?, body: String?, labels: List<String>): List<DorField> =
        DorField.entries.filter { it in required && isMissing(it, title, body, labels) }

    private fun isMissing(field: DorField, title: String?, body: String?, labels: List<String>): Boolean =
        when (field) {
            DorField.TITLE -> title.isNullOrBlank()
            DorField.BODY -> body.isNullOrBlank()
            DorField.ACCEPTANCE_CRITERIA -> body == null || !CRITERIA.containsMatchIn(body)
            DorField.LABEL -> labels.none { it.isNotBlank() }
        }
}
