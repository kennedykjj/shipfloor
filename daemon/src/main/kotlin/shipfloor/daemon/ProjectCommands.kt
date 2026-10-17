package shipfloor.daemon

import java.nio.file.InvalidPathException
import java.nio.file.Path
import shipfloor.core.Actor

/** Applies project commands to the registry. Only the owner, with a valid proof, registers a project. */
class ProjectCommands(
    private val registry: ProjectRegistry,
    private val ownerProof: OwnerProof,
    private val log: DaemonLog = StderrDaemonLog,
) {

    fun handle(request: Request): Response = when (request) {
        is Request.ProjectAdd -> add(request)
        Request.ProjectList -> Response.ProjectsResult(registry.list())
        else -> Response.Error("not a project command")
    }

    private fun add(request: Request.ProjectAdd): Response {
        // The proof is checked before any file is read or git is run.
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("project_command_refused", mapOf("cmd" to "project.add"))
            return Response.Error("only the owner, with a valid proof, registers projects")
        }
        val repo = try {
            Path.of(request.path)
        } catch (e: InvalidPathException) {
            return Response.Error("invalid path")
        }
        return try {
            val project = registry.register(repo)
            log.log("project_command", mapOf("cmd" to "project.add", "project" to project.name, "repo" to project.repo))
            Response.ProjectResult(project)
        } catch (e: InvalidProjectConfigException) {
            refused(e)
        } catch (e: ProjectNotFoundException) {
            refused(e)
        } catch (e: ProjectAlreadyRegisteredException) {
            refused(e)
        }
    }

    private fun refused(e: RuntimeException): Response {
        log.log("project_command_refused", mapOf("cmd" to "project.add", "reason" to e.javaClass.simpleName))
        return Response.Error(e.message ?: "project refused")
    }
}
