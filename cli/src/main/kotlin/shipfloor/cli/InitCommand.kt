package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default

private const val FACTORY_YML = ".shipfloor/factory.yml"
private const val MAIN_BRANCH = "main"
private val CONTEXT_DOCS = listOf("PRODUCT.md", "ARCHITECTURE.md", "DESIGN.md")

class InitCommand(private val cli: CliEnvironment) : CliktCommand(name = "init") {
    private val path by argument(help = "Path of the project repository").default(".")

    override fun help(context: Context) = "Register a project and report how ready its context documents are."

    override fun run() {
        val canonical = canonicalPath(path)
        if (cli.runCommand(listOf("git", "-C", canonical, "rev-parse", "--git-dir")) == null) {
            cli.fail("$canonical não é um repositório git; rode `git init` nele ou informe o caminho certo")
        }
        if (cli.showFromMain(canonical, FACTORY_YML) == null) {
            cli.fail("$FACTORY_YML não existe no branch $MAIN_BRANCH de $canonical; ele precisa estar commitado em $MAIN_BRANCH")
        }
        cli.out(cli.registerProject(canonical))
        val present = CONTEXT_DOCS.associateWith { cli.showFromMain(canonical, it) != null }
        cli.out("prontidão ${present.values.count { it }}/${CONTEXT_DOCS.size}")
        present.forEach { (doc, found) -> cli.out(if (found) "  $doc  ok" else "  $doc  ausente") }
    }
}

/** The content of [file] on main, or null when git has nothing there; the working tree is never read. */
private fun CliEnvironment.showFromMain(repo: String, file: String): String? =
    runCommand(listOf("git", "-C", repo, "show", "$MAIN_BRANCH:$file"))?.takeIf { it.isNotBlank() }

fun ShipfloorCommand.withInitCommand(cli: CliEnvironment): ShipfloorCommand = subcommands(InitCommand(cli)) as ShipfloorCommand
