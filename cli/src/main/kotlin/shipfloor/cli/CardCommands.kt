package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import shipfloor.core.Column

private const val DAEMON_DOWN_MESSAGE = "daemon não está rodando; inicie com `./gradlew :daemon:run`"
const val EXIT_FAILURE = 1

/** What every command needs: where the daemon is and where to write. */
class CliEnvironment(
    val env: Map<String, String>,
    val out: (String) -> Unit,
    val err: (String) -> Unit,
    val runCommand: (List<String>) -> String? = ::runProcess,
) {
    /** Reports [message] on stderr and ends the command with exit code 1. */
    fun fail(message: String): Nothing {
        err(message)
        throw ProgramResult(EXIT_FAILURE)
    }

    fun location(): SocketLocation =
        resolveSocketLocation(env) ?: fail("XDG_RUNTIME_DIR não está definido; a CLI precisa dele para achar o socket do daemon")

    fun client(): DaemonClient = DaemonClient(location().socket)

    /** The owner token; fails before any connection is opened when it cannot be read. */
    fun ownerToken(): String {
        val path = location().token
        return readOwnerToken(path)
            ?: fail("token do dono ausente, ilegível ou vazio: $path; inicie o daemon (ele gera o arquivo)")
    }

    /** Sends [request] and returns the daemon's result, or fails with the right message. */
    fun call(request: JsonObject): JsonElement = callFull(request).result

    /** Like [call], keeping the extra fields of the reply. */
    fun callFull(request: JsonObject): DaemonResult.Ok =
        when (val result = client().request(request)) {
            is DaemonResult.Ok -> result
            is DaemonResult.DaemonError -> fail(result.message)
            DaemonResult.Unavailable -> fail(DAEMON_DOWN_MESSAGE)
        }
}

private fun cardLine(card: JsonElement): String {
    val obj = card as? JsonObject ?: return card.toString()
    fun field(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val blocked = (obj["blocked"] as? JsonPrimitive)?.booleanOrNull == true
    val line = "${field("id")}  ${field("column")}  ${field("title")}"
    return if (blocked) "$line  BLOQUEADO" else line
}

private fun parseColumn(text: String): Column? {
    val normalized = text.trim().uppercase().replace('-', '_')
    return Column.entries.firstOrNull { it.name == normalized }
}

class CardGroup : CliktCommand(name = "card") {
    override fun help(context: Context) = "Create, list, move and show cards."

    override fun run() = Unit
}

class CardCreate(private val cli: CliEnvironment) : CliktCommand(name = "create") {
    private val title by argument(help = "Card title")

    override fun help(context: Context) = "Create a card in the inbox."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.create")
            put("actor", "owner")
            put("title", title)
            put("proof", token)
        }
        cli.out(cardLine(cli.call(request)))
    }
}

class CardList(private val cli: CliEnvironment) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List all cards."

    override fun run() {
        val cards = cli.call(buildJsonObject { put("cmd", "card.list") }) as? JsonArray
            ?: cli.fail("resposta inesperada do daemon")
        cards.forEach { cli.out(cardLine(it)) }
    }
}

class CardMove(private val cli: CliEnvironment) : CliktCommand(name = "move") {
    private val id by argument(help = "Card id")
    private val column by argument(help = "Target column, e.g. ready-for-dev").convert { text ->
        parseColumn(text) ?: fail("coluna desconhecida '$text'; válidas: ${Column.entries.joinToString(", ") { it.name }}")
    }

    override fun help(context: Context) = "Move a card to a column."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.move")
            put("actor", "owner")
            put("id", id)
            put("to", column.name)
            put("proof", token)
        }
        cli.out(cardLine(cli.call(request)))
    }
}

class CardApprove(private val cli: CliEnvironment) : CliktCommand(name = "approve") {
    private val id by argument(help = "Card id")

    override fun help(context: Context) = "Approve a card waiting for the owner."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.approve")
            put("actor", "owner")
            put("id", id)
            put("proof", token)
        }
        cli.out(cardLine(cli.call(request)))
    }
}

class CardUnblock(private val cli: CliEnvironment) : CliktCommand(name = "unblock") {
    private val id by argument(help = "Card id")

    override fun help(context: Context) = "Unblock a blocked card; one blocked mid-work returns to the queue of its stage."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.unblock")
            put("actor", "owner")
            put("id", id)
            put("proof", token)
        }
        cli.out(cardLine(cli.call(request)))
    }
}

