package shipfloor.daemon

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.DeliveryMode
import shipfloor.core.ExternalEffect
import shipfloor.core.Signer
import shipfloor.ports.Runtime
import shipfloor.ports.SessionRequest
import shipfloor.ports.SessionResult
import shipfloor.store.Database
import shipfloor.store.ProjectPathStore

class ProjectRegistryTest {

    @TempDir
    lateinit var dir: Path

    private val servers = mutableListOf<SocketServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { runCatching { it.close() } }
    }

    /** Serves factory.yml text per repository; a missing entry means the file is absent on main. */
    private class FakeSource(private val files: MutableMap<Path, String> = mutableMapOf()) : ProjectConfigSource {
        override fun read(repo: Path): String? = files[repo.toRealPath()]

        fun put(repo: Path, yaml: String) {
            files[repo.toRealPath()] = yaml
        }
    }

    private fun aRepo(name: String): Path {
        val repo = Files.createDirectories(dir.resolve(name))
        Files.createDirectories(repo.resolve(".git"))
        return repo
    }

    private fun validYaml(name: String = "alpha") = """
        project:
          name: $name
        delivery_mode: pr
        signers:
          close: policy
        layers:
          - name: core
            path: core/
          - name: daemon
            path: daemon/
    """.trimIndent()

    @Test
    fun `AC-1 a valid factory yml registers the project with name layers and policies`() {
        val repo = aRepo("alpha")
        val source = FakeSource().also { it.put(repo, validYaml()) }
        val registry = ProjectRegistry(source)

        val registered = registry.register(repo)

        assertThat(registered.name).isEqualTo("alpha")
        assertThat(registered.layers).containsExactly(ProjectLayer("core", "core/"), ProjectLayer("daemon", "daemon/"))
        assertThat(registered.policies.deliveryMode).isEqualTo(DeliveryMode.PR_HANDOFF)
        assertThat(registered.policies.signers.getValue(ExternalEffect.CLOSE)).isEqualTo(Signer.POLICY)
        assertThat(registry.list()).containsExactly(registered)
    }

    @Test
    fun `the factory yml of this repository registers despite the keys the registry does not read`() {
        val repo = aRepo("self")
        val real = Files.readString(Path.of("..", ".shipfloor", "factory.yml"))
        val source = FakeSource().also { it.put(repo, real) }

        val registered = ProjectRegistry(source).register(repo)

        assertThat(registered.name).isEqualTo("shipfloor")
        assertThat(registered.layers.map { it.name }).contains("core", "store", "ports", "adapters", "daemon", "cli")
        assertThat(registered.layers.first { it.name == "core" }.path).isEqualTo("core/")
    }

    @Test
    fun `layer build test and ran are read from factory yml as tokens and text`() {
        val repo = aRepo("commands")
        val yaml = """
            project:
              name: commands
            layers:
              - name: daemon
                path: daemon/
                build: ../gradlew :daemon:build --console=plain
                test: ../gradlew :daemon:test --tests {selector}
                ran: 'PASSED'
              - name: core
                path: core/
        """.trimIndent()
        val source = FakeSource().also { it.put(repo, yaml) }

        val registered = ProjectRegistry(source).register(repo)

        val daemon = registered.layers.first { it.name == "daemon" }
        assertThat(daemon.build).containsExactly("../gradlew", ":daemon:build", "--console=plain")
        assertThat(daemon.test).containsExactly("../gradlew", ":daemon:test", "--tests", "{selector}")
        assertThat(daemon.ran).isEqualTo("PASSED")
        val core = registered.layers.first { it.name == "core" }
        assertThat(core.build).isNull()
        assertThat(core.test).isNull()
        assertThat(core.ran).isNull()
    }

    @Test
    fun `a layer test command that is not text is refused naming the field`() {
        val repo = aRepo("bad-test")
        val yaml = "project:\n  name: bad-test\nlayers:\n  - name: core\n    path: core/\n    test: [a, b]\n"
        val source = FakeSource().also { it.put(repo, yaml) }

        val refused = assertThrows(InvalidProjectConfigException::class.java) { ProjectRegistry(source).register(repo) }

        assertThat(refused.field).isEqualTo("layers[0].test")
    }

    @Test
    fun `unknown keys are ignored but an invalid known field still names itself`() {
        val ok = aRepo("extra-keys")
        val bad = aRepo("extra-keys-bad")
        val source = FakeSource().also {
            it.put(ok, validYaml().replace("project:\n  name: alpha", "project:\n  name: alpha\n  repo: o/r") + "\nboard: {owner: o, number: 4}\n")
            it.put(bad, validYaml().replace("close: policy", "close: nobody") + "\nboard: {owner: o, number: 4}\n")
        }
        val registry = ProjectRegistry(source)

        assertThat(registry.register(ok).name).isEqualTo("alpha")
        assertThat(assertThrows(InvalidProjectConfigException::class.java) { registry.register(bad) }.field).isEqualTo("signers.close")
    }

    @Test
    fun `AC-2 an invalid factory yml is refused and the reply names the invalid field`() {
        val repo = aRepo("bad-policy")
        val noName = aRepo("no-name")
        val noLayers = aRepo("no-layers")
        val source = FakeSource().also {
            it.put(repo, validYaml().replace("close: policy", "close: nobody"))
            it.put(noName, validYaml().replace("name: alpha", "name: ''"))
            it.put(noLayers, "project:\n  name: alpha\nlayers: []\n")
        }
        val registry = ProjectRegistry(source)

        val policy = assertThrows(InvalidProjectConfigException::class.java) { registry.register(repo) }
        val name = assertThrows(InvalidProjectConfigException::class.java) { registry.register(noName) }
        val layers = assertThrows(InvalidProjectConfigException::class.java) { registry.register(noLayers) }

        assertThat(policy.field).isEqualTo("signers.close")
        assertThat(policy.message).contains("signers.close")
        assertThat(name.field).isEqualTo("project.name")
        assertThat(layers.field).isEqualTo("layers")
    }

    @Test
    fun `AC-3 a refused registration leaves the project out of the listing`() {
        val bad = aRepo("bad")
        val missing = aRepo("missing")
        val good = aRepo("good")
        val source = FakeSource().also {
            it.put(bad, validYaml().replace("close: policy", "close: nobody"))
            it.put(good, validYaml("good"))
        }
        val registry = ProjectRegistry(source)

        assertThrows(InvalidProjectConfigException::class.java) { registry.register(bad) }
        assertThrows(ProjectNotFoundException::class.java) { registry.register(missing) }

        assertThat(registry.list()).isEmpty()
        registry.register(good)
        assertThat(registry.list().map { it.name }).containsExactly("good")
    }

    @Test
    fun `AC-4 values come from main even when a branch changes the factory yml`() {
        val repo = aRepo("gitrepo")
        Files.delete(repo.resolve(".git"))
        git(repo, "init", "-q", "-b", "main")
        Files.createDirectories(repo.resolve(".shipfloor"))
        val file = repo.resolve(".shipfloor/factory.yml")
        Files.writeString(file, validYaml("on-main"))
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "main config")
        git(repo, "checkout", "-q", "-b", "feat/change")
        Files.writeString(file, validYaml("on-branch"))
        git(repo, "commit", "-q", "-am", "branch config")

        val source = GitProjectConfigSource()
        val registered = ProjectRegistry(source).register(repo)

        assertThat(registered.name).isEqualTo("on-main")
        assertThat(source.read(repo)).contains("on-main").doesNotContain("on-branch")
        // Uncommitted edits in the working tree do not count either.
        Files.writeString(file, validYaml("dirty"))
        assertThat(ProjectRegistry(source).register(repo).name).isEqualTo("on-main")
    }

    @Test
    fun `AC-5 a repository without factory yml on main is refused as not found`() {
        val repo = aRepo("empty")
        val registry = ProjectRegistry(FakeSource())

        val failure = assertThrows(ProjectNotFoundException::class.java) { registry.register(repo) }

        assertThat(failure.message).contains(repo.toRealPath().toString())
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `AC-6 registering the same repository twice is refused and the listing keeps one entry`() {
        val repo = aRepo("alpha")
        val source = FakeSource().also { it.put(repo, validYaml()) }
        val registry = ProjectRegistry(source)
        val first = registry.register(repo)
        // The same repository reached through another path spelling.
        val alias = Files.createSymbolicLink(dir.resolve("alias"), repo)

        val byPath = assertThrows(ProjectAlreadyRegisteredException::class.java) { registry.register(repo) }
        val bySymlink = assertThrows(ProjectAlreadyRegisteredException::class.java) { registry.register(alias) }

        assertThat(byPath.message).contains(repo.toRealPath().toString())
        assertThat(bySymlink.message).contains(repo.toRealPath().toString())
        assertThat(registry.list()).containsExactly(first)
    }

    @Test
    fun `scope AC-5 the forbidden list and layers come from main, not from the edited working tree`() {
        val repo = aRepo("scoped")
        Files.delete(repo.resolve(".git"))
        git(repo, "init", "-q", "-b", "main")
        Files.createDirectories(repo.resolve(".shipfloor"))
        val file = repo.resolve(".shipfloor/factory.yml")
        Files.writeString(file, validYaml("scoped") + "\nforbidden:\n  - gradle/wrapper/\n  - docs/private\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "main config")
        git(repo, "checkout", "-q", "-b", "feat/loosen")
        Files.writeString(file, validYaml("scoped").replace("path: core/", "path: .") + "\nforbidden: []\n")

        val registered = ProjectRegistry(GitProjectConfigSource()).register(repo)

        assertThat(registered.forbidden).containsExactly("gradle/wrapper/", "docs/private")
        assertThat(registered.layers).containsExactly(ProjectLayer("core", "core/"), ProjectLayer("daemon", "daemon/"))
        // The gate built on that project still rejects the path the working tree tried to release.
        val verdict = ScopeGate.check(registered, listOf("gradle/wrapper/gradle-wrapper.jar"), setOf("daemon"))
        assertThat(verdict).isInstanceOf(ScopeVerdict.Violations::class.java)
    }

    @Test
    fun `scope forbidden is optional and empty by default`() {
        val repo = aRepo("no-forbidden")
        val source = FakeSource().also { it.put(repo, validYaml()) }

        assertThat(ProjectRegistry(source).register(repo).forbidden).isEmpty()
    }

    @Test
    fun `scope an invalid forbidden item is refused naming forbidden`() {
        val invalid = listOf(
            "forbidden: build/\n",
            "forbidden:\n  - 5\n",
            "forbidden:\n  - ''\n",
            "forbidden:\n  - /etc\n",
            "forbidden:\n  - a/../b\n",
        )
        val registry = ProjectRegistry(FakeSource().also { source ->
            invalid.forEachIndexed { index, extra -> source.put(aRepo("bad-forbidden-$index"), validYaml() + "\n" + extra) }
        })

        invalid.indices.forEach { index ->
            val failure = assertThrows(InvalidProjectConfigException::class.java) {
                registry.register(dir.resolve("bad-forbidden-$index"))
            }
            assertThat(failure.field).startsWith("forbidden")
            assertThat(failure.message).contains("forbidden")
        }
        assertThat(registry.list()).isEmpty()
    }

    private fun refusedField(yamlTail: String, name: String): String {
        val repo = aRepo(name)
        val registry = ProjectRegistry(FakeSource().also { it.put(repo, validYaml() + "\n" + yamlTail) })
        val failure = assertThrows(InvalidProjectConfigException::class.java) { registry.register(repo) }
        assertThat(registry.list()).isEmpty()
        assertThat(failure.message).contains(failure.field)
        return failure.field
    }

    @Test
    fun `AC-4 of #197 an unknown tracker or a jira tracker without site or project is refused naming the key`() {
        assertThat(refusedField("tracker: gitlab\n", "tracker-unknown")).isEqualTo("tracker")
        assertThat(refusedField("tracker: [jira]\n", "tracker-list")).isEqualTo("tracker")
        assertThat(refusedField("tracker: jira\njira_project: PROJ\n", "jira-no-site")).isEqualTo("jira_site")
        assertThat(refusedField("tracker: jira\njira_site: acme.atlassian.net\n", "jira-no-project")).isEqualTo("jira_project")

        val github = aRepo("tracker-github")
        val jira = aRepo("tracker-jira")
        val registry = ProjectRegistry(FakeSource().also {
            it.put(github, validYaml("tracker-github") + "\ntracker: github\n")
            it.put(jira, validYaml("tracker-jira") + "\ntracker: jira\njira_site: acme.atlassian.net\njira_project: PROJ\n")
        })
        assertThat(registry.register(github).name).isEqualTo("tracker-github")
        assertThat(registry.register(jira).name).isEqualTo("tracker-jira")
    }

    @Test
    fun `AC-5 of #197 a jira site or project outside the allowed format is refused at registration naming the key`() {
        val jira = "tracker: jira\n"
        val badSites = listOf("Acme.atlassian.net", "-acme.net", "acme atlassian.net", "acme.net/evil", "a".repeat(64))
        val badProjects = listOf("proj", "P", "1PROJ", "PROJ-1", "A" + "B".repeat(20), "PROJ\" OR 1=1")

        badSites.forEachIndexed { index, site ->
            assertThat(refusedField("${jira}jira_site: '$site'\njira_project: PROJ\n", "bad-site-$index")).describedAs(site).isEqualTo("jira_site")
        }
        badProjects.forEachIndexed { index, project ->
            assertThat(refusedField("${jira}jira_site: acme.atlassian.net\njira_project: '$project'\n", "bad-project-$index"))
                .describedAs(project).isEqualTo("jira_project")
        }
    }

    // ---- #210: registered projects persist across daemon restarts ----

    private class RecordingLog : DaemonLog {
        val events = mutableListOf<Pair<String, Map<String, String>>>()

        override fun log(event: String, fields: Map<String, String>) {
            events.add(event to fields)
        }
    }

    private val fixedClock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC)

    /** Runs [block] with a registry on the database file [name], like one daemon run; the file outlives the call. */
    private fun <T> daemonRun(
        name: String,
        source: ProjectConfigSource,
        log: DaemonLog = RecordingLog(),
        block: (ProjectRegistry) -> T,
    ): T = Database.open(dir.resolve(name)).use { db ->
        block(ProjectRegistry(source, ProjectPathStore(db), log, fixedClock))
    }

    @Test
    fun `AC-1 persisted a project added before a restart is listed after restore without a new add`() {
        val repo = aRepo("alpha")
        val other = aRepo("beta")
        val source = FakeSource().also { it.put(repo, validYaml("alpha")); it.put(other, validYaml("beta")) }
        val registeredBefore = daemonRun("restart.db", source) { listOf(it.register(repo), it.register(other)) }

        val restarted = daemonRun("restart.db", source) { registry ->
            assertThat(registry.list()).isEmpty()
            registry.restore()
            registry.list()
        }

        assertThat(restarted).containsExactlyElementsOf(registeredBefore)
    }

    @Test
    fun `AC-2 persisted the restored project uses the factory yml of main as it is now`() {
        val repo = aRepo("alpha")
        val source = FakeSource().also { it.put(repo, validYaml("alpha")) }
        daemonRun("policy.db", source) { it.register(repo) }
        source.put(repo, validYaml("renamed").replace("close: policy", "close: owner").replace("name: daemon", "name: app").replace("path: daemon/", "path: app/"))

        val restored = daemonRun("policy.db", source) { registry -> registry.restore(); registry.list().single() }

        assertThat(restored.name).isEqualTo("renamed")
        assertThat(restored.layers).containsExactly(ProjectLayer("core", "core/"), ProjectLayer("app", "app/"))
        assertThat(restored.policies.signers.getValue(ExternalEffect.CLOSE)).isEqualTo(Signer.OWNER)
    }

    @Test
    fun `AC-3 persisted a missing path or invalid factory yml is logged by path and field and the others stay`() {
        val gone = aRepo("gone")
        val broken = aRepo("broken")
        val good = aRepo("good")
        val source = FakeSource().also {
            it.put(gone, validYaml("gone")); it.put(broken, validYaml("broken")); it.put(good, validYaml("good"))
        }
        daemonRun("failing.db", source) { registry -> listOf(gone, broken, good).forEach { registry.register(it) } }
        val goneCanonical = gone.toRealPath().toString()
        val brokenCanonical = broken.toRealPath().toString()
        source.put(broken, validYaml("broken").replace("close: policy", "close: nobody"))
        Files.delete(gone.resolve(".git"))
        Files.delete(gone)
        val log = RecordingLog()

        val restored = daemonRun("failing.db", source, log) { registry -> registry.restore(); registry.list() }

        assertThat(restored.map { it.name }).containsExactly("good")
        val failures = log.events.filter { it.first == "project_restore_failed" }.map { it.second }
        assertThat(failures).hasSize(2)
        assertThat(failures.first { it["path"] == brokenCanonical }["field"]).isEqualTo("signers.close")
        assertThat(failures.first { it["path"] == goneCanonical }).containsKey("field")
        failures.forEach { assertThat(it.keys).containsExactlyInAnyOrder("path", "field") }
        // The failing paths stay persisted: once the owner fixes main, the next start restores them.
        source.put(broken, validYaml("broken"))
        val afterFix = daemonRun("failing.db", source) { registry -> registry.restore(); registry.list() }
        assertThat(afterFix.map { it.name }).containsExactlyInAnyOrder("broken", "good")
    }

    @Test
    fun `AC-3 persisted a refused registration leaves no trace for the next start`() {
        val bad = aRepo("bad")
        val missing = aRepo("missing")
        val source = FakeSource().also { it.put(bad, validYaml().replace("close: policy", "close: nobody")) }
        daemonRun("refused.db", source) { registry ->
            assertThrows(InvalidProjectConfigException::class.java) { registry.register(bad) }
            assertThrows(ProjectNotFoundException::class.java) { registry.register(missing) }
        }
        source.put(bad, validYaml("bad"))
        val log = RecordingLog()

        val restored = daemonRun("refused.db", source, log) { registry -> registry.restore(); registry.list() }

        assertThat(restored).isEmpty()
        assertThat(log.events.filter { it.first == "project_restore_failed" }).isEmpty()
    }

    @Test
    fun `AC-5 persisted the same repository registered twice is listed once after a restart`() {
        val repo = aRepo("alpha")
        val alias = Files.createSymbolicLink(dir.resolve("alias"), repo)
        val source = FakeSource().also { it.put(repo, validYaml("alpha")) }
        daemonRun("twice.db", source) { registry ->
            registry.register(repo)
            assertThrows(ProjectAlreadyRegisteredException::class.java) { registry.register(repo) }
            assertThrows(ProjectAlreadyRegisteredException::class.java) { registry.register(alias) }
        }
        daemonRun("twice.db", source) { registry ->
            registry.restore()
            assertThrows(ProjectAlreadyRegisteredException::class.java) { registry.register(repo) }
        }

        val restored = daemonRun("twice.db", source) { registry -> registry.restore(); registry.list() }

        assertThat(restored.map { it.name }).containsExactly("alpha")
    }

    // ---- AC-7: through the socket of a real daemon ----

    private fun startDaemon(): Pair<Path, String> {
        val runtime = Files.createDirectories(dir.resolve("run"))
        val env = mapOf("XDG_RUNTIME_DIR" to runtime.toString(), "XDG_DATA_HOME" to dir.resolve("data").toString())
        val launch = launchDaemon(env, PrintStream(ByteArrayOutputStream()))
        val started = launch as DaemonLaunch.Started
        servers.add(started.server)
        val socket = resolveSocketPath(env)
        val proof = Files.readString(socket.resolveSibling("owner.token")).trim()
        return socket to proof
    }

    private fun exchange(socket: Path, line: String): JsonObject {
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
            val out = Channels.newOutputStream(channel)
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            val reply = Channels.newInputStream(channel).bufferedReader(Charsets.UTF_8).readLine()
            return Json.parseToJsonElement(reply).jsonObject
        }
    }

    private fun JsonObject.isOk(): Boolean = getValue("ok").jsonPrimitive.boolean

    private fun gitRepoWithConfig(name: String): Path {
        val repo = Files.createDirectories(dir.resolve(name))
        git(repo, "init", "-q", "-b", "main")
        Files.createDirectories(repo.resolve(".shipfloor"))
        Files.writeString(repo.resolve(".shipfloor/factory.yml"), validYaml(name))
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "config")
        return repo
    }

    @Test
    fun `AC-7 only the owner with a valid proof registers a project`() {
        val (socket, proof) = startDaemon()
        val repo = gitRepoWithConfig("guarded")
        fun add(actor: String, proofPart: String) =
            exchange(socket, """{"cmd":"project.add","actor":"$actor","path":"$repo"$proofPart}""")
        val refused = listOf(
            add("agent:dev", ""","proof":"$proof""""),
            add("adapter:github", ""","proof":"$proof""""),
            add("factory:dispatcher", ""","proof":"$proof""""),
            add("owner", ""),
            add("owner", ""","proof":"deadbeef""""),
            add("owner", ""","proof":"${proof.dropLast(1)}${if (proof.last() == '0') '1' else '0'}""""),
        )

        for (reply in refused) {
            assertThat(reply.isOk()).isFalse()
            assertThat(reply.getValue("error").jsonPrimitive.content).isNotBlank().doesNotContain(proof)
        }
        val listed = exchange(socket, """{"cmd":"project.list"}""")
        assertThat(listed.isOk()).isTrue()
        assertThat(listed.toString()).doesNotContain("guarded")

        val accepted = add("owner", ""","proof":"$proof"""")

        assertThat(accepted.isOk()).isTrue()
        assertThat(accepted.toString()).contains("guarded")
        val after = exchange(socket, """{"cmd":"project.list"}""")
        assertThat(after.isOk()).isTrue()
        assertThat(after.toString()).contains("guarded").contains("core").contains("daemon")
    }

    // ---- #83: assisted draft of the project documents (project.docs.draft) ----

    private val scratchRoot: Path by lazy { Files.createDirectories(dir.resolve("scratch")) }
    private val draftSessions = McpSessions()
    private val ownerTokenFile: Path by lazy { dir.resolve("owner").resolve("owner.token") }
    private val ownerProof: OwnerProof by lazy { OwnerProof.create(ownerTokenFile, SecureRandom()) }

    private fun proofText(): String {
        ownerProof
        return Files.readString(ownerTokenFile).trim()
    }

    /** Serves project files from main by path; counts reads so a test can show nothing was read. */
    private class FakeDocs(private val present: Map<String, String> = emptyMap()) : ProjectFileSource {
        var reads = 0

        override fun read(repo: Path, path: String): String? {
            reads++
            return present[path]
        }
    }

    private data class SeenSession(
        val worktree: Path,
        val config: Path,
        val token: String,
        val role: String?,
        val hasMainTree: Boolean,
        val configMode: String,
    )

    /** Records what the session could see while it ran, then lets [behavior] play the agent. */
    private inner class FakeRuntime(private val behavior: (SessionRequest) -> SessionResult) : Runtime {
        val seen = mutableListOf<SeenSession>()

        override fun launch(request: SessionRequest): SessionResult {
            val token = Json.parseToJsonElement(Files.readString(request.mcpConfig)).jsonObject
                .getValue("mcpServers").jsonObject.getValue("shipfloor").jsonObject
                .getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content
                .removePrefix("Bearer ")
            seen.add(
                SeenSession(
                    worktree = request.worktree,
                    config = request.mcpConfig,
                    token = token,
                    role = draftSessions.find(token)?.role,
                    hasMainTree = Files.exists(request.worktree.resolve(".shipfloor/factory.yml")),
                    configMode = PosixFilePermissions.toString(Files.getPosixFilePermissions(request.mcpConfig)),
                ),
            )
            return behavior(request)
        }
    }

    private fun exited(code: Int): SessionResult = SessionResult.Exited(code, Files.createTempFile(dir, "transcript", ".jsonl"))

    private fun writeDraft(request: SessionRequest, doc: String, text: String) {
        val folder = Files.createDirectories(request.worktree.resolve("docs-draft"))
        Files.writeString(folder.resolve(doc), text)
    }

    private fun docsService(repo: Path, files: ProjectFileSource, runtime: Runtime, log: DaemonLog = RecordingLog()): ProjectDocsDraft {
        val registry = ProjectRegistry(GitProjectConfigSource())
        registry.register(repo)
        return ProjectDocsDraft(
            registry = registry,
            files = files,
            sessions = draftSessions,
            runtime = runtime,
            ownerProof = ownerProof,
            scratchRoot = scratchRoot,
            mcpPort = 4000,
            configDir = Files.createDirectories(dir.resolve("mcp-config")),
            model = "test-model",
            log = log,
        )
    }

    /** The request goes through the wire parser, as the socket would deliver it. */
    private fun draftRequest(actor: String = "owner", proof: String? = proofText(), project: String = "docs-alpha"): Request {
        val proofPart = proof?.let { ""","proof":"$it"""" }.orEmpty()
        return parseRequest("""{"cmd":"project.docs.draft","actor":"$actor","project":"$project"$proofPart}""")
    }

    private fun ProjectDocsDraft.ask(request: Request): JsonObject = Json.parseToJsonElement(render(handle(request))).jsonObject

    private fun JsonObject.draftsByDoc(): Map<String, JsonObject> =
        getValue("result").jsonArray.map { it.jsonObject }.associateBy { it.getValue("doc").jsonPrimitive.content }

    private fun JsonObject.field(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun treeOf(repo: Path): Map<String, String> = Files.walk(repo).use { paths ->
        paths.filter { Files.isRegularFile(it) && !repo.relativize(it).startsWith(".git") }
            .toList()
            .associate { repo.relativize(it).toString() to Files.readString(it) }
    }

    private fun assertCleanedUp(session: SeenSession) {
        assertThat(session.worktree).doesNotExist()
        assertThat(session.config).doesNotExist()
        assertThat(draftSessions.find(session.token)).isNull()
        Files.list(scratchRoot).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `AC-1 of #83 a draft request without the owner proof is refused and no session is launched`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val files = FakeDocs()
        val runtime = FakeRuntime { exited(0) }
        val service = docsService(repo, files, runtime)
        val proof = proofText()
        val wrongProof = "${proof.dropLast(1)}${if (proof.last() == '0') '1' else '0'}"
        val refused = listOf(
            service.ask(draftRequest(actor = "agent:dev", proof = proof)),
            service.ask(draftRequest(actor = "adapter:github", proof = proof)),
            service.ask(draftRequest(proof = null)),
            service.ask(draftRequest(proof = "deadbeef")),
            service.ask(draftRequest(proof = wrongProof)),
        )

        for (reply in refused) {
            assertThat(reply.isOk()).describedAs(reply.toString()).isFalse()
            assertThat(reply.getValue("error").jsonPrimitive.content).contains("only the owner").doesNotContain(proof)
        }
        assertThat(runtime.seen).isEmpty()
        assertThat(files.reads).isZero()
    }

    @Test
    fun `AC-2 of #83 the owner gets a draft for each missing document and none for the one that exists`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val files = FakeDocs(mapOf("ARCHITECTURE.md" to "# Architecture of the project"))
        val runtime = FakeRuntime { request ->
            writeDraft(request, "PRODUCT.md", "# Product draft")
            writeDraft(request, "DESIGN.md", "# Design draft")
            writeDraft(request, "ARCHITECTURE.md", "# Architecture rewritten by the agent")
            exited(0)
        }
        val service = docsService(repo, files, runtime)

        val reply = service.ask(draftRequest())

        assertThat(reply.isOk()).describedAs(reply.toString()).isTrue()
        val drafts = reply.draftsByDoc()
        assertThat(drafts.keys).containsExactlyInAnyOrder("PRODUCT.md", "DESIGN.md")
        assertThat(drafts.getValue("PRODUCT.md").field("status")).isEqualTo("drafted")
        assertThat(drafts.getValue("PRODUCT.md").field("text")).isEqualTo("# Product draft")
        assertThat(drafts.getValue("DESIGN.md").field("status")).isEqualTo("drafted")
        assertThat(drafts.getValue("DESIGN.md").field("text")).isEqualTo("# Design draft")
        assertThat(runtime.seen).hasSize(1)
        assertThat(runtime.seen.single().role).isEqualTo("docs-draft")
    }

    @Test
    fun `AC-3 of #83 a project with the three documents on main gets an empty list and no session`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val files = FakeDocs(
            mapOf("PRODUCT.md" to "# Product", "ARCHITECTURE.md" to "# Architecture", "DESIGN.md" to "# Design"),
        )
        val runtime = FakeRuntime { exited(0) }
        val service = docsService(repo, files, runtime)

        val reply = service.ask(draftRequest())

        assertThat(reply.isOk()).describedAs(reply.toString()).isTrue()
        assertThat(reply.getValue("result").jsonArray).isEmpty()
        assertThat(runtime.seen).isEmpty()
        Files.list(scratchRoot).use { assertThat(it.count()).isZero() }
    }

    @Test
    fun `AC-4 of #83 the session runs in a scratch directory outside the repository and everything is removed at the end`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val before = treeOf(repo)
        val runtime = FakeRuntime { request ->
            writeDraft(request, "PRODUCT.md", "# Product draft")
            writeDraft(request, "DESIGN.md", "# Design draft")
            writeDraft(request, "ARCHITECTURE.md", "# Architecture draft")
            exited(0)
        }
        val service = docsService(repo, FakeDocs(), runtime)

        val reply = service.ask(draftRequest())

        assertThat(reply.isOk()).describedAs(reply.toString()).isTrue()
        val session = runtime.seen.single()
        // Raw comparison: the scratch directory is already removed, so it cannot be resolved to a real path.
        assertThat(session.worktree.toAbsolutePath().normalize()).startsWithRaw(scratchRoot.toAbsolutePath().normalize())
        assertThat(session.worktree.toAbsolutePath().toString()).doesNotStartWith(repo.toAbsolutePath().toString())
        assertThat(session.hasMainTree).describedAs("main tree exported for context").isTrue()
        assertThat(session.role).isEqualTo("docs-draft")
        assertThat(session.configMode).isEqualTo("rw-------")
        assertThat(treeOf(repo)).isEqualTo(before)
        assertCleanedUp(session)
    }

    @Test
    fun `AC-4 of #83 the scratch directory the config file and the token are removed also when the session ends badly or throws`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val before = treeOf(repo)
        val failing = FakeRuntime { exited(1) }
        val hung = FakeRuntime { SessionResult.Hung(Files.createTempFile(dir, "transcript", ".jsonl")) }
        val throwing = FakeRuntime { throw IllegalStateException("runtime exploded") }

        listOf(failing, hung, throwing).forEach { runtime ->
            val service = docsService(repo, FakeDocs(), runtime)
            runCatching { service.ask(draftRequest()) }
            assertThat(runtime.seen).hasSize(1)
            assertCleanedUp(runtime.seen.single())
        }
        assertThat(treeOf(repo)).isEqualTo(before)
    }

    @Test
    fun `AC-5 of #83 a document without a draft is marked failed with the reason and the other drafts are kept`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val before = treeOf(repo)
        val onlyProduct = { request: SessionRequest -> writeDraft(request, "PRODUCT.md", "# Product draft") }
        val nonZero = FakeRuntime { request -> onlyProduct(request); exited(2) }
        val hung = FakeRuntime { request -> onlyProduct(request); SessionResult.Hung(Files.createTempFile(dir, "transcript", ".jsonl")) }
        val silent = FakeRuntime { request -> onlyProduct(request); exited(0) }

        // ARCHITECTURE.md exists on main, so only PRODUCT.md and DESIGN.md are asked for.
        val architectureOnMain = mapOf("ARCHITECTURE.md" to "# Architecture of the project")
        val byExit = docsService(repo, FakeDocs(architectureOnMain), nonZero).ask(draftRequest()).draftsByDoc()
        val byHang = docsService(repo, FakeDocs(architectureOnMain), hung).ask(draftRequest()).draftsByDoc()
        val byOmission = docsService(repo, FakeDocs(architectureOnMain), silent).ask(draftRequest()).draftsByDoc()

        listOf(byExit to "exit_code", byHang to "hung", byOmission to "not_written").forEach { (drafts, reason) ->
            assertThat(drafts.keys).describedAs(reason).containsExactlyInAnyOrder("PRODUCT.md", "DESIGN.md")
            assertThat(drafts.getValue("PRODUCT.md").field("status")).describedAs(reason).isEqualTo("drafted")
            assertThat(drafts.getValue("PRODUCT.md").field("text")).describedAs(reason).isEqualTo("# Product draft")
            assertThat(drafts.getValue("DESIGN.md").field("status")).describedAs(reason).isEqualTo("failed")
            assertThat(drafts.getValue("DESIGN.md").field("reason")).describedAs(reason).isEqualTo(reason)
        }
        assertThat(treeOf(repo)).isEqualTo(before)
    }

    @Test
    fun `AC-6 of #83 the log carries the project and the documents with their result and never the text read or drafted`() {
        val repo = gitRepoWithConfig("docs-alpha")
        val readMarker = "TEXTO-LIDO-DO-REPOSITORIO"
        val draftMarker = "TEXTO-DO-RASCUNHO"
        val files = FakeDocs(mapOf("ARCHITECTURE.md" to "# Architecture $readMarker"))
        val runtime = FakeRuntime { request ->
            writeDraft(request, "PRODUCT.md", "# Product $draftMarker")
            exited(0)
        }
        val log = RecordingLog()
        val service = docsService(repo, files, runtime, log)

        val reply = service.ask(draftRequest())

        assertThat(reply.isOk()).describedAs(reply.toString()).isTrue()
        val logged = log.events.joinToString("\n") { (event, fields) -> "$event " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" } }
        assertThat(logged).contains("docs-alpha").contains("PRODUCT.md").contains("DESIGN.md").contains("drafted").contains("failed")
        assertThat(logged).doesNotContain(readMarker).doesNotContain(draftMarker).doesNotContain(proofText())
    }

    private fun git(repo: Path, vararg args: String) {
        val command = listOf(
            "git", "-C", repo.toString(),
            "-c", "user.name=QA", "-c", "user.email=qa@example.invalid", "-c", "commit.gpgsign=false",
        ) + args
        val log = Files.createTempFile(dir, "git", ".log")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).describedAs(Files.readString(log)).isZero()
    }
}
