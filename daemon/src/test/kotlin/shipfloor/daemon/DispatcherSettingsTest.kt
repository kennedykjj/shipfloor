package shipfloor.daemon

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.Database

/**
 * The daemon composed as `launchDaemon` does it (real [RefineRuntime.start]), with fakes for the repositories.
 * The refine run itself dies at the worktree step (the repos are empty), so no agent binary is ever started:
 * a cycle's [DispatchedRun] list is what tells whether the dispatcher was allowed to launch.
 */
class ComposedDaemon(private val dir: Path, startAt: Instant = Instant.parse("2026-10-16T12:00:00Z")) : AutoCloseable {
    class SyncLog : DaemonLog {
        private val entries = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val lines: List<Pair<String, Map<String, String>>> get() = entries.toList()

        override fun log(event: String, fields: Map<String, String>) {
            entries.add(event to fields)
        }

        fun events(name: String) = lines.filter { it.first == name }

        fun everything(): String = lines.joinToString("\n") { (event, fields) -> "$event $fields" }
    }

    class MapSource : ProjectConfigSource, ProjectFileSource {
        val files = mutableMapOf<Path, String>()
        var failure: RuntimeException? = null

        override fun read(repo: Path): String? {
            failure?.let { throw it }
            return files[repo.toRealPath()]
        }

        override fun read(repo: Path, path: String): String? = null
    }

    val clock: Clock = Clock.fixed(startAt, ZoneOffset.UTC)
    val log = SyncLog()
    val source = MapSource()
    val database: Database = Database.open(dir.resolve("composed.db"))
    val store = CardEventStore(database)
    val commands = CardCommands(store, clock, OwnerProof.create(dir.resolve("owner.token"), SecureRandom()))
    val registry = ProjectRegistry(source)
    val dataDir: Path = Files.createDirectories(dir.resolve("data"))
    private var runtime: RefineRuntime? = null

    /** Creates an (empty) git-looking repository and registers it with the given factory.yml text. */
    fun repo(name: String, extra: String = "", register: Boolean = true): Path {
        val repo = Files.createDirectories(dir.resolve("repos").resolve(name))
        Files.createDirectories(repo.resolve(".git"))
        source.files[repo.toRealPath()] = factoryYaml(name, extra)
        if (register) registry.register(repo)
        return repo.toRealPath()
    }

    fun setConfig(repo: Path, extra: String) {
        source.files[repo.toRealPath()] = factoryYaml(repo.fileName.toString(), extra)
    }

    fun prices(text: String? = VALID_PRICES) {
        if (text != null) Files.writeString(dataDir.resolve("prices.yml"), text)
    }

    fun card(id: String, column: Column = Column.READY_FOR_REFINEMENT) {
        val event = Event(EventId("seed-$id"), clock.instant(), Actor.Owner, EventType.CARD_CREATED, mapOf("title" to id))
        store.recordChange(id, Card.create("Card $id", column), event)
    }

    /** A cost already recorded today for [cardId]. */
    fun spend(cardId: String, usd: String) {
        val event = Event(
            EventId("cost-$cardId-$usd"),
            clock.instant(),
            Actor.Factory("cost"),
            EventType.RUN_COST_RECORDED,
            mapOf("run_id" to "run-$cardId-$usd", "status" to "KNOWN", "cost_usd" to usd, "estimate" to "true"),
        )
        commands.recorder.record(cardId, requireNotNull(store.card(cardId)), event)
    }

    fun start(env: Map<String, String> = emptyMap()): RefineRuntime {
        val started = RefineRuntime.start(
            dataDir = dataDir,
            store = store,
            commands = commands,
            registry = registry,
            files = source,
            database = database,
            clock = clock,
            source = source,
            env = env,
            log = log,
        )
        runtime = started
        return started
    }

    /** One dispatcher cycle on a freshly started runtime. */
    fun cycle(env: Map<String, String> = emptyMap()): List<DispatchedRun> = (runtime ?: start(env)).dispatcher.runCycle()

    override fun close() {
        // Let any refine run started by the dispatcher finish before the directory under it goes away.
        Thread.getAllStackTraces().keys.filter { it.name.startsWith("refine-") }.forEach { runCatching { it.join(JOIN_MILLIS) } }
        runCatching { runtime?.close() }
        runCatching { commands.events.close() }
        runCatching { database.close() }
    }

