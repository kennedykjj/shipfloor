package shipfloor.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import shipfloor.core.FactorySettings

/** The settings file cannot be read or written; the message never carries its content. */
class SettingsFileException(message: String) : RuntimeException(message)

/** Per-machine settings file (`settings.json`, mode 0600). An absent file means the defaults, with no override. */
class SettingsStore(val path: Path, private val clock: Clock) {

    fun exists(): Boolean = Files.exists(path)

    fun load(): FactorySettings {
        val text = snapshot()?.toString(Charsets.UTF_8) ?: return FactorySettings.DEFAULT
        return try {
            settingsFromJson(Json.parseToJsonElement(text) as? JsonObject ?: throw SettingsFileException("settings file is not an object"))
        } catch (e: SerializationException) {
            throw SettingsFileException("settings file is not valid JSON")
        } catch (e: InvalidSettingsJsonException) {
            throw SettingsFileException("settings file has an invalid field: ${e.field}")
        }
    }

    /** Writes atomically: a reader sees the old or the new file, never a partial one. */
    @Synchronized
    fun save(settings: FactorySettings) {
        write(settingsToJson(settings).toString().toByteArray(Charsets.UTF_8))
    }

    /** The raw file content, or null when there is no file. */
    @Synchronized
    fun snapshot(): ByteArray? = try {
        if (Files.exists(path)) Files.readAllBytes(path) else null
    } catch (e: IOException) {
        throw SettingsFileException("cannot read the settings file: ${e.javaClass.simpleName}")
    }

    /** Puts back what [snapshot] returned; null removes the file. */
    @Synchronized
    fun restore(previous: ByteArray?) {
        if (previous != null) {
            write(previous)
            return
        }
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            throw SettingsFileException("cannot remove the settings file: ${e.javaClass.simpleName}")
        }
    }

    private fun write(content: ByteArray) {
        val temp = path.resolveSibling("${path.fileName}.${clock.millis()}.tmp")
        try {
            Files.createDirectories(path.parent)
            Files.deleteIfExists(temp)
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)))
            Files.write(temp, content)
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            throw SettingsFileException("cannot write the settings file: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val FILE_MODE = "rw-------"
    }
}
