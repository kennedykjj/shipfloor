package shipfloor.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.concurrent.TimeUnit

private const val DAEMON_START_HINT = "inicie o daemon com `./gradlew :daemon:run`"
private const val SERVICE_HINT = "instale o serviço com `service install` no daemon"
private const val APP_DIR_NAME = "shipfloor"
private const val DATABASE_FILE_NAME = "shipfloor.db"
private const val SERVICE_UNIT_NAME = "shipfloor.service"
private const val SERVICE_ENV_NAME = "service.env"
private const val APPINDICATOR_PREFIX = "appindicatorsupport@"
private const val DEFAULT_APPINDICATOR_UUID = "appindicatorsupport@rgcjonas.gmail.com"
private const val PROCESS_TIMEOUT_SECONDS = 3L
private val OWNER_ONLY_FILE = setOf(OWNER_READ, OWNER_WRITE)
private val GROUP_OR_OTHERS = setOf(
    GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
    OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE,
)

/** One line of `shipfloor doctor`: [detail] follows the name; on a failure it is the instruction to fix it. */
private data class Check(val name: String, val ok: Boolean, val detail: String = "") {
    fun line(): String = when {
        !ok -> "FAIL $name — $detail"
        detail.isEmpty() -> "OK $name"
        else -> "OK $name — $detail"
    }
}

class DoctorCommand(private val cli: CliEnvironment) : CliktCommand(name = "doctor") {
    override fun help(context: Context) = "Check the machine: tools, daemon, database, service and permissions."

    override fun run() {
        val checks = diagnose(cli.env)
        checks.forEach { cli.out(it.line()) }
        appIndicatorWarning(cli.env, cli.runCommand)?.let { cli.out(it) }
        if (checks.any { !it.ok }) throw ProgramResult(EXIT_FAILURE)
    }
}

/** Runs every check, even after a failure, so the owner sees the whole picture at once. */
private fun diagnose(env: Map<String, String>): List<Check> {
    val location = resolveSocketLocation(env)
    val dataDir = resolveDataDir(env, location)
    val configDir = resolveConfigDir(env)
    return listOf(
        toolCheck("java", env),
        toolCheck("git", env),
        toolCheck("gh", env),
        toolCheck("claude", env),
        socketCheck(location),
        databaseCheck(dataDir),
        serviceCheck(configDir),
        permissionsCheck(location, dataDir),
        serviceEnvCheck(configDir),
    )
}

private fun toolCheck(tool: String, env: Map<String, String>): Check {
    val found = env["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        .any { dir -> Files.isRegularFile(Path.of(dir).resolve(tool)) && Files.isExecutable(Path.of(dir).resolve(tool)) }
    return if (found) Check(tool, ok = true) else Check(tool, ok = false, detail = "instale $tool e coloque o executável no PATH da shell")
}

private fun socketCheck(location: SocketLocation?): Check {
    if (location == null) {
        return Check("socket", ok = false, detail = "defina XDG_RUNTIME_DIR; o daemon cria o socket nesse diretório")
    }
    if (!Files.exists(location.socket)) {
        return Check("socket", ok = false, detail = "o socket ${location.socket} não existe; $DAEMON_START_HINT")
    }
    return try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(location.socket)) }
        Check("socket", ok = true)
    } catch (e: IOException) {
        Check("socket", ok = false, detail = "nenhum daemon aceita conexão em ${location.socket}; $DAEMON_START_HINT")
    }
}

private fun databaseCheck(dataDir: Path?): Check {
    if (dataDir == null) {
        return Check("banco", ok = false, detail = "defina XDG_DATA_HOME ou HOME para o banco ter onde ficar")
    }
    val database = dataDir.resolve(DATABASE_FILE_NAME)
    return if (Files.isRegularFile(database)) {
        Check("banco", ok = true)
    } else {
        Check("banco", ok = false, detail = "$database não existe; $DAEMON_START_HINT")
    }
}

private fun serviceCheck(configDir: Path?): Check {
    if (configDir == null) {
        return Check("serviço", ok = false, detail = "defina XDG_CONFIG_HOME ou HOME para achar a unit do systemd")
    }
    val unit = configDir.resolve("systemd").resolve("user").resolve(SERVICE_UNIT_NAME)
    return if (Files.isRegularFile(unit)) {
        Check("serviço", ok = true)
    } else {
        Check("serviço", ok = false, detail = "$unit não existe; $SERVICE_HINT")
    }
}