    companion object {
        private const val JOIN_MILLIS = 5_000L
        const val VALID_PRICES = "models:\n  model-a:\n    input: 3.00\n    output: 15.00\n"
        const val ENABLED = "dispatcher:\n  enabled: true\n"
        const val LIMITS = "cost:\n  per_card_usd: \"5\"\n  per_day_usd: \"20\"\n"

        fun factoryYaml(name: String, extra: String): String =
            "project:\n  name: $name\nlayers:\n  - name: daemon\n    path: daemon/\n$extra"
    }
}

class DispatcherSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val limits = mapOf("cost" to mapOf("per_card_usd" to "5", "per_day_usd" to "20"))

    private fun resolve(yaml: Map<String, Any?>?, env: Map<String, String> = emptyMap()) =
        DispatcherSettings.resolve(yaml, env)

    private fun yaml(vararg entries: Pair<String, Any?>): Map<String, Any?> = limits + mapOf(*entries)

    @Test
    fun `AC-1 dispatcher enabled is false unless the factory yml or the environment says true`() {
        assertThat(resolve(limits).enabled).isFalse()
        assertThat(resolve(null, mapOf("SHIPFLOOR_COST_UNLIMITED" to "true")).enabled).isFalse()
        assertThat(resolve(yaml("dispatcher" to mapOf("enabled" to true))).enabled).isTrue()
        assertThat(resolve(yaml("dispatcher" to mapOf("enabled" to false))).enabled).isFalse()
    }

    @Test
    fun `AC-1 SHIPFLOOR_DISPATCHER_ENABLED beats the factory yml in both directions`() {
        val on = yaml("dispatcher" to mapOf("enabled" to true))
        val off = yaml("dispatcher" to mapOf("enabled" to false))

        assertThat(resolve(on, mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "false")).enabled).isFalse()
        assertThat(resolve(off, mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "true")).enabled).isTrue()
        assertThat(resolve(limits, mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "true")).enabled).isTrue()
    }

    @Test
    fun `AC-1 only exact true or false are accepted for enabled, anything else is invalid`() {
        listOf("TRUE", "1", "yes", "", " true", "True").forEach { value ->
            assertThrows<InvalidDispatcherSettingsException>("env value '$value'") {
                resolve(limits, mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to value))
            }
        }
        listOf<Any>("yes", "true", 1).forEach { value ->
            val e = assertThrows<InvalidDispatcherSettingsException>("yaml value $value") {
                resolve(yaml("dispatcher" to mapOf("enabled" to value)))
            }
            assertThat(e.field).isEqualTo("dispatcher.enabled")
        }
    }

    @Test
    fun `AC-2 limits are positive decimals read from text, never through Double`() {
        val settings = resolve(mapOf("cost" to mapOf("per_card_usd" to "5.50", "per_day_usd" to 0.1)))

        assertThat(settings.limits.perCardUsd).isEqualByComparingTo(BigDecimal("5.50"))
        assertThat(settings.limits.perDayUsd).isEqualByComparingTo(BigDecimal("0.1"))
        assertThat(settings.unlimited).isFalse()
    }

    @Test
    fun `AC-2 a limit that is zero, negative, not a number or not a scalar is invalid and its value stays out of the message`() {
        listOf<Any>("0", -1, "-0.5", "abc-SECRET-77", "NaN", true, listOf("1")).forEach { value ->
            val e = assertThrows<InvalidDispatcherSettingsException>("limit $value") {
                resolve(mapOf("cost" to mapOf("per_card_usd" to value, "per_day_usd" to "20")))
            }
            assertThat(e.field).isEqualTo("cost.per_card_usd")
            assertThat(e.message).doesNotContain("SECRET-77")
        }
        val day = assertThrows<InvalidDispatcherSettingsException> {
            resolve(mapOf("cost" to mapOf("per_card_usd" to "5", "per_day_usd" to "0")))
        }
        assertThat(day.field).isEqualTo("cost.per_day_usd")
    }

    @Test
    fun `AC-2 unlimited exists only as SHIPFLOOR_COST_UNLIMITED exactly true in the environment`() {
        val none = mapOf<String, Any?>("dispatcher" to mapOf("enabled" to true))

        assertThat(resolve(none, mapOf("SHIPFLOOR_COST_UNLIMITED" to "true")).unlimited).isTrue()
        listOf("TRUE", "1", "yes", "false", "").forEach { value ->
            assertThat(resolve(none.plus(limits), mapOf("SHIPFLOOR_COST_UNLIMITED" to value)).unlimited).isFalse()
        }
    }

    @Test
    fun `AC-2 cost unlimited in the factory yml is ignored, whatever its value`() {
        listOf<Any>(true, "true").forEach { value ->
            val settings = resolve(mapOf("cost" to mapOf("per_card_usd" to "5", "per_day_usd" to "20", "unlimited" to value)))

            assertThat(settings.unlimited).isFalse()
            assertThat(settings.limits.perCardUsd).isEqualByComparingTo(BigDecimal("5"))
        }
    }

    @Test
    fun `AC-4 work_hours and its timezone come from the factory yml, and without them it is always work time`() {
        val settings = resolve(
            yaml(
                "work_hours" to mapOf(
                    "start" to "09:00", "end" to "18:00", "days" to listOf("mon", "fri"), "timezone" to "America/Sao_Paulo",
                ),
            ),
        )

        assertThat(settings.zone).isEqualTo(ZoneId.of("America/Sao_Paulo"))
        assertThat(settings.schedule.isWorkTime(Instant.parse("2026-10-16T15:00:00Z"))).isTrue()
        assertThat(settings.schedule.isWorkTime(Instant.parse("2026-10-16T22:00:00Z"))).isFalse()
        val always = resolve(limits)
        assertThat(always.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(always.schedule.isWorkTime(Instant.parse("2026-10-17T03:00:00Z"))).isTrue()
    }

    @Test
    fun `AC-4 an invalid work_hours section is rejected`() {
        assertThrows<RuntimeException> { resolve(yaml("work_hours" to mapOf("start" to "09:00"))) }
        assertThrows<RuntimeException> {
            resolve(yaml("work_hours" to mapOf("start" to "09:00", "end" to "18:00", "timezone" to "Mars/Base")))
        }
    }

    @Test
    fun `AC-1 a daemon without enabled launches nothing and logs the disabled state once`() {
        ComposedDaemon(dir).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c1")

            assertThat(daemon.cycle()).isEmpty()
            assertThat(daemon.cycle()).isEmpty()

            assertThat(daemon.log.events("dispatcher_state").map { it.second["state"] }).containsExactly("disabled")
        }
    }

    @Test
    fun `AC-1 enabled in the factory yml launches the refine run and logs the enabled state`() {
        ComposedDaemon(dir).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c1")

            assertThat(daemon.cycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))

            assertThat(daemon.log.events("dispatcher_state").map { it.second["state"] }).containsExactly("enabled")
        }
    }

    @Test
    fun `AC-1 the environment enables a project that says nothing and disables one that says true`() {
        ComposedDaemon(dir.resolve("a").also { Files.createDirectories(it) }).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c1")

            assertThat(daemon.cycle(mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "true"))).hasSize(1)
        }
        ComposedDaemon(dir.resolve("b").also { Files.createDirectories(it) }).use { daemon ->
            daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)
            daemon.prices()
            daemon.card("c1")

            assertThat(daemon.cycle(mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "false"))).isEmpty()
            assertThat(daemon.log.events("dispatcher_state").map { it.second["state"] }).containsExactly("disabled")
        }
    }

    @Test
    fun `AC-1 a daemon with no registered project is closed, then opens when the project appears`() {
        ComposedDaemon(dir).use { daemon ->
            daemon.prices()
            daemon.card("c1")
            val runtime = daemon.start()

            assertThat(runtime.dispatcher.runCycle()).isEmpty()

            daemon.repo("alpha", ComposedDaemon.ENABLED + ComposedDaemon.LIMITS)

            assertThat(runtime.dispatcher.runCycle()).containsExactly(DispatchedRun(DispatchStage.REFINE, "c1"))
        }
    }
}
