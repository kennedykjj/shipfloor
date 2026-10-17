package shipfloor.daemon

import java.io.IOException
import java.io.PrintStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

data class SystemctlResult(val exitCode: Int, val output: String)

/** Runs `systemctl --user <args>`; a first argument `loginctl` runs `loginctl <rest>` instead. */
interface SystemctlRunner {
    fun run(vararg args: String): SystemctlResult
}

/** Process-backed [SystemctlRunner]. */
object ProcessSystemctl : SystemctlRunner {
    override fun run(vararg args: String): SystemctlResult {
        val command = if (args.firstOrNull() == LOGINCTL) args.drop(1).let { listOf(LOGINCTL) + it }
        else listOf("systemctl", "--user") + args
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            SystemctlResult(process.waitFor(), output.trim())
        } catch (e: IOException) {
            SystemctlResult(127, "cannot run ${command.first()}: ${e.javaClass.simpleName}")
        }
    }

    const val LOGINCTL = "loginctl"
}

sealed interface ServiceOutcome {
    /** [lingerWarning] is set when the user has no linger, so the service only starts at login. */
    data class Installed(val lingerWarning: String? = null) : ServiceOutcome
    data object AlreadyInstalled : ServiceOutcome
    data object Removed : ServiceOutcome
    data object NotInstalled : ServiceOutcome
    data class Unsupported(val os: String) : ServiceOutcome
    data class Failed(val reason: String) : ServiceOutcome
}

class ServiceInstaller(
    private val unitDir: Path,
    private val launcher: Path,
    private val systemctl: SystemctlRunner,
    private val osName: String,
) {
    private val unitFile: Path get() = unitDir.resolve(ServiceUnit.UNIT_NAME)

    /** service.env lives next to the unit tree: `<config>/systemd/user` -> `<config>/shipfloor/service.env`. */
    private val envFile: Path get() = unitDir.resolve("../../shipfloor/service.env").normalize()

    fun install(): ServiceOutcome {
        if (!osName.contains("Linux", ignoreCase = true)) return ServiceOutcome.Unsupported(osName)
        val unit = try {
            ServiceUnit.render(launcher)
        } catch (e: IllegalArgumentException) {
            return ServiceOutcome.Failed(e.message ?: "invalid launcher path")
        }
        return try {
            ensureEnvFile()
            if (Files.exists(unitFile) && Files.readString(unitFile) == unit) return ServiceOutcome.AlreadyInstalled
            writeAtomically(unit)
            systemctl.step("daemon-reload")?.let { return it }
            systemctl.step("enable", "--now", ServiceUnit.UNIT_NAME)?.let { return it }
            ServiceOutcome.Installed(lingerWarning())
        } catch (e: IOException) {
            ServiceOutcome.Failed("cannot write service files: ${e.javaClass.simpleName}")
        }
    }

    fun uninstall(): ServiceOutcome {
        if (!Files.exists(unitFile)) return ServiceOutcome.NotInstalled
        return try {
            systemctl.step("disable", "--now", ServiceUnit.UNIT_NAME)?.let { return it }
            Files.deleteIfExists(unitFile)
            systemctl.step("daemon-reload")?.let { return it }
            ServiceOutcome.Removed
        } catch (e: IOException) {
            ServiceOutcome.Failed("cannot remove unit: ${e.javaClass.simpleName}")
        }
    }

    private fun SystemctlRunner.step(vararg args: String): ServiceOutcome.Failed? {
        val result = run(*args)
        return if (result.exitCode == 0) null
        else ServiceOutcome.Failed("systemctl ${args.first()} exited ${result.exitCode}: ${result.output}")
    }

    private fun lingerWarning(): String? {
        val result = systemctl.run(ProcessSystemctl.LOGINCTL, "show-user", "--property=Linger")
        if (result.exitCode == 0 && result.output.trim() == "Linger=yes") return null
        return "the service only starts at login; to start it at boot run: loginctl enable-linger"
    }

    private fun ensureEnvFile() {
        if (Files.exists(envFile)) return
        Files.createDirectories(envFile.parent)
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createFile(envFile, permissions)
        Files.writeString(envFile, ENV_TEMPLATE)
    }

    private fun writeAtomically(content: String) {
        Files.createDirectories(unitDir)
        val temp = Files.createTempFile(unitDir, "shipfloor", ".tmp")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, unitFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, unitFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        val ENV_TEMPLATE = """
            |# Environment for the shipfloor user service (read by systemd, never overwritten by install).
            |# Uncomment and edit; one KEY=value per line, no quotes needed.
            |#
            |# Turns the dispatcher on (it is off by default):
            |# SHIPFLOOR_DISPATCHER_ENABLED=true
            |#
            |# systemd user services get a minimal PATH; list the directories with git, gh and your agent CLIs:
            |# PATH=/usr/local/bin:/usr/bin:/bin
            |#
            |# JDK used by the daemon when it is not the system default:
            |# JAVA_HOME=/path/to/jdk
            |
        """.trimMargin()
    }
}

/** Handles `service install|uninstall`; returns the process exit code. */
fun runServiceCommand(args: List<String>, env: Map<String, String>, out: PrintStream, err: PrintStream): Int {
    val action = args.getOrNull(1)
    if (args.firstOrNull() != "service" || action !in setOf("install", "uninstall")) {
        err.println("usage: service install|uninstall")
        return 2
    }
    val configHome = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: env["HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".config") }
    if (configHome == null) {
        err.println("cannot locate the config directory: set XDG_CONFIG_HOME or HOME")
        return 1
    }
    val launcherProperty = System.getProperty(LAUNCHER_PROPERTY)
    if (action == "install" && launcherProperty.isNullOrBlank()) {
        err.println("cannot locate the launcher: run it through the installed start script")
        return 1
    }
    val launcher = Path.of(launcherProperty ?: "/")
    val installer = ServiceInstaller(
        configHome.resolve("systemd/user"), launcher, ProcessSystemctl, System.getProperty("os.name") ?: "",
    )
    val outcome = if (action == "install") installer.install() else installer.uninstall()
    return when (outcome) {
        is ServiceOutcome.Installed -> {
            out.println("service installed and started")
            outcome.lingerWarning?.let { out.println("warning: $it") }
            0
        }
        ServiceOutcome.AlreadyInstalled -> { out.println("service already installed"); 0 }
        ServiceOutcome.Removed -> { out.println("service removed"); 0 }
        ServiceOutcome.NotInstalled -> { out.println("service not installed"); 0 }
        is ServiceOutcome.Unsupported -> { err.println("only Linux is supported (found ${outcome.os})"); 1 }
        is ServiceOutcome.Failed -> { err.println("service command failed: ${outcome.reason}"); 1 }
    }
}

private const val LAUNCHER_PROPERTY = "shipfloor.launcher"
