package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private fun projectField(project: JsonObject, name: String): String =
    (project[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun projectLine(project: JsonElement): String {
    val obj = project as? JsonObject ?: return project.toString()
    return "${projectField(obj, "name")}  ${projectField(obj, "repo")}"
}

/** The path the daemon registers; a path that does not resolve goes as typed so the daemon refuses it. */
internal fun canonicalPath(path: String): String =
    try {
        Path.of(path).toRealPath().toString()
    } catch (e: IOException) {
        path
    } catch (e: InvalidPathException) {
        path
    }

private fun CliEnvironment.registeredProjects(): List<JsonElement> =
    (call(buildJsonObject { put("cmd", "project.list") }) as? JsonArray ?: fail("resposta inesperada do daemon")).toList()

class ProjectGroup : CliktCommand(name = "project") {
    override fun help(context: Context) = "Register and list projects."

    override fun run() = Unit
}

class ProjectAdd(private val cli: CliEnvironment) : CliktCommand(name = "add") {
    private val path by argument(help = "Path of the project repository")

    override fun help(context: Context) = "Register a project (the daemon reads factory.yml from main)."

    override fun run() {
        cli.out(cli.registerProject(canonicalPath(path)))
    }
}

/** Registers the project at [canonical] unless the daemon already has it; returns the project line. */
internal fun CliEnvironment.registerProject(canonical: String): String {
    val existing = registeredProjects().firstOrNull { (it as? JsonObject)?.let { p -> projectField(p, "repo") } == canonical }
    if (existing != null) return projectLine(existing)
    val token = ownerToken()
    val request = buildJsonObject {
        put("cmd", "project.add")
        put("actor", "owner")
        put("path", canonical)
        put("proof", token)
    }
    return projectLine(call(request))
}

class ProjectList(private val cli: CliEnvironment) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List registered projects."

    override fun run() {
        cli.registeredProjects().forEach { cli.out(projectLine(it)) }
    }
}

fun ShipfloorCommand.withProjectCommands(cli: CliEnvironment): ShipfloorCommand =
    subcommands(ProjectGroup().subcommands(ProjectAdd(cli), ProjectList(cli))) as ShipfloorCommand
