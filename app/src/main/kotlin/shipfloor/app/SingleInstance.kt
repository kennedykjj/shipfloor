package shipfloor.app

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

/**
 * One app per user: the first one holds a lock on `app.lock` and writes its process id there; a second one finds the
 * lock taken, reads that id and asks the first (through its tray icon) to show its window instead of opening another.
 */
object SingleInstance {
    sealed interface Result {
        /** This process is the app; keep [lock] for as long as it runs. It is null when no lock file could be made. */
        data class First(val lock: FileLock?) : Result

        /** Another process, [pid], already is the app. */
        data class Running(val pid: Long?) : Result
    }

    private const val LOCK_FILE = "app.lock"
    private const val PID_BYTES = 24

    fun tryAcquire(dir: Path, ownPid: Long = ProcessHandle.current().pid()): Result =
        try {
            Files.createDirectories(dir)
            val channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                val pid = readPid(channel)
                channel.close()
                Result.Running(pid)
            } else {
                channel.truncate(0)
                channel.write(ByteBuffer.wrap(ownPid.toString().toByteArray(StandardCharsets.UTF_8)), 0)
                Result.First(lock)
            }
        } catch (e: IOException) {
            // No lock file to be had: run anyway rather than refuse to start.
            Result.First(null)
        }

    private fun readPid(channel: FileChannel): Long? {
        val buffer = ByteBuffer.allocate(PID_BYTES)
        val read = channel.read(buffer, 0)
        return if (read <= 0) null else String(buffer.array(), 0, read, StandardCharsets.UTF_8).trim().toLongOrNull()
    }

    /** Asks the running app (its tray icon) to show its window. False when it cannot be reached. */
    fun activate(pid: Long): Boolean =
        try {
            DBusConnectionBuilder.forSessionBus().build().use { connection ->
                connection.getRemoteObject("org.kde.StatusNotifierItem-$pid-1", "/StatusNotifierItem", StatusNotifierItem::class.java).Activate(0, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
}
