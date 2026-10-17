package shipfloor.daemon

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceInstallerTest {

    @TempDir
    lateinit var root: Path

    private class FakeSystemctl(private val linger: String = "Linger=yes", private val failOn: String? = null) : SystemctlRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(vararg args: String): SystemctlResult {
            calls += args.toList()
            if (args.any { it == "show-user" || it.contains("Linger") }) return SystemctlResult(0, linger)
            if (failOn != null && args.contains(failOn)) return SystemctlResult(1, "boom")
            return SystemctlResult(0, "")
        }
    }

    private val launcher: Path get() = root.resolve("bin/shipfloor-daemon")
    private val unitDir: Path get() = root.resolve("systemd/user")
    private val unitFile: Path get() = unitDir.resolve("shipfloor.service")
    private val envFile: Path get() = root.resolve("shipfloor/service.env")

    private fun installer(fake: FakeSystemctl = FakeSystemctl(), os: String = "Linux") =
        ServiceInstaller(unitDir, launcher, fake, os)

    @Test
    fun `AC-1 install writes the user unit and enables it now`() {
        val fake = FakeSystemctl()

        val outcome = installer(fake).install()

        assertThat(outcome).isInstanceOf(ServiceOutcome.Installed::class.java)
        val unit = Files.readString(unitFile)
        assertThat(unit).contains("ExecStart=$launcher").contains("WantedBy=default.target")
        assertThat(fake.calls).anyMatch { it.contains("daemon-reload") }
        assertThat(fake.calls).anyMatch { it.contains("enable") && it.contains("--now") && it.contains("shipfloor.service") }
    }

    @Test
    fun `AC-2 AC-6 AC-9 unit restarts on failure, logs to the journal and is hardened`() {
        val unit = ServiceUnit.render(launcher)

        assertThat(unit.lines()).contains(
            "Restart=on-failure",
            "SyslogIdentifier=shipfloor",
            "NoNewPrivileges=true",
            "UMask=0077",
            "EnvironmentFile=-%h/.config/shipfloor/service.env",
        )
        assertThat(unit).doesNotContain("Environment=").doesNotContain("TOKEN").doesNotContain("SECRET")
    }

    @Test
    fun `AC-3 second install is a no-op that leaves one identical unit`() {
        val first = installer()
        first.install()
        val before = Files.readString(unitFile)

        val outcome = installer().install()

        assertThat(outcome).isInstanceOf(ServiceOutcome.AlreadyInstalled::class.java)
        assertThat(Files.readString(unitFile)).isEqualTo(before)
        assertThat(Files.list(unitDir).use { it.toList() }).containsExactly(unitFile)
    }

    @Test
    fun `AC-4 uninstall stops, disables and removes the unit`() {
        installer().install()
        val fake = FakeSystemctl()

        val outcome = installer(fake).uninstall()

        assertThat(outcome).isInstanceOf(ServiceOutcome.Removed::class.java)
        assertThat(Files.exists(unitFile)).isFalse()
        assertThat(fake.calls).anyMatch { it.contains("disable") && it.contains("--now") && it.contains("shipfloor.service") }
        assertThat(fake.calls.last()).contains("daemon-reload")
    }

    @Test
    fun `AC-5 uninstall without a unit succeeds and touches nothing`() {
        val fake = FakeSystemctl()

        val outcome = installer(fake).uninstall()

        assertThat(outcome).isInstanceOf(ServiceOutcome.NotInstalled::class.java)
        assertThat(fake.calls).isEmpty()
        assertThat(Files.exists(unitDir)).isFalse()
    }

    @Test
    fun `AC-5 runServiceCommand uninstall without a unit exits 0`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = runServiceCommand(
            listOf("service", "uninstall"),
            mapOf("XDG_CONFIG_HOME" to root.toString(), "HOME" to root.toString()),
            PrintStream(out),
            PrintStream(err),
        )

        assertThat(code).isZero()
        assertThat(err.toString()).isEmpty()
    }

    @Test
    fun `AC-7 install refuses outside Linux`() {
        val fake = FakeSystemctl()

        val outcome = installer(fake, os = "Mac OS X").install()

        assertThat(outcome).isEqualTo(ServiceOutcome.Unsupported("Mac OS X"))
        assertThat(fake.calls).isEmpty()
        assertThat(Files.exists(unitFile)).isFalse()
    }

    @Test
    fun `AC-8 first install creates a 0600 service env with comments only and later installs keep it`() {
        installer().install()

        assertThat(Files.exists(envFile)).isTrue()
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(envFile))).isEqualTo("rw-------")
        val content = Files.readString(envFile)
        assertThat(content).contains("SHIPFLOOR_DISPATCHER_ENABLED").contains("PATH").contains("JAVA_HOME")
        assertThat(content.lines().filter { it.isNotBlank() }).allMatch { it.trimStart().startsWith("#") }

        Files.writeString(envFile, "SHIPFLOOR_DISPATCHER_ENABLED=1\n")
        installer().install()

        assertThat(Files.readString(envFile)).isEqualTo("SHIPFLOOR_DISPATCHER_ENABLED=1\n")
    }

    @Test
    fun `AC-10 render and install refuse unsafe launcher paths without writing`() {
        val unsafe = listOf("/opt/my dir/shipfloor", "/opt/a\nb/shipfloor", "/opt/50%/shipfloor", "/opt/\$HOME/shipfloor", "/opt/\"q\"/shipfloor", "/opt/'q'/shipfloor")
        for (path in unsafe) {
            val fake = FakeSystemctl()
            val outcome = ServiceInstaller(unitDir, Path.of(path), fake, "Linux").install()

            assertThat(outcome).`as`(path).isInstanceOf(ServiceOutcome.Failed::class.java)
            assertThat(Files.exists(unitFile)).`as`(path).isFalse()
            assertThat(fake.calls).`as`(path).isEmpty()
        }
    }

    @Test
    fun `AC-11 install warns about linger only when the user has none and never enables it`() {
        val noLinger = FakeSystemctl(linger = "Linger=no")
        val warned = installer(noLinger).install()

        assertThat(warned.toString()).contains("enable-linger")
        assertThat(noLinger.calls).noneMatch { it.contains("enable-linger") }

        Files.deleteIfExists(unitFile)
        val withLinger = FakeSystemctl(linger = "Linger=yes")
        val quiet = installer(withLinger).install()

        assertThat(quiet.toString()).doesNotContain("enable-linger")
    }

    @Test
    fun `systemctl failure becomes Failed`() {
        val outcome = installer(FakeSystemctl(failOn = "enable")).install()

        assertThat(outcome).isInstanceOf(ServiceOutcome.Failed::class.java)
    }
}
