package shipfloor.store

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import shipfloor.core.TextHash

sealed interface SpecRead {
    data class Valid(val text: String, val hash: TextHash) : SpecRead

    /** The file no longer matches the registered hash; `actual` is null when the file is gone. */
    data class Diverged(val path: Path, val expected: TextHash, val actual: TextHash?) : SpecRead

    data class NotRegistered(val cardId: String) : SpecRead
}

class SpecStoreException(message: String) : RuntimeException(message)

/** Card specs as Markdown files; the database holds the path and hash and is the arbiter. */
class SpecStore(private val database: Database, private val specsDir: Path) {
    fun write(cardId: String, text: String) {
        val path = pathOf(cardId)
        Files.createDirectories(specsDir)
        val temp = Files.createTempFile(specsDir, "$cardId-", ".tmp")
        try {
            Files.writeString(temp, text)
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        database.queries.schemaQueries.upsertSpec(cardId, path.toString(), TextHash.of(text).value)
    }

    fun read(cardId: String): SpecRead {
        val row = database.queries.schemaQueries.selectSpec(cardId).executeAsOneOrNull()
            ?: return SpecRead.NotRegistered(cardId)
        val path = Path.of(row.path)
        val expected = TextHash(row.hash)
        val text = try {
            Files.readString(path)
        } catch (e: NoSuchFileException) {
            return SpecRead.Diverged(path, expected, null)
        }
        val actual = TextHash.of(text)
        return if (actual == expected) SpecRead.Valid(text, actual) else SpecRead.Diverged(path, expected, actual)
    }

    private fun pathOf(cardId: String): Path {
        if (cardId.isBlank() || cardId.contains('/') || cardId.contains('\\') || cardId.contains("..")) {
            throw SpecStoreException("Invalid card id for spec path: $cardId")
        }
        return specsDir.resolve("$cardId.md")
    }
}
