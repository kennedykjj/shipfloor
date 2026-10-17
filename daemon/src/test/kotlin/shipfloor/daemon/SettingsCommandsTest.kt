package shipfloor.daemon

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.CostSetting
import shipfloor.core.Event
import shipfloor.core.FactorySettings
import shipfloor.core.NotificationSetting
import shipfloor.core.NotificationType
import shipfloor.core.WorkHoursSetting
import shipfloor.store.CardEventStore
import shipfloor.store.Database
import shipfloor.store.StoredEvent

object SettingsFixtures {
    fun aWorkHours(
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(17, 0),
        zone: String = "UTC",
    ) = WorkHoursSetting(days, start, end, zone)

    fun aSettings(
        poolSize: Int = 2,
        workHours: WorkHoursSetting? = aWorkHours(),
        cost: CostSetting = CostSetting(BigDecimal("5"), BigDecimal("20")),
        notifications: NotificationSetting = NotificationSetting(setOf(NotificationType.RUN_FAILED), null, null),
    ) = FactorySettings(poolSize, workHours, cost, notifications)
}

class SettingsCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC)
    private val log = RunPoolFixtures.RecordingLog()

    private lateinit var database: Database
    private lateinit var cards: CardEventStore
    private lateinit var stream: EventStream
    private lateinit var ownerProof: OwnerProof
    private lateinit var token: String
    private lateinit var settingsPath: Path
    private lateinit var store: SettingsStore

    @BeforeEach
    fun setUp() {
        database = Database.open(dir.resolve("settings.db"))
        cards = CardEventStore(database)
        stream = EventStream(cards)
        val tokenPath = dir.resolve("owner.token")
        ownerProof = OwnerProof.create(tokenPath, SecureRandom())
        token = Files.readString(tokenPath)
        settingsPath = dir.resolve("data").resolve("settings.json")
        Files.createDirectories(settingsPath.parent)
        store = SettingsStore(settingsPath, clock)
    }

    @AfterEach
    fun tearDown() {
        stream.close()
        database.close()
    }

    private fun commands(
        env: Map<String, String> = emptyMap(),
        recorder: EventRecorder = EventRecorder(cards, stream),
        target: SettingsStore = store,
    ) = SettingsCommands(target, recorder, cards, ownerProof, env, clock, log)

    private fun save(
        commands: SettingsCommands,
        settings: FactorySettings,
        actor: Actor = Actor.Owner,
        proof: String? = token,
    ): Response = commands.handle(Request.SettingsSave(actor, settings, proof))

    private fun savedEvents(): List<Event> =
        cards.events(SettingsCommands.SETTINGS_CARD_ID).filter { it.type.name == "SETTINGS_SAVED" }

    private fun assertRefusedNaming(response: Response, field: String) {
        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat((response as Response.Error).message).startsWith(field)
    }

    @Test
    fun `AC-3 a valid save by the owner persists, records a signed event and is returned by settings get`() {
        val settings = SettingsFixtures.aSettings(poolSize = 4, cost = CostSetting(BigDecimal("7.5"), BigDecimal("123.45")))
        val commands = commands()

        val response = save(commands, settings)

        assertThat(response).isEqualTo(Response.Settings(settings))
        assertThat(commands.handle(Request.SettingsGet)).isEqualTo(Response.Settings(settings))
        assertThat(store.load()).isEqualTo(settings)
        val event = savedEvents().single()
        assertThat(event.actor).isEqualTo(Actor.Owner)
        assertThat(event.payload.toString()).doesNotContain("123.45").doesNotContain("7.5")
    }

    @Test
    fun `AC-3 the pool takes the saved size on the next admission without dropping runs in progress`() {
        val commands = commands()
        save(commands, SettingsFixtures.aSettings(poolSize = 1))
        RunPoolFixtures.seed(cards, "c1")
        RunPoolFixtures.seed(cards, "c2")
        val pool = RunPool(
            capacity = { store.load().poolSize },
            store = cards,
            clock = clock,
            probe = RunPoolFixtures.FakeProbe(),
            log = log,
            recorder = EventRecorder(cards, stream),
        )
        val first = pool.tryStart("c1", 100L, clock.instant())
        assertThat(first).isInstanceOf(StartDecision.Started::class.java)
        assertThat(pool.tryStart("c2", 101L, clock.instant())).isInstanceOf(StartDecision.PoolFull::class.java)

        save(commands, SettingsFixtures.aSettings(poolSize = 2))

        assertThat(pool.hasRoom()).isTrue()
        assertThat(pool.tryStart("c2", 101L, clock.instant())).isInstanceOf(StartDecision.Started::class.java)
        assertThat(pool.isLocked("c1")).isTrue()
    }

    @Test
    fun `AC-3 the dispatch control reads the saved work hours on every call`() {
        val repo = Files.createDirectories(dir.resolve("alpha"))
        Files.createDirectories(repo.resolve(".git"))
        val yaml = "project:\n  name: alpha\nlayers:\n  - name: core\n    path: core/\ncost:\n  per_card_usd: 5\n  per_day_usd: 20\n"
        val source = object : ProjectConfigSource {
            override fun read(repo: Path): String = yaml
        }
        val registry = ProjectRegistry(source).also { it.register(repo) }
        val control = ConfiguredDispatchControl(
            registry = registry,
            source = source,
            env = mapOf("SHIPFLOOR_DISPATCHER_ENABLED" to "true"),
            store = cards,
            recorder = EventRecorder(cards, stream),
            clock = clock,
            log = log,
            settings = store,
        )
        val friday = Instant.parse("2026-10-16T10:00:00Z")
        val commands = commands()

        save(commands, SettingsFixtures.aSettings(workHours = SettingsFixtures.aWorkHours(days = setOf(DayOfWeek.MONDAY))))
        assertThat(control.isWorkTime(friday)).isFalse()

        save(commands, SettingsFixtures.aSettings(workHours = SettingsFixtures.aWorkHours(days = setOf(DayOfWeek.FRIDAY))))
        assertThat(control.isWorkTime(friday)).isTrue()
    }

    @Test
    fun `AC-5 a save without the owner proof, with a wrong one or by another actor is refused and changes nothing`() {
        val commands = commands()
        val original = SettingsFixtures.aSettings(poolSize = 2)
        save(commands, original)
        val changed = SettingsFixtures.aSettings(poolSize = 9)

        val responses = listOf(
            save(commands, changed, proof = null),
            save(commands, changed, proof = "forged"),
            save(commands, changed, actor = Actor.Agent("develop")),
        )

        responses.forEach { assertThat(it).isInstanceOf(Response.Error::class.java) }
        assertThat(store.load()).isEqualTo(original)
        assertThat(savedEvents()).hasSize(1)
    }

    @Test
    fun `AC-9 without the unlimited env an empty cost limit is refused naming the field and nothing is written`() {
        val commands = commands(env = emptyMap())
        val perDayEmpty = SettingsFixtures.aSettings(cost = CostSetting(BigDecimal("5"), null))
        val bothEmpty = SettingsFixtures.aSettings(cost = CostSetting(null, null))

        assertRefusedNaming(save(commands, perDayEmpty), "cost.perDayUsd")
        assertThat(save(commands, bothEmpty)).isInstanceOf(Response.Error::class.java)
        assertThat(Files.exists(settingsPath)).isFalse()
        assertThat(savedEvents()).isEmpty()
    }

    @Test
    fun `AC-9 with SHIPFLOOR_COST_UNLIMITED true an empty cost limit means no limit and is saved`() {
        val commands = commands(env = mapOf("SHIPFLOOR_COST_UNLIMITED" to "true"))
        val unlimited = SettingsFixtures.aSettings(cost = CostSetting(null, null))

        assertThat(save(commands, unlimited)).isEqualTo(Response.Settings(unlimited))
        assertThat(store.load().cost).isEqualTo(CostSetting(null, null))
    }

    @Test
    fun `AC-10 a pool above 64, an unknown zone and empty days are refused naming the field and nothing is written`() {
        val commands = commands()

        assertRefusedNaming(save(commands, SettingsFixtures.aSettings(poolSize = 65)), "poolSize")
        assertRefusedNaming(
            save(commands, SettingsFixtures.aSettings(workHours = SettingsFixtures.aWorkHours(zone = "Mars/Olympus"))),
            "workHours.zone",
        )
        assertRefusedNaming(
            save(commands, SettingsFixtures.aSettings(workHours = SettingsFixtures.aWorkHours(days = emptySet()))),
            "workHours.days",
        )
        assertThat(Files.exists(settingsPath)).isFalse()
        assertThat(savedEvents()).isEmpty()
    }

    @Test
    fun `AC-11 when the SETTINGS_SAVED event cannot be recorded the previous file is restored and the response is an error`() {
        val original = SettingsFixtures.aSettings(poolSize = 2)
        save(commands(), original)
        val failing = object : EventRecorder(cards, stream) {
            override fun record(cardId: String, card: Card, event: Event): StoredEvent =
                throw IllegalStateException("event store down")
        }

        val response = save(commands(recorder = failing), SettingsFixtures.aSettings(poolSize = 9))

        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat(store.load()).isEqualTo(original)
        assertThat(savedEvents()).hasSize(1)
    }

    @Test
    fun `AC-11 when there was no previous file a failed event leaves no file`() {
        val failing = object : EventRecorder(cards, stream) {
            override fun record(cardId: String, card: Card, event: Event): StoredEvent =
                throw IllegalStateException("event store down")
        }

        val response = save(commands(recorder = failing), SettingsFixtures.aSettings(poolSize = 9))

        assertThat(response).isInstanceOf(Response.Error::class.java)
        assertThat(Files.exists(settingsPath)).isFalse()
    }

    @Test
    fun `AC-7 notification preferences survive a daemon restart`() {
        val notifications = NotificationSetting(
            setOf(NotificationType.DECISION_WAITING, NotificationType.FACTORY_STOPPED),
            LocalTime.of(22, 0),
            LocalTime.of(7, 0),
        )
        save(commands(), SettingsFixtures.aSettings(notifications = notifications))

        val afterRestart = commands(target = SettingsStore(settingsPath, clock)).handle(Request.SettingsGet)

        assertThat((afterRestart as Response.Settings).settings.notifications).isEqualTo(notifications)
    }

    @Test
    fun `AC-8 settings are per machine file, private to the owner, and another machine data dir has its own`() {
        save(commands(), SettingsFixtures.aSettings(poolSize = 7))

        val otherMachine = SettingsStore(dir.resolve("other").resolve("settings.json"), clock)

        assertThat(otherMachine.load()).isEqualTo(FactorySettings.DEFAULT)
        assertThat(store.load().poolSize).isEqualTo(7)
        assertThat(Files.getPosixFilePermissions(settingsPath)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
    }
}
