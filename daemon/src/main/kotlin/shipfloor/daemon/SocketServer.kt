package shipfloor.daemon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Another daemon is already serving on the socket. */
class DaemonAlreadyRunningException(message: String) : RuntimeException(message)

/** Serves the line-delimited JSON protocol on a Unix domain socket, and on loopback TCP when [tcp] is set. */
class SocketServer private constructor(
    private val path: Path,
    private val server: ServerSocketChannel,
    private val lock: FileLock,
    private val log: DaemonLog,
    private val pause: (Long) -> Unit,
    private val commands: CardCommands?,
    private val projects: ProjectCommands?,
    private val rebuild: CardRebuild?,
    private val importer: CardImport?,
    private val closure: CardClosure?,
    private val milestones: ExternalMilestones?,
    private val settings: SettingsCommands?,
    private val costSummary: CostSummaryQuery?,
    private val tcp: TcpTransport?,
    private val pulse: FactoryPulse?,
    private val docsDraft: ProjectDocsDraft?,
    private val onClose: () -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()

    private fun startAccepting() {
        val loop = AcceptLoop({ server.accept() }, log, pause) { client ->
            clients.add(client)
            Thread.ofVirtual().start { serve(client) }
        }
        Thread.ofPlatform().daemon().name("shipfloor-daemon-accept").start { loop.run() }
        if (tcp != null) {
            val tcpLoop = AcceptLoop({ tcp.accept() }, log, pause) { client ->
                clients.add(client)
                Thread.ofVirtual().start { serveAuthenticated(tcp, client) }
            }
            Thread.ofPlatform().daemon().name("shipfloor-daemon-tcp-accept").start { tcpLoop.run() }
        }
    }

    /** A TCP client is served only after its first line carried the transport token. */
    private fun serveAuthenticated(transport: TcpTransport, client: SocketChannel) {
        if (transport.authenticate(client)) {
            serve(client)
        } else {
            log.log("tcp_rejected", mapOf("reason" to "auth_failed"))
            runCatching { client.close() }
            clients.remove(client)
        }
    }

    private fun serve(client: SocketChannel) {
        var handedOver = false
        try {
            val input = Channels.newInputStream(client).buffered()
            val output = Channels.newOutputStream(client)
            while (true) {
                val line = when (val read = readLine(input)) {
                    LineRead.EndOfStream -> return
                    LineRead.TooLong -> {
                        reply(output, render(Response.Error("line exceeds the $MAX_LINE_BYTES byte limit")))
                        drain(input)
                        return
                    }
                    is LineRead.Line -> read.text
                }
                val request = parseRequest(line)
                val stream = commands?.events
                if (request is Request.Subscribe && stream != null) {
                    reply(output, """{"ok":true,"result":"subscribed"}""")
                    // From here the stream owns the connection and closes it.
                    handedOver = true
                    stream.subscribe(client, request.after) { clients.remove(client) }
                    return
                }
                reply(output, render(dispatch(request)))
            }
        } catch (e: IOException) {
            // A failing client only ends its own connection.
            log.log("client_error", mapOf("error" to e.javaClass.simpleName))
        } catch (e: RuntimeException) {
            // Same: never let one client affect the accept loop.
            log.log("client_error", mapOf("error" to e.javaClass.simpleName))
        } finally {
            if (!handedOver) {
                runCatching { client.close() }
                clients.remove(client)
            }
        }
    }

    private fun reply(output: OutputStream, body: String) {
        output.write((body + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun dispatch(request: Request): Response = when (request) {
        Request.Ping -> Response.Pong
        is Request.Rejected -> Response.Error(request.message)
        is Request.ProjectAdd, Request.ProjectList ->
            projects?.handle(request) ?: Response.Error("project commands are not available")
        is Request.ProjectDocsDraft -> docsDraft?.handle(request) ?: Response.Error("docs draft is not available")
        Request.SettingsGet, is Request.SettingsSave ->
            settings?.handle(request) ?: Response.Error("settings commands are not available")
        is Request.CardRebuild -> rebuild?.handle(request) ?: Response.Error("card rebuild is not available")
        is Request.CardImport, is Request.CardImportBoard -> importer?.handle(request) ?: Response.Error("card import is not available")
        is Request.CardCloseDraft, is Request.CardCloseConfirm -> closure?.handle(request) ?: Response.Error("card closure is not available")
        is Request.CostSummary -> costSummary?.handle(request) ?: Response.Error("cost summary is not available")
        Request.FactoryPulse -> pulse?.handle(request) ?: Response.Error("factory pulse is not available")
        Request.MilestoneList, is Request.MilestoneConfirm -> milestones?.handle(request) ?: Response.Error("milestone commands are not available")
        is Request.Subscribe, is Request.CardCreate, is Request.CardMove, is Request.CardApprove, is Request.CardUnblock, is Request.CardRequestChange, Request.CardList, is Request.CardShow, is Request.CardDetail, Request.ReportCsv, is Request.EventsRecent, is Request.AuditExport, is Request.ColumnTimes ->
            commands?.handle(request) ?: Response.Error("card commands are not available")
    }

    /** Stops accepting, closes the channels and removes the socket file. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { tcp?.close() }
        clients.forEach { runCatching { it.close() } }
        runCatching { Files.deleteIfExists(path) }
        runCatching { lock.release() }
        runCatching { lock.channel().close() }
        runCatching { onClose() }
    }

    private sealed interface LineRead {
        data object EndOfStream : LineRead
        data object TooLong : LineRead
        data class Line(val text: String) : LineRead
    }

    private fun readLine(input: InputStream): LineRead {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return LineRead.EndOfStream
            if (b == '\n'.code) return LineRead.Line(buffer.toString(Charsets.UTF_8))
            if (buffer.size() >= MAX_LINE_BYTES) return LineRead.TooLong
            buffer.write(b)
        }
    }

    /** Discards what the client still sends, up to a cap, so closing does not reset the reply. */
    private fun drain(input: InputStream) {
        var discarded = 0
        val chunk = ByteArray(8192)
        while (discarded < MAX_DRAIN_BYTES) {
            val n = input.read(chunk)
            if (n < 0) return
            discarded += n
        }
    }

    companion object {
        private const val MAX_LINE_BYTES = 64 * 1024
        private const val MAX_DRAIN_BYTES = 1024 * 1024
        private const val DIR_PERMISSIONS = "rwx------"
        private const val FILE_PERMISSIONS = "rw-------"

        private const val LOCK_FILE_NAME = "daemon.lock"

        /** Binds the socket at [path] and starts serving; the server owns [tcp] from here on. */
        fun start(
            path: Path,
            log: DaemonLog = StderrDaemonLog,
            pause: (Long) -> Unit = Thread::sleep,
            commands: CardCommands? = null,
            projects: ProjectCommands? = null,
            onClose: () -> Unit = {},
            rebuild: CardRebuild? = null,
            importer: CardImport? = null,
            closure: CardClosure? = null,
            milestones: ExternalMilestones? = null,
            settings: SettingsCommands? = null,
            costSummary: CostSummaryQuery? = null,
            tcp: TcpTransport? = null,
            pulse: FactoryPulse? = null,
            docsDraft: ProjectDocsDraft? = null,
        ): SocketServer {
            prepareDirectory(path)
            val lock = acquireLock(path)
            val channel = try {
                clearOrphan(path)
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            } catch (e: IOException) {
                releaseLock(lock)
                throw DaemonStartException("cannot open socket at $path: ${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                releaseLock(lock)
                throw e
            }
            try {
                channel.bind(UnixDomainSocketAddress.of(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(FILE_PERMISSIONS))
            } catch (e: IOException) {
                runCatching { channel.close() }
                releaseLock(lock)
                throw DaemonStartException("cannot bind socket at $path: ${e.javaClass.simpleName}")
            }
            return SocketServer(path, channel, lock, log, pause, commands, projects, rebuild, importer, closure, milestones, settings, costSummary, tcp, pulse, docsDraft, onClose).also { it.startAccepting() }
        }

        private fun releaseLock(lock: FileLock) {
            runCatching { lock.release() }
            runCatching { lock.channel().close() }
        }

        /** Takes the exclusive start lock; it is kept until the server closes. */
        private fun acquireLock(path: Path): FileLock {
            val lockPath = path.resolveSibling(LOCK_FILE_NAME)
            val channel = try {
                FileChannel.open(
                    lockPath,
                    setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMISSIONS)),
                )
            } catch (e: IOException) {
                throw DaemonStartException("cannot open lock file $lockPath: ${e.javaClass.simpleName}")
            }
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                runCatching { channel.close() }
                throw DaemonStartException("cannot lock $lockPath: ${e.javaClass.simpleName}")
            }
            if (lock == null) {
                runCatching { channel.close() }
                throw DaemonAlreadyRunningException("a daemon is already running on $path")
            }
            return lock
        }

        private fun prepareDirectory(path: Path) {
            val dir = requireNotNull(path.parent) { "socket path has no parent directory: $path" }
            try {
                val permissions = PosixFilePermissions.fromString(DIR_PERMISSIONS)
                if (!Files.isDirectory(dir)) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(permissions))
                }
                Files.setPosixFilePermissions(dir, permissions)
            } catch (e: IOException) {
                throw DaemonStartException("cannot prepare socket directory $dir: ${e.javaClass.simpleName}")
            }
        }

        private fun clearOrphan(path: Path) {
            if (!Files.exists(path)) return
            val alive = try {
                SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
            } catch (e: IOException) {
                false
            }
            if (alive) throw DaemonAlreadyRunningException("a daemon is already running on $path")
            try {
                Files.deleteIfExists(path)
            } catch (e: IOException) {
                throw DaemonStartException("cannot remove orphan socket $path: ${e.javaClass.simpleName}")
            }
        }
    }
}
