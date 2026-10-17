package shipfloor.daemon

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.ProjectPathStore
import shipfloor.store.SpecStore

/** Outcome of starting the daemon; only [main] turns a failure into a process exit. */
sealed interface DaemonLaunch {
    data class Started(val server: SocketServer) : DaemonLaunch
    data class Failed(val exitCode: Int) : DaemonLaunch
}

/** Starts the daemon from [env]; failures are reported on [err] and returned, never thrown. */
fun launchDaemon(env: Map<String, String>, err: PrintStream): DaemonLaunch = try {
    val socketPath = resolveSocketPath(env)
    val tcpPort = resolveTcpPort(env)
    val database = openDatabase(resolveDatabasePath(env, socketPath))
    var tcp: TcpTransport? = null
    try {
        tcp = tcpPort?.let { TcpTransport.open(requireNotNull(socketPath.parent) { "socket path has no parent directory" }, it, SecureRandom()) }
        val ownerProof = OwnerProof.create(socketPath.resolveSibling("owner.token"), SecureRandom())
        val clock = Clock.systemUTC()
        val store = CardEventStore(database)
        val specs = SpecStore(database, resolveDatabasePath(env, socketPath).parent.resolve("specs"))
        val commands = CardCommands(store, clock, ownerProof, specs = specs)
        val source = GitProjectConfigSource()
        val registry = ProjectRegistry(source, ProjectPathStore(database), StderrDaemonLog, clock)
        registry.restore()
        val projects = ProjectCommands(registry, ownerProof)
        val dataDir = resolveDatabasePath(env, socketPath).parent
        val settingsStore = SettingsStore(dataDir.resolve("settings.json"), clock)
        val settings = SettingsCommands(settingsStore, commands.recorder, store, ownerProof, env, clock)
        val refine = RefineRuntime.start(
            settings = settingsStore,
            dataDir = dataDir,
            store = store,
            commands = commands,
            registry = registry,
            files = source,
            database = database,
            clock = clock,
            source = source,
            env = env,
        )
        if (refine.dispatcherAllowed) {
            commands.events.addListener(refine.dispatcher::onEvent)
            refine.dispatcher.start()
        }
        DaemonLaunch.Started(
            SocketServer.start(
                socketPath,
                commands = commands,
                projects = projects,
                rebuild = CardRebuild.forProjects(
                    store, commands.recorder, registry,
                    { project -> ProjectDelivery.forgeOf(project, dataDir.resolve("forge")) },
                    ownerProof, clock, StderrDaemonLog,
                ),
                importer = CardImport.forProjects(
                    store, commands.recorder, registry,
                    { project -> ProjectDelivery.trackerOf(project, dataDir.resolve("tracker")) },
                    ownerProof, clock, StderrDaemonLog,
                ),
                closure = CardClosure.forProjects(
                    store, commands.recorder, registry,
                    { project -> ProjectDelivery.trackerOf(project, dataDir.resolve("tracker")) },
                    { project, pr -> ProjectDelivery.pullRequestUrl(project, pr) },
                    ownerProof, clock, StderrDaemonLog,
                ),
                milestones = refine.milestones,
                settings = settings,
                costSummary = refine.costSummary,
                tcp = tcp,
                pulse = refine.pulse,
                docsDraft = refine.docsDraft,
                onClose = {
                    runCatching { refine.close() }
                    database.close()
                },
            ),
        )
    } catch (e: Throwable) {
        runCatching { tcp?.close() }
        runCatching { database.close() }
        throw e
    }
} catch (e: DaemonStartException) {
    err.println(e.message)
    DaemonLaunch.Failed(exitCode = 1)
} catch (e: DaemonAlreadyRunningException) {
    err.println(e.message)
    DaemonLaunch.Failed(exitCode = 1)
}

/** The opt-in TCP port from SHIPFLOOR_TCP_PORT (0 picks a free port); null when the variable is unset or blank. */
internal fun resolveTcpPort(env: Map<String, String>): Int? {
    val raw = env[TCP_PORT_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toIntOrNull()?.takeIf { it in 0..65535 }
        ?: throw DaemonStartException("$TCP_PORT_VARIABLE must be a port number from 0 to 65535, got a value that is not one; fix or unset the variable")
}

private const val TCP_PORT_VARIABLE = "SHIPFLOOR_TCP_PORT"

private fun openDatabase(path: Path): Database = try {
    Files.createDirectories(path.parent)
    Database.open(path)
} catch (e: IOException) {
    throw DaemonStartException("cannot open database at $path: ${e.javaClass.simpleName}")
}

/**
 * Database under XDG_DATA_HOME, else HOME/.local/share. Without either (no home to persist to)
 * it falls back to the socket's runtime directory so the daemon never guesses a path outside [env].
 */
internal fun resolveDatabasePath(env: Map<String, String>, socketPath: Path): Path {
    val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: env["HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".local/share") }
        ?: return requireNotNull(socketPath.parent) { "socket path has no parent directory" }.resolve(DATABASE_FILE_NAME)
    return dataHome.resolve("shipfloor").resolve(DATABASE_FILE_NAME)
}

private const val DATABASE_FILE_NAME = "shipfloor.db"

fun main(args: Array<String>) {
    if (args.firstOrNull() == "service") {
        exitProcess(runServiceCommand(args.toList(), System.getenv(), System.out, System.err))
    }
    when (val launch = launchDaemon(System.getenv(), System.err)) {
        is DaemonLaunch.Failed -> exitProcess(launch.exitCode)
        is DaemonLaunch.Started -> {
            Runtime.getRuntime().addShutdownHook(Thread { launch.server.close() })
            CountDownLatch(1).await()
        }
    }
}
