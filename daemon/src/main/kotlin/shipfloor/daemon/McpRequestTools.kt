package shipfloor.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID
import shipfloor.core.AgentRequestRefusedException
import shipfloor.core.AgentRequests
import shipfloor.core.Card
import shipfloor.core.EventId
import shipfloor.store.CardEventStore

data class ReportedView(val eventId: String)

data class WriteView(val path: String, val bytes: Int)

/** Tools through which an agent reports and writes; each one acts only on the session's own card. */
class McpRequestTools(
    private val session: McpSession,
    private val store: CardEventStore,
    private val recorder: EventRecorder,
    private val worktrees: WorktreeManager,
    private val registry: ProjectRegistry,
    private val clock: Clock,
    private val log: DaemonLog = StderrDaemonLog,
    private val docsScratchRoot: Path? = null,
) {

    fun completionReport(cardId: String, summary: String): ReportedView {
        val card = requireOwnCard("completion_report", cardId)
        val event = refusing {
            AgentRequests.reportCompletion(cardId, card, session.role, summary, newId(), clock.instant())
        }
        recorder.record(cardId, card, event)
        log.log("mcp_tool", mapOf("tool" to "completion_report", "card" to cardId))
        return ReportedView(event.id.value)
    }

    fun fileWriteRequest(cardId: String, path: String, content: String): WriteView {
        if (session.role == DOCS_DRAFT_ROLE) return docsDraftWrite(cardId, path, content)
        val card = requireOwnCard(FILE_WRITE, cardId)
        val project = project()
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTENT_BYTES) {
            throw refused(FILE_WRITE, cardId, "content above $MAX_CONTENT_BYTES bytes refused for card $cardId")
        }
        if (path.length > MAX_PATH_CHARS) {
            throw refused(FILE_WRITE, cardId, "path above $MAX_PATH_CHARS characters refused for card $cardId")
        }
        val relative = normalize(project, cardId, path)
        val event = refusing {
            AgentRequests.requestWrite(cardId, card, session.role, relative, bytes.size, newId(), clock.instant())
        }
        val root = worktrees.existing(project, cardId)
            ?: throw refused(FILE_WRITE, cardId, "card $cardId has no worktree")
        writeInside(root, relative, bytes, cardId, FILE_WRITE)
        recorder.record(cardId, card, event)
        log.log("mcp_tool", mapOf("tool" to FILE_WRITE, "card" to cardId, "path" to relative))
        return WriteView(relative, bytes.size)
    }

    /** A docs-draft session has no card: it writes only the three fixed draft files of its own scratch directory. */
    private fun docsDraftWrite(draftId: String, path: String, content: String): WriteView {
        val root = docsScratchRoot?.resolve(session.cardId)
        if (draftId != session.cardId || !DRAFT_ID.matches(draftId) || root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw refused(FILE_WRITE, draftId, "draft session $draftId has no scratch directory")
        }
        val relative = path.split("/").filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (relative !in DRAFT_PATHS) throw refused(FILE_WRITE, draftId, "path is not writable for draft $draftId")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > ProjectFileSource.MAX_BYTES) {
            throw refused(FILE_WRITE, draftId, "content above ${ProjectFileSource.MAX_BYTES} bytes refused for draft $draftId")
        }
        writeInside(root, relative, bytes, draftId, FILE_WRITE)
        log.log("mcp_tool", mapOf("tool" to FILE_WRITE, "draft" to draftId, "path" to relative))
        return WriteView(relative, bytes.size)
    }

    private fun normalize(project: Project, cardId: String, path: String, tool: String = FILE_WRITE): String {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        val unsafe = path.startsWith("/") || parts.isEmpty() || ".." in parts || path.any { it.isISOControl() } ||
            parts.any { it == GIT_DIR }
        val relative = parts.joinToString("/")
        if (unsafe || ScopeGate.isProtected(project, relative)) {
            throw refused(tool, cardId, "path is not writable for card $cardId")
        }
        return relative
    }

    /** Replaces one exact piece of an existing file; the rest of the file is untouched. */
    fun fileEditRequest(cardId: String, path: String, oldText: String, newText: String): WriteView {
        val card = requireOwnCard(FILE_EDIT, cardId)
        val project = project()
        val bytes = newText.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTENT_BYTES || oldText.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
            throw refused(FILE_EDIT, cardId, "text above $MAX_CONTENT_BYTES bytes refused for card $cardId")
        }
        if (path.length > MAX_PATH_CHARS) {
            throw refused(FILE_EDIT, cardId, "path above $MAX_PATH_CHARS characters refused for card $cardId")
        }
        if (oldText.isEmpty()) throw refused(FILE_EDIT, cardId, "oldText is empty for card $cardId")
        val relative = normalize(project, cardId, path, FILE_EDIT)
        val event = refusing {
            AgentRequests.requestWrite(cardId, card, session.role, relative, bytes.size, newId(), clock.instant())
        }
        val root = worktrees.existing(project, cardId)
            ?: throw refused(FILE_EDIT, cardId, "card $cardId has no worktree")
        val target = targetInside(root, relative, cardId, FILE_EDIT)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw refused(FILE_EDIT, cardId, "file $relative does not exist for card $cardId")
        }
        val current = try {
            Files.readString(target)
        } catch (e: IOException) {
            throw refused(FILE_EDIT, cardId, "read failed for card $cardId")
        }
        val first = current.indexOf(oldText)
        if (first < 0) throw refused(FILE_EDIT, cardId, "oldText not found in $relative for card $cardId")
        if (current.indexOf(oldText, first + 1) >= 0) {
            throw refused(FILE_EDIT, cardId, "oldText appears more than once in $relative for card $cardId")
        }
        val edited = current.substring(0, first) + newText + current.substring(first + oldText.length)
        val editedBytes = edited.toByteArray(Charsets.UTF_8)
        if (editedBytes.size > MAX_CONTENT_BYTES) {
            throw refused(FILE_EDIT, cardId, "file above $MAX_CONTENT_BYTES bytes refused for card $cardId")
        }
        writeInside(root, relative, editedBytes, cardId, FILE_EDIT)
        recorder.record(cardId, card, event)
        log.log("mcp_tool", mapOf("tool" to FILE_EDIT, "card" to cardId, "path" to relative))
        return WriteView(relative, editedBytes.size)
    }

    /** The target path inside the worktree, creating missing parent directories; refuses links and escapes. */
    private fun targetInside(root: Path, relative: String, cardId: String, tool: String): Path {
        val realRoot = root.toRealPath()
        var current = realRoot
        val parts = relative.split("/")
        for (part in parts.dropLast(1)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw outside(cardId, tool)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw outside(cardId, tool)
        }
        if (!current.toRealPath().startsWith(realRoot)) throw outside(cardId, tool)
        val target = current.resolve(parts.last())
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw outside(cardId, tool)
        }
        return target
    }

    private fun writeInside(root: Path, relative: String, bytes: ByteArray, cardId: String, tool: String) {
        val target = targetInside(root, relative, cardId, tool)
        refuseTruncation(target, relative, bytes.size, cardId, tool)
        val temp = Files.createTempFile(target.parent, ".write-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw refused(tool, cardId, "write failed for card $cardId")
        }
    }

    /** A rewrite that shrinks a sizeable file to under 40% is almost always a truncated output, not an intent. */
    private fun refuseTruncation(target: Path, relative: String, newSize: Int, cardId: String, tool: String) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || relative.substringAfterLast('/') == RED_STUBS) return
        val oldSize = try {
            Files.size(target)
        } catch (e: IOException) {
            return
        }
        if (oldSize >= TRUNCATION_MIN_BYTES && newSize * TRUNCATION_DENOMINATOR < oldSize * TRUNCATION_NUMERATOR) {
            throw refused(
                tool, cardId,
                "write would shrink $relative from $oldSize to $newSize bytes for card $cardId; " +
                    "send the whole file, or use file_edit_request to change only one passage",
            )
        }
    }

    private fun outside(cardId: String, tool: String): McpToolException =
        refused(tool, cardId, "path leaves the worktree of card $cardId")

    private fun requireOwnCard(tool: String, cardId: String): Card {
        if (!CARD_ID.matches(cardId)) {
            log.log("mcp_refused", mapOf("tool" to tool, "reason" to "invalid_card_id"))
            throw McpToolException("argument cardId is invalid")
        }
        if (store.projectOf(cardId) != session.repo) {
            throw refused(tool, cardId, "card $cardId does not belong to project ${project().name}")
        }
        if (cardId != session.cardId) {
            throw refused(tool, cardId, "card $cardId is not the card of this session")
        }
        return store.card(cardId) ?: throw McpToolException("card $cardId not found")
    }

    private fun refused(tool: String, cardId: String, message: String): McpToolException {
        log.log("mcp_refused", mapOf("tool" to tool, "card" to cardId))
        return McpToolException(message)
    }

    private fun <T> refusing(block: () -> T): T =
        try {
            block()
        } catch (e: AgentRequestRefusedException) {
            throw McpToolException(e.message ?: "request refused")
        }

    private fun project(): Project =
        registry.list().firstOrNull { it.repo == session.repo }
            ?: throw McpToolException("project ${session.repo} is not registered")

    private fun newId(): EventId = EventId(UUID.randomUUID().toString())

    private companion object {
        val CARD_ID = Regex("[A-Za-z0-9-]{1,64}")
        val DRAFT_ID = Regex("[A-Za-z0-9-]{1,64}")
        val DRAFT_PATHS = ProjectDocsDraft.DOCS.map { "${ProjectDocsDraft.DRAFT_DIR}/$it" }.toSet()
        const val DOCS_DRAFT_ROLE = ProjectDocsDraft.ROLE
        const val MAX_CONTENT_BYTES = 256 * 1024
        const val MAX_PATH_CHARS = 512
        const val GIT_DIR = ".git"
        const val FILE_WRITE = "file_write_request"
        const val FILE_EDIT = "file_edit_request"
        const val RED_STUBS = "RedStubs.kt"
        const val TRUNCATION_MIN_BYTES = 500L
        const val TRUNCATION_NUMERATOR = 40L
        const val TRUNCATION_DENOMINATOR = 100L
    }
}
