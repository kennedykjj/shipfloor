package shipfloor.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Where a project's `factory.yml` text comes from. */
interface ProjectConfigSource {
    /** The file as committed on `main`, or null when the repository has no such file there. */
    fun read(repo: Path): String?
}

/** Any file of a project as committed on `main`. */
interface ProjectFileSource {
    /** The file at [path] on `main`, or null when the repository has no such file there. */
    fun read(repo: Path, path: String): String?

    companion object {
        /** Largest file or spec an agent may read. */
        const val MAX_BYTES = 256 * 1024
    }
}

/** A source with no files, the default where nothing is read from the repository. */
object NoProjectFiles : ProjectFileSource {
    override fun read(repo: Path, path: String): String? = null
}

/** Reads files from the `main` commit, never from the working tree. */
class GitProjectConfigSource(private val timeoutSeconds: Long = TIMEOUT_SECONDS) :
    ProjectConfigSource, ProjectFileSource {

    override fun read(repo: Path): String? = show(repo, CONFIG_PATH, CONFIG_FIELD, CONFIG_MAX_BYTES)

    override fun read(repo: Path, path: String): String? =
        show(repo, path, path.substringAfterLast('/'), ProjectFileSource.MAX_BYTES)

    private fun show(repo: Path, path: String, field: String, maxBytes: Int): String? {
        if (path.startsWith("/") || path.split('/').any { it == ".." }) {
            throw InvalidProjectConfigException(field, "path of $field is not inside the repository")
        }
        // Output goes to a file so the wait below bounds the whole run: a pipe read would block until EOF.
        val output = Files.createTempFile("shipfloor-project-file", ".txt")
        try {
            val process = try {
                ProcessBuilder(
                    "git", "-C", repo.toString(), "-c", "core.fsmonitor=false", "show", "main:$path",
                ).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                    .start()
            } catch (e: IOException) {
                throw InvalidProjectConfigException(field, "cannot run git to read $field")
            }
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw InvalidProjectConfigException(field, "reading $field timed out")
                }
            } finally {
                process.destroyForcibly()
            }
            if (process.exitValue() != 0) return null
            if (Files.size(output) > maxBytes) {
                throw InvalidProjectConfigException(field, "$field exceeds the $maxBytes byte limit")
            }
            return Files.readAllBytes(output).toString(Charsets.UTF_8)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private companion object {
        const val CONFIG_PATH = ".shipfloor/factory.yml"
        const val CONFIG_FIELD = "factory.yml"
        const val CONFIG_MAX_BYTES = 64 * 1024
        const val TIMEOUT_SECONDS = 10L
    }
}