/** Only the modes of existing files are read; a missing file is reported by its own check. */
private fun permissionsCheck(location: SocketLocation?, dataDir: Path?): Check {
    val problems = mutableListOf<String>()
    try {
        location?.token?.takeIf { Files.exists(it) }?.let { token ->
            if (Files.getPosixFilePermissions(token) != OWNER_ONLY_FILE) {
                problems += "chmod 600 $token"
            }
        }
        dataDir?.takeIf { Files.isDirectory(it) }?.let { dir ->
            if (Files.getPosixFilePermissions(dir).any { it in GROUP_OR_OTHERS }) {
                problems += "chmod 700 $dir"
            }
        }
    } catch (e: IOException) {
        return Check("permissões", ok = false, detail = "não foi possível ler as permissões: ${e.javaClass.simpleName}")
    }
    return if (problems.isEmpty()) Check("permissões", ok = true) else Check("permissões", ok = false, detail = problems.joinToString("; "))
}

private fun serviceEnvCheck(configDir: Path?): Check {
    if (configDir == null) {
        return Check("ambiente do serviço", ok = false, detail = "defina XDG_CONFIG_HOME ou HOME para achar service.env")
    }
    val envFile = configDir.resolve(APP_DIR_NAME).resolve(SERVICE_ENV_NAME)
    if (!Files.exists(envFile)) {
        return Check("ambiente do serviço", ok = false, detail = "$envFile não existe; crie com o PATH de java, git, gh e claude")
    }
    return try {
        if (Files.getPosixFilePermissions(envFile) != OWNER_ONLY_FILE) {
            Check("ambiente do serviço", ok = false, detail = "chmod 600 $envFile")
        } else {
            Check(
                "ambiente do serviço",
                ok = true,
                detail = "modo 600; o PATH do serviço é o do systemd, não o da sua shell, então java, git, gh e claude podem faltar no daemon",
            )
        }
    } catch (e: IOException) {
        Check("ambiente do serviço", ok = false, detail = "não foi possível ler o modo de $envFile: ${e.javaClass.simpleName}")
    }
}

/** Warns when GNOME would hide the tray: only on GNOME, only when `gnome-extensions` answers and the extension is off. */
private fun appIndicatorWarning(env: Map<String, String>, runCommand: (List<String>) -> String?): String? {
    if (!env["XDG_CURRENT_DESKTOP"].orEmpty().contains("GNOME")) return null
    val enabled = runCommand(listOf("gnome-extensions", "list", "--enabled")) ?: return null
    if (enabled.lines().any { it.trim().startsWith(APPINDICATOR_PREFIX) }) return null
    val installed = runCommand(listOf("gnome-extensions", "list"))
        ?.lines()?.map { it.trim() }?.firstOrNull { it.startsWith(APPINDICATOR_PREFIX) }
    val uuid = installed ?: DEFAULT_APPINDICATOR_UUID
    return "AVISO  A bandeja não vai aparecer: a extensão AppIndicator do GNOME está desligada. Ligue com: gnome-extensions enable $uuid"
}

/** Stdout of [command], or null when it does not exist, fails, or takes longer than a few seconds. */
fun runProcess(command: List<String>): String? =
    try {
        val process = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }
        reader.start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            reader.join(PROCESS_TIMEOUT_SECONDS * 1000)
            if (process.exitValue() == 0) output.toString() else null
        }
    } catch (e: IOException) {
        null
    }

/** Database directory, as the daemon resolves it: XDG_DATA_HOME, else HOME/.local/share, else the socket's directory. */
private fun resolveDataDir(env: Map<String, String>, location: SocketLocation?): Path? {
    env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it).resolve(APP_DIR_NAME) }
    env["HOME"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it).resolve(".local/share").resolve(APP_DIR_NAME) }
    return location?.socket?.parent
}

/** Config directory, as the service installer resolves it: XDG_CONFIG_HOME, else HOME/.config. */
private fun resolveConfigDir(env: Map<String, String>): Path? {
    env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    return env["HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".config") }
}

/** Registers the doctor command on [root]. */
fun ShipfloorCommand.withDoctorCommand(cli: CliEnvironment): ShipfloorCommand =
    subcommands(DoctorCommand(cli)) as ShipfloorCommand
