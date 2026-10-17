package shipfloor.daemon

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The QA's `RedStubs.kt` only exists to make the red tests compile. The engineer has no tool to delete a file, so it
 * empties the stubs instead; what is left (blank lines, `package`, comments) is removed here before the commit.
 */
object RedStubFiles {
    private const val NAME = "RedStubs.kt"
    private val SKIPPED_DIRS = setOf(".git", "build", ".gradle")

    fun removeEmptied(worktree: Path) {
        val root = worktree.toAbsolutePath().normalize()
        val found = mutableListOf<Path>()
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && dir.fileName.toString() in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && file.fileName.toString() == NAME) found.add(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        } catch (e: IOException) {
            return
        }
        found.filter { isEmptied(it) }.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun isEmptied(file: Path): Boolean =
        try {
            Files.readAllLines(file).none { line ->
                val text = line.trim()
                text.isNotEmpty() && !text.startsWith("package ") && !text.startsWith("//") && !text.startsWith("/*") &&
                    !text.startsWith("*")
            }
        } catch (e: IOException) {
            false
        }
}