class CardRequestChange(private val cli: CliEnvironment) : CliktCommand(name = "request-change") {
    private val id by argument(help = "Card id")
    private val note by option("--note", help = "What must change").required()

    override fun help(context: Context) = "Ask for changes on a card."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.request-change")
            put("actor", "owner")
            put("id", id)
            put("note", note)
            put("proof", token)
        }
        cli.out(cardLine(cli.call(request)))
    }
}

class CardRebuild(private val cli: CliEnvironment) : CliktCommand(name = "rebuild") {
    private val project by option("--project", help = "Only rebuild cards of this project")

    override fun help(context: Context) = "Recreate cards lost from the database out of branches and pull requests."

    override fun run() {
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.rebuild")
            put("actor", "owner")
            project?.let { put("project", it) }
            put("proof", token)
        }
        val cards = cli.call(request) as? JsonArray ?: cli.fail("resposta inesperada do daemon")
        cards.forEach { cli.out(cardLine(it)) }
    }
}

class CardImportBoard(private val cli: CliEnvironment) : CliktCommand(name = "import-board") {
    private val file by argument(help = "TSV file with lines <issue><TAB><column>")
    private val project by option("--project", help = "Project the cards belong to")

    override fun help(context: Context) = "Import the cards of the V0 board into the mapped columns."

    override fun run() {
        val text = try {
            Files.readString(Path.of(file))
        } catch (e: IOException) {
            cli.fail("não foi possível ler o arquivo: $file")
        } catch (e: InvalidPathException) {
            cli.fail("não foi possível ler o arquivo: $file")
        }
        val rows = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            val id = parts[0].trim().removePrefix("#")
            val column = parts.getOrNull(1)?.trim().orEmpty()
            if (id.isEmpty() || column.isEmpty()) null else id to column
        }
        val token = cli.ownerToken()
        val request = buildJsonObject {
            put("cmd", "card.import_board")
            put("actor", "owner")
            project?.let { put("project", it) }
            put(
                "rows",
                JsonArray(
                    rows.map { (id, column) ->
                        buildJsonObject {
                            put("id", id)
                            put("column", column)
                        }
                    },
                ),
            )
            put("proof", token)
        }
        val reply = cli.callFull(request)
        val cards = reply.result as? JsonArray ?: cli.fail("resposta inesperada do daemon")
        cards.forEach { cli.out(cardLine(it)) }
        (reply.ignored as? JsonArray).orEmpty().forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            cli.out("ignorada: #${field("id")} (${field("reason")})")
        }
    }
}

class CardShow(private val cli: CliEnvironment) : CliktCommand(name = "show") {
    private val id by argument(help = "Card id")

    override fun help(context: Context) = "Show one card."

    override fun run() {
        cli.out(cardLine(cli.call(buildJsonObject { put("cmd", "card.show"); put("id", id) })))
    }
}

class EventsCommand(private val cli: CliEnvironment) : CliktCommand(name = "events") {
    private val after by option("--after", help = "Replay events after this sequence number").long()
    private val count by option("--count", help = "Exit after this many events").int()

    override fun help(context: Context) = "Print the event stream as JSON lines."

    override fun run() {
        val limit = count
        if (limit != null && limit < 1) cli.fail("--count precisa ser pelo menos 1")
        val request = buildJsonObject {
            put("cmd", "events.subscribe")
            after?.let { put("after", it) }
        }
        var seen = 0
        val result = cli.client().stream(request) { line ->
            cli.out(line)
            seen++
            limit == null || seen < limit
        }
        when (result) {
            is StreamResult.Rejected -> cli.fail(result.message)
            is StreamResult.Ended -> when (val end = result.end) {
                StreamEnd.Stopped -> Unit
                is StreamEnd.Failed -> cli.fail(end.message)
                StreamEnd.Closed -> cli.fail("o daemon encerrou o stream de eventos")
                StreamEnd.Unavailable -> cli.fail(DAEMON_DOWN_MESSAGE)
            }
        }
    }
}

/** Registers the card group and the events command on [root]. */
fun ShipfloorCommand.withCardCommands(cli: CliEnvironment): ShipfloorCommand =
    subcommands(
        CardGroup().subcommands(CardCreate(cli), CardList(cli), CardMove(cli), CardApprove(cli), CardUnblock(cli), CardRequestChange(cli), CardRebuild(cli), CardImportBoard(cli), CardShow(cli)),
        EventsCommand(cli),
        AuditGroup().subcommands(AuditExport(cli)),
    ) as ShipfloorCommand
