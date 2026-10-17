package shipfloor.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import shipfloor.core.Actor
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult

/**
 * Assisted draft of the project documents missing on main. Only the owner, with a valid proof, asks for it.
 * The agent runs in a scratch directory outside the owner's repository; nothing is written to the repository
 * and nothing is persisted: the drafts are returned for the owner to review and commit.
 */
@Suppress("LongParameterList")
class ProjectDocsDraft(
    private val registry: ProjectRegistry,
    private val files: ProjectFileSource,
    private val sessions: McpSessions,
    private val runtime: Runtime,
    private val ownerProof: OwnerProof,
    private val scratchRoot: Path,
    private val mcpPort: Int,
    private val configDir: Path,
    private val model: String,
    private val log: DaemonLog = StderrDaemonLog,
) {

    fun handle(request: Request): Response = when (request) {
        is Request.ProjectDocsDraft -> draft(request)
        is Request.Rejected -> Response.Error(request.message)
        else -> Response.Error("not a docs draft command")
    }

    private fun draft(request: Request.ProjectDocsDraft): Response {
        // The proof is checked before any file is read or any session is opened.
        if (request.actor != Actor.Owner || !ownerProof.matches(request.proof)) {
            log.log("docs_draft_refused", mapOf("cmd" to CMD))
            return Response.Error(OWNER_ONLY)
        }
        val project = findProject(request.project) ?: return Response.Error("project is not registered or not unique")
        val missing = DOCS.filter { !existsOnMain(project, it) }
        if (missing.isEmpty()) {
            log.log("docs_draft", mapOf("project" to project.name, "missing" to "0"))
            return Response.DocsDraftResult(emptyList())
        }
        log.log("docs_draft_started", mapOf("project" to project.name, "docs" to missing.joinToString(",")))
        return try {
            val drafts = runSession(project, missing)
            drafts.forEach { logDoc(project, it) }
            Response.DocsDraftResult(drafts)
        } catch (e: IOException) {
            failed(project, e)
        } catch (e: RuntimeException) {
            failed(project, e)
        }
    }

    private fun failed(project: Project, e: Exception): Response {
        log.log("docs_draft_failed", mapOf("project" to project.name, "error" to e.javaClass.simpleName))
        return Response.Error("the draft session failed")
    }

    private fun findProject(name: String?): Project? {
        val projects = registry.list()
        return if (name == null) projects.singleOrNull() else projects.firstOrNull { it.name == name }
    }

    private fun existsOnMain(project: Project, doc: String): Boolean =
        try {
            files.read(Path.of(project.repo), doc) != null
        } catch (e: InvalidProjectConfigException) {
            // Too large to read still means the document exists.
            true
        }

    private fun runSession(project: Project, missing: List<String>): List<DocDraft> {
        val draftId = UUID.randomUUID().toString()
        val scratch = Files.createDirectory(
            scratchRoot.resolve(draftId),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        var token: String? = null
        var config: Path? = null
        try {
            exportMain(Path.of(project.repo), scratch)
            val opened = sessions.open(project.repo, ROLE, draftId)
            token = opened
            val written = writeMcpConfig(configDir, mcpPort, opened)
            config = written
            val result = runtime.launch(SessionRequest(ROLE, scratch, model, written, prompt(draftId, missing)))
            return missing.map { doc -> collect(scratch, doc, result) }
        } finally {
            config?.let { runCatching { Files.deleteIfExists(it) } }
            token?.let { sessions.close(it) }
            runCatching { scratch.toFile().deleteRecursively() }
        }
    }

    private fun collect(scratch: Path, doc: String, result: SessionResult): DocDraft {
        val file = scratch.resolve(DRAFT_DIR).resolve(doc)
        val text = readDraft(file)
        if (text != null) return DocDraft(doc, DRAFTED, text = text)
        val reason = when {
            result is SessionResult.Hung -> "hung"
            result is SessionResult.Exited && result.exitCode != 0 -> "exit_code"
            Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> "too_large"
            else -> "not_written"
        }
        return DocDraft(doc, FAILED, reason = reason)
    }

    private fun readDraft(file: Path): String? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(file) > ProjectFileSource.MAX_BYTES) return null
        return Files.readString(file).takeIf { it.isNotBlank() }
    }

    private fun logDoc(project: Project, draft: DocDraft) {
        val fields = buildMap {
            put("project", project.name)
            put("doc", draft.doc)
            put("result", draft.status)
            draft.reason?.let { put("reason", it) }
        }
        log.log("docs_draft_doc", fields)
    }

    private fun prompt(draftId: String, missing: List<String>): String = buildString {
        append(StageSkills.load(ROLE).trimEnd())
        append("\n\n## Esta sessão\n\n")
        append("- Id desta sessão: `").append(draftId).append("` (use este id em todas as ferramentas que pedem `cardId`).\n")
        append("- Documentos a rascunhar: ").append(missing.joinToString(", ") { "`$DRAFT_DIR/$it`" }).append(".\n")
    }

    /** Exports the tree of `main` into [scratch] for context only; links and any `docs-draft` entry are dropped. */
    private fun exportMain(repo: Path, scratch: Path) {
        val archive = Files.createTempFile("shipfloor-docs-archive", ".tar")
        try {
            run(listOf("git", "-C", repo.toString(), "-c", "core.fsmonitor=false", "archive", "--format=tar", "-o", archive.toString(), "main"))
            run(listOf("tar", "-xf", archive.toString(), "-C", scratch.toString()))
        } finally {
            Files.deleteIfExists(archive)
        }
        Files.walk(scratch).use { paths ->
            paths.filter { Files.isSymbolicLink(it) }.toList().forEach { Files.deleteIfExists(it) }
        }
        scratch.resolve(DRAFT_DIR).toFile().deleteRecursively()
    }

    private fun run(command: List<String>) {
        val process = try {
            ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (e: IOException) {
            throw IllegalStateException("cannot run ${command.first()} to export main", e)
        }
        try {
            check(process.waitFor(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "${command.first()} timed out exporting main" }
            check(process.exitValue() == 0) { "${command.first()} failed exporting main" }
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        const val ROLE = "docs-draft"
        const val DRAFT_DIR = "docs-draft"
        const val OWNER_ONLY = "only the owner, with a valid proof, asks for document drafts"
        val DOCS = listOf("PRODUCT.md", "ARCHITECTURE.md", "DESIGN.md")
        private const val CMD = "project.docs.draft"
        private const val DRAFTED = "drafted"
        private const val FAILED = "failed"
        private const val EXPORT_TIMEOUT_SECONDS = 60L
    }
}
