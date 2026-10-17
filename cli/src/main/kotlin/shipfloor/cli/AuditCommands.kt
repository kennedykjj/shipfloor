package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class AuditGroup : CliktCommand(name = "audit") {
    override fun help(context: Context) = "Read the audit trail of a card."

    override fun run() = Unit
}

class AuditExport(private val cli: CliEnvironment) : CliktCommand(name = "export") {
    private val id by argument(help = "Card id")
    private val format by option("--format", help = "Document format: json or csv").default("json")

    override fun help(context: Context) = "Print the audit trail of a card as JSON or CSV."

    override fun run() {
        val request = buildJsonObject {
            put("cmd", "audit.export")
            put("id", id)
            put("format", format)
        }
        val text = (cli.call(request) as? JsonPrimitive)?.contentOrNull ?: cli.fail("resposta inesperada do daemon")
        cli.out(text.trimEnd('\n'))
    }
}
