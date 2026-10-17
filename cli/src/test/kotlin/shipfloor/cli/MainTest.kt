package shipfloor.cli

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MainTest {
    @TempDir
    lateinit var runtimeDir: Path

    private val out = StringBuilder()
    private val err = StringBuilder()
    private val requests = CopyOnWriteArrayList<String>()
    private var server: ServerSocketChannel? = null

    private fun run(vararg args: String): Int =
        runCli(args.toList(), "1.2.3", { out.appendLine(it) }, { err.appendLine(it) }, env = mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString()))

    private fun socketDir(): Path = runtimeDir.resolve("shipfloor")

    private fun tokenPath(): Path = socketDir().resolve("owner.token")

    /** A fake of the daemon protocol: one request per connection, answered with the lines [reply] returns. */
    private fun fakeDaemon(reply: (String) -> List<String>) {
        Files.createDirectories(socketDir())
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketDir().resolve("daemon.sock")))
        server = channel
        Thread.ofPlatform().daemon().start {
            try {
                while (true) {
                    val client = channel.accept()
                    val input = Channels.newInputStream(client).bufferedReader()
                    val line = input.readLine()
                    if (line != null) {
                        requests.add(line)
                        val output = Channels.newOutputStream(client)
                        reply(line).forEach { output.write((it + "\n").toByteArray()) }
                        output.flush()
                    }
                    client.close()
                }
            } catch (e: ClosedChannelException) {
                // test over
            }
        }
    }

    @AfterEach
    fun stopFake() {
        server?.close()
    }

    @Test
    fun `--version prints the version and exits 0`() {
        assertThat(run("--version")).isZero()
        assertThat(out.toString().trim()).isEqualTo("shipfloor 1.2.3")
        assertThat(err).isEmpty()
    }

    @Test
    fun `an unknown argument prints the usage and exits 2`() {
        assertThat(run("--nope")).isEqualTo(2)
        assertThat(err.toString()).contains("Usage: shipfloor").contains("--nope")
        assertThat(out).isEmpty()
    }

    @Test
    fun `--help prints the usage and exits 0`() {
        assertThat(run("--help")).isZero()
        assertThat(out.toString()).contains("Usage: shipfloor").contains("--version")
    }

    @Test
    fun `the version comes from the build`() {
        assertThat(BuildInfo.version).isNotBlank().doesNotContain("\${")
    }

    @Test
    fun `AC-3 the native image config embeds the build properties resource and --version prints the build version`() {
        val config = MainTest::class.java.getResourceAsStream("/META-INF/native-image/shipfloor/cli/resource-config.json")
            ?.use { it.readBytes().decodeToString() }

        assertThat(config).isNotNull().contains("shipfloor/cli/build.properties")

        val printed = StringBuilder()
        val exit = runCli(listOf("--version"), BuildInfo.version, { printed.appendLine(it) }, { err.appendLine(it) }, mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString()))
        assertThat(exit).isZero()
        assertThat(printed.toString().trim()).isEqualTo("shipfloor ${BuildInfo.version}")
    }

    @Test
    fun `AC-1 card create prints the new card`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        fakeDaemon { listOf("""{"ok":true,"result":{"id":"c1","title":"Write docs","column":"INBOX"}}""") }

        assertThat(run("card", "create", "Write docs")).isZero()

        assertThat(out.toString().trim()).isEqualTo("c1  INBOX  Write docs")
        assertThat(requests.single()).contains("\"card.create\"").contains("\"Write docs\"")
    }

    @Test
    fun `card import-board sends the tsv rows and prints created and ignored cards`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        val tsv = runtimeDir.resolve("board.tsv")
        Files.writeString(tsv, "12\tTechnical Refinement\textra\n\n13\tNowhere\n")
        fakeDaemon {
            listOf(
                """{"ok":true,"result":[{"id":"c12","title":"Twelve","column":"REFINEMENT"}],"ignored":[{"id":"13","reason":"unknown_column"}]}""",
            )
        }

        assertThat(run("card", "import-board", tsv.toString(), "--project", "p1")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() })
            .containsExactly("c12  REFINEMENT  Twelve", "ignorada: #13 (unknown_column)")
        val request = requests.single()
        assertThat(request).contains("\"card.import_board\"").contains("\"project\":\"p1\"")
            .contains("""{"id":"12","column":"Technical Refinement"}""").contains("""{"id":"13","column":"Nowhere"}""")
    }

    @Test
    fun `card import-board fails in portuguese when the file cannot be read`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")

        assertThat(run("card", "import-board", runtimeDir.resolve("missing.tsv").toString())).isEqualTo(1)

        assertThat(err.toString()).contains("não foi possível ler o arquivo")
    }

    @Test
    fun `AC-1 card list prints one line per card`() {
        fakeDaemon {
            listOf(
                """{"ok":true,"result":[{"id":"c1","title":"One","column":"INBOX"},{"id":"c2","title":"Two","column":"CODING"}]}""",
            )
        }

        assertThat(run("card", "list")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() }).containsExactly("c1  INBOX  One", "c2  CODING  Two")
        assertThat(requests.single()).contains("\"card.list\"")
    }

    @Test
    fun `AC-1 card show prints the card and an unknown card is a daemon error`() {
        fakeDaemon { line ->
            if (line.contains("\"c1\"")) {
                listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"VALIDATION"}}""")
            } else {
                listOf("""{"ok":false,"error":"card not found: zz"}""")
            }
        }

        assertThat(run("card", "show", "c1")).isZero()
        assertThat(out.toString().trim()).isEqualTo("c1  VALIDATION  One")

        out.clear()
        assertThat(run("card", "show", "zz")).isEqualTo(1)
        assertThat(err.toString()).contains("card not found: zz")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-1 card move accepts a lowercase dashed column and rejects an unknown one with usage`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        fakeDaemon { listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"READY_FOR_DEV"}}""") }

        assertThat(run("card", "move", "c1", "ready-for-dev")).isZero()
        assertThat(out.toString().trim()).isEqualTo("c1  READY_FOR_DEV  One")
        assertThat(requests.single()).contains("\"card.move\"").contains("\"READY_FOR_DEV\"").contains("\"c1\"")

        requests.clear()
        err.clear()
        assertThat(run("card", "move", "c1", "nowhere")).isEqualTo(2)
        assertThat(err.toString()).contains("INBOX").contains("DONE")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `AC-1 events prints each event line, skips the ack and stops after count`() {
        val event1 = """{"event":{"seq":4,"card":"c1","type":"CARD_CREATED"}}"""
        val event2 = """{"event":{"seq":5,"card":"c1","type":"CARD_MOVED"}}"""
        fakeDaemon { listOf("""{"ok":true,"result":"subscribed"}""", event1, event2) }

        assertThat(run("events", "--after", "3", "--count", "2")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() }).containsExactly(event1, event2)
        assertThat(requests.single()).contains("\"events.subscribe\"").containsPattern("\"after\"\\s*:\\s*3")
    }

    @Test
    fun `AC-1 events exits 1 with the message when the stream ends with an error`() {
        fakeDaemon { listOf("""{"ok":true,"result":"subscribed"}""", """{"error":"subscriber too slow"}""") }

        assertThat(run("events")).isEqualTo(1)
        assertThat(err.toString()).contains("subscriber too slow")
    }

    @Test
    fun `AC-5 approve and request-change send the owner decision and print the card, a daemon refusal exits 1`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon { line ->
            if (line.contains("\"gone\"")) {
                listOf("""{"ok":false,"error":"card gone is in Coding, not in Spec Review"}""")
            } else {
                listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"READY_FOR_DEV"}}""")
            }
        }

        assertThat(run("card", "approve", "c1")).isZero()
        assertThat(out.toString().trim()).isEqualTo("c1  READY_FOR_DEV  One")
        assertThat(run("card", "request-change", "c1", "--note", "Cover offline")).isZero()

        assertThat(requests).hasSize(2)
        assertThat(requests[0]).contains("\"card.approve\"").contains("\"c1\"")
        assertThat(requests[1]).contains("\"card.request-change\"").contains("\"c1\"").contains("Cover offline")
        requests.forEach {
            assertThat(it).containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")
        }

        out.clear()
        assertThat(run("card", "approve", "gone")).isEqualTo(1)
        assertThat(err.toString()).contains("card gone is in Coding")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-6 unblock sends card unblock with the owner proof and prints the card, a daemon refusal exits 1`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon { line ->
            if (line.contains("\"fresh\"")) {
                listOf("""{"ok":false,"error":"card fresh is not blocked"}""")
            } else {
                listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"CODING","blocked":false}}""")
            }
        }

        assertThat(run("card", "unblock", "c1")).isZero()

        assertThat(out.toString().trim()).isEqualTo("c1  CODING  One")
        assertThat(requests.single()).contains("\"card.unblock\"").contains("\"c1\"")
            .containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")

        out.clear()
        assertThat(run("card", "unblock", "fresh")).isEqualTo(1)
        assertThat(err.toString()).contains("card fresh is not blocked")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-7 card list marks only the blocked cards with BLOQUEADO at the end of the line`() {
        fakeDaemon {
            listOf(
                """{"ok":true,"result":[""" +
                    """{"id":"c1","title":"One","column":"INBOX","blocked":false},""" +
                    """{"id":"c2","title":"Two","column":"CODING","blocked":true},""" +
                    """{"id":"c3","title":"Three","column":"DONE"}]}""",
            )
        }

        assertThat(run("card", "list")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() })
            .containsExactly("c1  INBOX  One", "c2  CODING  Two  BLOQUEADO", "c3  DONE  Three")
    }

    @Test
    fun `card rebuild sends the owner request with the project and prints one line per card`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon {
            listOf("""{"ok":true,"result":[{"id":"c1","title":"One","column":"CODING"},{"id":"c2","title":"Two","column":"PR_REVIEW"}]}""")
        }

        assertThat(run("card", "rebuild", "--project", "shipfloor")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() })
            .containsExactly("c1  CODING  One", "c2  PR_REVIEW  Two")
        assertThat(requests).hasSize(1)
        assertThat(requests[0]).contains("\"card.rebuild\"").contains("\"shipfloor\"")
            .containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")
    }

    @Test
    fun `AC-5 request-change without a note is a usage error and sends nothing`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        fakeDaemon { listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"READY_FOR_REFINEMENT"}}""") }

        assertThat(run("card", "request-change", "c1")).isEqualTo(2)

        assertThat(err.toString()).contains("--note")
        assertThat(requests).isEmpty()
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-5 events shows the owner decisions with actor, moment and the change note`() {
        val approved = """{"event":{"seq":7,"card":"c1","at":"2026-10-16T12:00:00Z","actor":"owner","type":"OWNER_APPROVED","payload":{"from":"SPEC_REVIEW","to":"READY_FOR_DEV"}}}"""
        val changed = """{"event":{"seq":8,"card":"c2","at":"2026-10-16T12:01:00Z","actor":"owner","type":"CARD_MOVED","payload":{"from":"SPEC_REVIEW","to":"READY_FOR_REFINEMENT","note":"Cover offline"}}}"""
        fakeDaemon { listOf("""{"ok":true,"result":"subscribed"}""", approved, changed) }

        assertThat(run("events", "--after", "6", "--count", "2")).isZero()

        val lines = out.toString().lines().filter { it.isNotBlank() }
        assertThat(lines).containsExactly(approved, changed)
        assertThat(lines[0]).contains("\"actor\":\"owner\"").contains("2026-10-16T12:00:00Z").contains("OWNER_APPROVED")
        assertThat(lines[1]).contains("\"actor\":\"owner\"").contains("2026-10-16T12:01:00Z").contains("Cover offline")
    }

    @Test
    fun `AC-6 audit export prints the document, defaults to json and needs no token`() {
        fakeDaemon { listOf("""{"ok":true,"text":"event_id,at\ne1,2026-10-16T10:00:00Z\n"}""") }

        assertThat(run("audit", "export", "c1", "--format", "csv")).isZero()
        assertThat(out.toString().trim()).isEqualTo("event_id,at\ne1,2026-10-16T10:00:00Z")
        assertThat(requests[0]).contains("\"audit.export\"").contains("\"c1\"").containsPattern("\"format\"\\s*:\\s*\"csv\"")

        assertThat(run("audit", "export", "c1")).isZero()
        assertThat(requests[1]).containsPattern("\"format\"\\s*:\\s*\"json\"").doesNotContain("proof")
    }

    @Test
    fun `AC-6 audit export of an unknown card or with an invalid format fails with a message and no output`() {
        fakeDaemon { line ->
            if (line.contains("\"zz\"")) {
                listOf("""{"ok":false,"error":"card not found: zz"}""")
            } else {
                listOf("""{"ok":false,"error":"invalid format: xml"}""")
            }
        }

        assertThat(run("audit", "export", "zz", "--format", "json")).isNotZero()
        assertThat(err.toString()).contains("card not found: zz")
        assertThat(out).isEmpty()

        err.clear()
        assertThat(run("audit", "export", "c1", "--format", "xml")).isNotZero()
        assertThat(err.toString()).contains("xml")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-2 a stopped daemon is reported with how to start it and exit 1`() {
        // No socket at all.
        assertThat(run("card", "list")).isEqualTo(1)
        assertThat(err.toString()).contains("daemon não está rodando").contains("./gradlew :daemon:run")

        // A stale socket file nobody listens on, with a token present.
        err.clear()
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
            it.bind(UnixDomainSocketAddress.of(socketDir().resolve("daemon.sock")))
        }
        assertThat(run("card", "create", "x")).isEqualTo(1)
        assertThat(err.toString()).contains("daemon não está rodando").contains("./gradlew :daemon:run")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-4 create and move send the owner token as proof with the owner actor`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon { listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"INBOX"}}""") }

        assertThat(run("card", "create", "One")).isZero()
        assertThat(run("card", "move", "c1", "ready_for_refinement")).isZero()

        assertThat(requests).hasSize(2)
        requests.forEach {
            assertThat(it).containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")
        }
    }

    @Test
    fun `AC-4 reads never send the token`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon { listOf("""{"ok":true,"result":[]}""") }

        assertThat(run("card", "list")).isZero()

        assertThat(requests.single()).doesNotContain("secret-token")
    }

    @Test
    fun `AC-5 a missing, empty or unreadable token fails before any request reaches the daemon`() {
        fakeDaemon { listOf("""{"ok":true,"result":{"id":"c1","title":"One","column":"INBOX"}}""") }

        // Missing.
        assertThat(run("card", "create", "One")).isEqualTo(1)
        assertThat(err.toString()).contains(tokenPath().toString()).contains("inicie o daemon")

        // Empty.
        err.clear()
        Files.writeString(tokenPath(), "")
        assertThat(run("card", "move", "c1", "refinement")).isEqualTo(1)
        assertThat(err.toString()).contains(tokenPath().toString()).contains("inicie o daemon")

        // Unreadable: a directory where the file should be.
        err.clear()
        Files.delete(tokenPath())
        Files.createDirectory(tokenPath())
        assertThat(run("card", "create", "One")).isEqualTo(1)
        assertThat(err.toString()).contains(tokenPath().toString()).contains("inicie o daemon")

        assertThat(out).isEmpty()
        assertThat(requests).isEmpty()
    }

    // ---- shipfloor project ----

    private fun projectDir(): Path = Files.createDirectories(runtimeDir.resolve("repos").resolve("alpha"))

    @Test
    fun `AC-1 project add registers the canonical path with the owner proof and prints name and repo`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        val dir = projectDir()
        val canonical = dir.toRealPath().toString()
        fakeDaemon { line ->
            if (line.contains("\"project.list\"")) {
                listOf("""{"ok":true,"result":[]}""")
            } else {
                listOf("""{"ok":true,"result":{"name":"alpha","repo":"acme/alpha","layers":[{"name":"core"}]}}""")
            }
        }

        assertThat(run("project", "add", dir.toString())).isZero()

        assertThat(out.toString().trim()).isEqualTo("alpha  acme/alpha")
        val add = requests.single { it.contains("\"project.add\"") }
        assertThat(add).contains(canonical)
            .containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")
    }

    @Test
    fun `AC-2 project add fails with the daemon message and a non-zero exit`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        fakeDaemon { line ->
            if (line.contains("\"project.list\"")) {
                listOf("""{"ok":true,"result":[]}""")
            } else {
                listOf("""{"ok":false,"error":"factory.yml invalid: layers[0].test is required"}""")
            }
        }

        assertThat(run("project", "add", runtimeDir.resolve("not-a-repo").toString())).isNotZero()

        assertThat(err.toString()).contains("factory.yml invalid: layers[0].test is required")
        assertThat(out).isEmpty()
    }

    @Test
    fun `AC-3 project add on an already registered path exits 0 without sending project add`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        val dir = projectDir()
        val canonical = dir.toRealPath().toString()
        fakeDaemon { line ->
            if (line.contains("\"project.list\"")) {
                listOf("""{"ok":true,"result":[{"name":"alpha","repo":"$canonical","layers":[]}]}""")
            } else {
                listOf("""{"ok":false,"error":"project already registered: alpha"}""")
            }
        }

        assertThat(run("project", "add", dir.toString())).isZero()

        assertThat(out.toString().trim()).isEqualTo("alpha  $canonical")
        assertThat(err).isEmpty()
        assertThat(requests.none { it.contains("\"project.add\"") }).isTrue()
    }

    @Test
    fun `AC-4 project list prints name and repo per project, without layers or the token`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon {
            listOf(
                """{"ok":true,"result":[{"name":"alpha","repo":"acme/alpha","layers":[{"name":"core","test":"gradle test"}]},{"name":"beta","repo":"acme/beta","layers":[]}]}""",
            )
        }

        assertThat(run("project", "list")).isZero()

        assertThat(out.toString().lines().filter { it.isNotBlank() }).containsExactly("alpha  acme/alpha", "beta  acme/beta")
        assertThat(out.toString()).doesNotContain("layers").doesNotContain("gradle test")
        assertThat(requests.single()).contains("\"project.list\"").doesNotContain("secret-token")
    }

    // ---- shipfloor init ----

    private val factoryYml = ".shipfloor/factory.yml"
    private val contextDocs = listOf("PRODUCT.md", "ARCHITECTURE.md", "DESIGN.md")

    /** A fake git: [isRepo] answers rev-parse, and `git show main:<file>` prints content only for the [onMain] files. */
    private fun fakeGit(isRepo: Boolean = true, onMain: Set<String> = emptySet()): (List<String>) -> String? = { command ->
        val show = command.indexOf("show")
        when {
            command.contains("rev-parse") -> if (isRepo) ".git\n" else null
            show >= 0 -> command.getOrNull(show + 1)?.takeIf { it.startsWith("main:") && it.removePrefix("main:") in onMain }?.let { "content\n" }
            else -> null
        }
    }

    private fun runInit(path: Path, git: (List<String>) -> String?): Int =
        runCli(listOf("init", path.toString()), "1.2.3", { out.appendLine(it) }, { err.appendLine(it) }, mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString()), git)

    private fun registerableDaemon(registered: Boolean = false, canonical: String = "") {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "secret-token\n")
        fakeDaemon { line ->
            when {
                line.contains("\"project.list\"") ->
                    listOf(if (registered) """{"ok":true,"result":[{"name":"alpha","repo":"$canonical","layers":[]}]}""" else """{"ok":true,"result":[]}""")
                else -> listOf("""{"ok":true,"result":{"name":"alpha","repo":"acme/alpha","layers":[{"name":"core"}]}}""")
            }
        }
    }

    private fun addRequests(): List<String> = requests.filter { it.contains("\"project.add\"") }

    @Test
    fun `AC-1 init on a directory that is not a git repository exits 1 with git init guidance and sends no project add`() {
        registerableDaemon()

        assertThat(runInit(projectDir(), fakeGit(isRepo = false))).isEqualTo(1)

        assertThat(err.toString()).contains("git init")
        assertThat(addRequests()).isEmpty()
    }

    @Test
    fun `AC-2 init on a repository without factory yml on main exits 1 naming the file and the branch and sends no project add`() {
        registerableDaemon()

        assertThat(runInit(projectDir(), fakeGit(onMain = setOf("PRODUCT.md")))).isEqualTo(1)

        assertThat(err.toString()).contains(factoryYml).contains("main")
        assertThat(addRequests()).isEmpty()
    }

    @Test
    fun `AC-3 init registers the project with the owner proof and prints the project line and the readiness score`() {
        registerableDaemon()
        val dir = projectDir()

        assertThat(runInit(dir, fakeGit(onMain = setOf(factoryYml) + contextDocs))).isZero()

        val add = addRequests().single()
        assertThat(add).contains(dir.toRealPath().toString())
            .containsPattern("\"proof\"\\s*:\\s*\"secret-token\"").containsPattern("\"actor\"\\s*:\\s*\"owner\"")
        val lines = out.toString().lines().filter { it.isNotBlank() }
        assertThat(lines).contains("alpha  acme/alpha")
        assertThat(out.toString()).contains("3/3").doesNotContain("ausente")
    }

    @Test
    fun `AC-4 init with missing context documents exits 0 and lists each one as ausente`() {
        registerableDaemon()

        assertThat(runInit(projectDir(), fakeGit(onMain = setOf(factoryYml, "PRODUCT.md")))).isZero()

        val lines = out.toString().lines()
        assertThat(out.toString()).contains("1/3")
        assertThat(lines.filter { it.contains("ausente") }).hasSize(2)
        assertThat(lines.single { it.contains("ARCHITECTURE.md") }).contains("ausente")
        assertThat(lines.single { it.contains("DESIGN.md") }).contains("ausente")
        assertThat(lines.filter { it.contains("PRODUCT.md") }).noneMatch { it.contains("ausente") }
        assertThat(addRequests()).hasSize(1)
    }

    @Test
    fun `AC-5 init on an already registered project sends no second project add, prints the score and exits 0`() {
        val dir = projectDir()
        registerableDaemon(registered = true, canonical = dir.toRealPath().toString())

        assertThat(runInit(dir, fakeGit(onMain = setOf(factoryYml, "PRODUCT.md", "DESIGN.md")))).isZero()

        assertThat(addRequests()).isEmpty()
        assertThat(out.toString()).contains("2/3")
        assertThat(err).isEmpty()
    }

    @Test
    fun `AC-6 init shows the daemon refusal with the field name and exits 1`() {
        Files.createDirectories(socketDir())
        Files.writeString(tokenPath(), "tok\n")
        fakeDaemon { line ->
            if (line.contains("\"project.list\"")) {
                listOf("""{"ok":true,"result":[]}""")
            } else {
                listOf("""{"ok":false,"error":"factory.yml invalid: layers[0].test is required"}""")
            }
        }

        assertThat(runInit(projectDir(), fakeGit(onMain = setOf(factoryYml)))).isEqualTo(1)

        assertThat(err.toString()).contains("layers[0].test")
    }

    // ---- shipfloor doctor ----

    private val checkNames = listOf("java", "git", "gh", "claude", "socket", "banco", "serviço", "permissões", "ambiente do serviço")
    private val secret = "s3cr3t-do-not-print"

    private fun binDir(): Path = runtimeDir.resolve("bin")

    private fun dataHome(): Path = runtimeDir.resolve("data")

    private fun configHome(): Path = runtimeDir.resolve("config")

    private fun serviceEnvPath(): Path = configHome().resolve("shipfloor").resolve("service.env")

    private fun chmod(path: Path, mode: String) = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))

    private fun fakeTool(name: String) {
        Files.createDirectories(binDir())
        val file = binDir().resolve(name)
        Files.writeString(file, "#!/bin/sh\nexit 0\n")
        chmod(file, "rwxr-xr-x")
    }

    /** A machine where every check passes: tools on PATH, daemon listening, database, unit, token, service.env. */
    private fun healthyMachine() {
        listOf("java", "git", "gh", "claude").forEach { fakeTool(it) }
        fakeDaemon { listOf("""{"ok":true,"result":[]}""") }
        chmod(socketDir(), "rwx------")
        Files.writeString(tokenPath(), "$secret\n")
        chmod(tokenPath(), "rw-------")
        val dataDir = Files.createDirectories(dataHome().resolve("shipfloor"))
        chmod(dataDir, "rwx------")
        Files.writeString(dataDir.resolve("shipfloor.db"), "")
        Files.createDirectories(configHome().resolve("systemd").resolve("user"))
        Files.writeString(configHome().resolve("systemd").resolve("user").resolve("shipfloor.service"), "[Service]\n")
        Files.createDirectories(serviceEnvPath().parent)
        Files.writeString(serviceEnvPath(), "TOKEN=$secret\n")
        chmod(serviceEnvPath(), "rw-------")
    }

    private fun runDoctor(): Int =
        runCli(
            listOf("doctor"),
            "1.2.3",
            { out.appendLine(it) },
            { err.appendLine(it) },
            env = mapOf(
                "XDG_RUNTIME_DIR" to runtimeDir.toString(),
                "XDG_DATA_HOME" to dataHome().toString(),
                "XDG_CONFIG_HOME" to configHome().toString(),
                "HOME" to runtimeDir.resolve("home").toString(),
                "PATH" to binDir().toString(),
            ),
        )

    private fun doctorLines(): List<String> = out.toString().lines().filter { it.isNotBlank() }

    private fun runDoctorOnDesktop(desktop: String?, runCommand: (List<String>) -> String?): Int =
        runCli(
            listOf("doctor"),
            "1.2.3",
            { out.appendLine(it) },
            { err.appendLine(it) },
            env = buildMap {
                put("XDG_RUNTIME_DIR", runtimeDir.toString())
                put("XDG_DATA_HOME", dataHome().toString())
                put("XDG_CONFIG_HOME", configHome().toString())
                put("HOME", runtimeDir.resolve("home").toString())
                put("PATH", binDir().toString())
                if (desktop != null) put("XDG_CURRENT_DESKTOP", desktop)
            },
            runCommand = runCommand,
        )

    private fun extensions(enabled: String, installed: String = enabled): (List<String>) -> String? = { command ->
        if (command.contains("--enabled")) enabled else installed
    }

    @Test
    fun `AC-6 doctor warns that the tray will not appear when the GNOME AppIndicator extension is off`() {
        healthyMachine()

        val exit = runDoctorOnDesktop("ubuntu:GNOME", extensions(enabled = "other@example.org\n", installed = "other@example.org\n"))

        assertThat(doctorLines().filter { it.startsWith("AVISO") }).hasSize(1)
        val warning = doctorLines().single { it.startsWith("AVISO") }
        assertThat(warning).contains("A bandeja não vai aparecer")
        assertThat(warning).contains("gnome-extensions enable appindicatorsupport@rgcjonas.gmail.com")
        assertThat(exit).isZero()
        assertThat(doctorLines().filter { it.startsWith("OK") || it.startsWith("FAIL") }).hasSize(checkNames.size)
    }

    @Test
    fun `AC-6 the warning names the installed AppIndicator uuid when there is one`() {
        healthyMachine()

        runDoctorOnDesktop("GNOME", extensions(enabled = "", installed = "appindicatorsupport@ubuntu.com\nother@example.org\n"))

        assertThat(doctorLines().filter { it.startsWith("AVISO") }).hasSize(1)
        assertThat(doctorLines().single { it.startsWith("AVISO") }).contains("gnome-extensions enable appindicatorsupport@ubuntu.com")
    }

    @Test
    fun `AC-6 no warning when the extension is on, outside GNOME, or without gnome-extensions`() {
        healthyMachine()

        runDoctorOnDesktop("GNOME", extensions(enabled = "appindicatorsupport@rgcjonas.gmail.com\n"))
        assertThat(doctorLines()).hasSize(checkNames.size)
        assertThat(out.toString()).doesNotContain("AVISO").doesNotContain("appindicator")

        out.clear()
        runDoctorOnDesktop("KDE", extensions(enabled = ""))
        assertThat(doctorLines()).hasSize(checkNames.size)

        out.clear()
        runDoctorOnDesktop(null, extensions(enabled = ""))
        assertThat(doctorLines()).hasSize(checkNames.size)

        out.clear()
        val exit = runDoctorOnDesktop("GNOME") { null }
        assertThat(doctorLines()).hasSize(checkNames.size)
        assertThat(exit).isZero()
    }

    private fun checkLine(name: String): String =
        doctorLines().singleOrNull { it.startsWith("OK $name") || it.startsWith("FAIL $name") }
            ?: error("no line for check '$name' in:\n$out")

    @Test
    fun `AC-1 doctor prints one OK or FAIL line per check`() {
        healthyMachine()

        runDoctor()

        assertThat(doctorLines()).hasSize(checkNames.size)
        checkNames.forEach { assertThat(checkLine(it)).startsWith("OK $it") }
    }

    @Test
    fun `AC-2 a failing check prints FAIL with a concrete instruction`() {
        healthyMachine()
        Files.delete(binDir().resolve("gh"))

        runDoctor()

        val line = checkLine("gh")
        assertThat(line).startsWith("FAIL gh — ")
        assertThat(line.substringAfter("FAIL gh — ").trim()).isNotBlank()
    }

    @Test
    fun `AC-3 doctor exits 0 when every check passes`() {
        healthyMachine()

        assertThat(runDoctor()).isZero()

        assertThat(doctorLines().filter { it.startsWith("FAIL") }).isEmpty()
        assertThat(err).isEmpty()
    }

    @Test
    fun `AC-4 one failing check exits non-zero and the other checks are still listed`() {
        healthyMachine()
        Files.delete(binDir().resolve("claude"))

        assertThat(runDoctor()).isEqualTo(1)

        assertThat(doctorLines()).hasSize(checkNames.size)
        assertThat(checkLine("claude")).startsWith("FAIL claude")
        checkNames.filter { it != "claude" }.forEach { assertThat(checkLine(it)).startsWith("OK $it") }
    }

    @Test
    fun `AC-5 doctor without a running daemon fails socket, banco and serviço with hints and no error`() {
        listOf("java", "git", "gh", "claude").forEach { fakeTool(it) }

        assertThat(runDoctor()).isEqualTo(1)

        listOf("socket", "banco", "serviço").forEach {
            val line = checkLine(it)
            assertThat(line).startsWith("FAIL $it — ")
            assertThat(line.substringAfter("— ").trim()).isNotBlank()
        }
        assertThat(doctorLines()).hasSize(checkNames.size)
        assertThat(err).isEmpty()
    }

    @Test
    fun `AC-5 a stale socket file nobody listens on is a FAIL for socket`() {
        listOf("java", "git", "gh", "claude").forEach { fakeTool(it) }
        Files.createDirectories(socketDir())
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
            it.bind(UnixDomainSocketAddress.of(socketDir().resolve("daemon.sock")))
        }

        assertThat(runDoctor()).isEqualTo(1)

        assertThat(checkLine("socket")).startsWith("FAIL socket — ")
    }

    @Test
    fun `AC-6 doctor output never contains the token or the service env content`() {
        healthyMachine()
        runDoctor()
        assertThat(doctorLines()).hasSize(checkNames.size)
        assertThat(out.toString() + err.toString()).doesNotContain(secret)

        // Also on the failure path, where modes and paths are printed.
        out.clear()
        err.clear()
        chmod(tokenPath(), "rw-r--r--")
        chmod(serviceEnvPath(), "rw-r--r--")
        runDoctor()
        assertThat(checkLine("permissões")).startsWith("FAIL permissões")
        assertThat(out.toString() + err.toString()).doesNotContain(secret).doesNotContain("TOKEN=")
    }

    @Test
    fun `AC-7 a token more open than 0600 fails permissões with the chmod 600 command and the path`() {
        healthyMachine()
        chmod(tokenPath(), "rw-r--r--")

        assertThat(runDoctor()).isEqualTo(1)

        val line = checkLine("permissões")
        assertThat(line).startsWith("FAIL permissões")
        assertThat(line).contains("chmod 600").contains(tokenPath().toString())
        assertThat(out.toString()).doesNotContain(secret)
    }

    @Test
    fun `AC-7 a data directory open to group or others fails permissões with the chmod 700 command`() {
        healthyMachine()
        val dataDir = dataHome().resolve("shipfloor")
        chmod(dataDir, "rwxr-xr-x")

        assertThat(runDoctor()).isEqualTo(1)

        val line = checkLine("permissões")
        assertThat(line).startsWith("FAIL permissões")
        assertThat(line).contains("chmod 700").contains(dataDir.toString())
    }

    @Test
    fun `AC-8 ambiente do serviço checks service env mode 0600 and warns about the systemd PATH`() {
        healthyMachine()

        runDoctor()

        val ok = checkLine("ambiente do serviço")
        assertThat(ok).startsWith("OK ambiente do serviço").contains("PATH").contains("systemd")

        out.clear()
        chmod(serviceEnvPath(), "rw-rw-r--")
        assertThat(runDoctor()).isEqualTo(1)
        assertThat(checkLine("ambiente do serviço")).startsWith("FAIL ambiente do serviço").contains("chmod 600")

        out.clear()
        Files.delete(serviceEnvPath())
        assertThat(runDoctor()).isEqualTo(1)
        assertThat(checkLine("ambiente do serviço")).startsWith("FAIL ambiente do serviço").contains(serviceEnvPath().toString())
    }
}
