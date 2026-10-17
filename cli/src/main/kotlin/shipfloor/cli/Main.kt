package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlin.system.exitProcess

/** Exit code for a command line the CLI does not understand. */
const val EXIT_USAGE = 2

class ShipfloorCommand(version: String) : CliktCommand(name = "shipfloor") {
    init {
        versionOption(version, message = { "shipfloor $it" })
    }

    override fun help(context: Context) = "Local software factory."

    override fun run() = Unit
}

/** Runs the CLI on [args] and returns its exit code; [out] and [err] receive what it prints. */
fun runCli(
    args: List<String>,
    version: String,
    out: (String) -> Unit,
    err: (String) -> Unit,
    env: Map<String, String>,
    runCommand: (List<String>) -> String? = ::runProcess,
): Int {
    val cli = CliEnvironment(env, out, err, runCommand)
    val command = ShipfloorCommand(version).withCardCommands(cli).withDoctorCommand(cli).withInitCommand(cli).withProjectCommands(cli)
    return try {
        command.parse(args)
        0
    } catch (e: CliktError) {
        command.getFormattedHelp(e)?.let { message -> if (e.printError) err(message) else out(message) }
        if (e is UsageError) EXIT_USAGE else e.statusCode
    }
}

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), BuildInfo.version, ::println, System.err::println, System.getenv()))
}
