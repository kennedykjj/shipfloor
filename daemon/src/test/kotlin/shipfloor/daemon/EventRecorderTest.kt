package shipfloor.daemon

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.streams.toList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.Actor
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventType
import shipfloor.store.CardEventStore
import shipfloor.store.Database

/** One event as a live subscriber saw it. */
data class SeenEvent(val seq: Long, val id: String, val type: String)

/** A live subscription on [events] over a loopback socket; reads what the stream delivered. */
class StreamCollector(events: EventStream) : AutoCloseable {
    private val client: Socket
    private val reader: BufferedReader

    init {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            client = Socket(InetAddress.getLoopbackAddress(), (server.localAddress as InetSocketAddress).port)
            events.subscribe(server.accept(), after = null)
        }
        reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
    }

    /** Reads up to [count] events, stopping early when none arrives within [timeoutMillis]. */
    fun read(count: Int, timeoutMillis: Int = 5000): List<SeenEvent> {
        client.soTimeout = timeoutMillis
        val seen = mutableListOf<SeenEvent>()
        try {
            while (seen.size < count) {
                val line = reader.readLine() ?: break
                seen.add(
                    SeenEvent(
                        seq = requireNotNull(Regex("\"seq\":(\\d+)").find(line)) { "no seq" }.groupValues[1].toLong(),
                        id = requireNotNull(Regex("\"id\":\"([^\"]*)\"").find(line)) { "no id" }.groupValues[1],
                        type = requireNotNull(Regex("\"type\":\"([^\"]*)\"").find(line)) { "no type" }.groupValues[1],
                    ),
                )
            }
        } catch (e: SocketTimeoutException) {
            // nothing more arrived: return what was seen
        }
        return seen
    }

    override fun close() {
        client.close()
    }
}

class EventRecorderTest {
    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-16T11:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var database: Database
    private lateinit var store: CardEventStore

    @BeforeEach
    fun openStore() {
        database = Database.open(dir.resolve("recorder.db"))
        store = CardEventStore(database)
    }

    @AfterEach
    fun closeStore() {
        database.close()
    }

    private class InstantRunner : GateRunner {
        override fun run(spec: GateSpec, outputFile: Path): GateRun {
            Files.writeString(outputFile, "ok\n")
            return GateRun(exitCode = 0, timedOut = false)
        }
    }

    private fun deadPool(commands: CardCommands, cards: List<String>): RunPool {
        val pool = RunPool(
            capacity = cards.size,
            store = store,
            clock = clock,
            probe = RunPoolFixtures.FakeProbe(),
            recorder = commands.recorder,
        )
        cards.forEachIndexed { i, id -> pool.tryStart(id, 1000L + i, now) }
        return pool
    }

    @Test
    fun `AC-1 and AC-3 card commands, pool sweep and gate emitting at once reach a subscriber complete and in strict seq order`() {
        val commands = CardCommands(store, clock, OwnerProof.create(dir.resolve("owner.token"), SecureRandom()))
        val perAuthor = 60
        val poolCards = (1..perAuthor).map { "pool-$it" }
        poolCards.forEach { RunPoolFixtures.seed(store, it) }
        RunPoolFixtures.seed(store, "gate-card")
        val pool = deadPool(commands, poolCards)
        val gate = GateExecutor(store, commands.recorder, clock, InstantRunner())
        val baseline = store.eventsAfter(0).maxOf { it.seq }

        StreamCollector(commands.events).use { collector ->
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val work = listOf(
                    { (1..perAuthor).forEach { commands.handle(Request.CardCreate(Actor.Factory("test"), "Card $it")) } },
                    { pool.sweep() },
                    { (1..perAuthor).forEach { gate.execute("gate-card", GateSpec("g$it", listOf("x"), dir, Duration.ofSeconds(5)), dir.resolve("run")) } },
                )
                val futures = work.map { task -> executor.submit { start.await(); task() } }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            val expected = store.eventsAfter(baseline).map { it.seq }
            assertThat(expected).hasSize(perAuthor * 3)
            val seen = collector.read(expected.size).map { it.seq }
            assertThat(seen).isEqualTo(expected)
            assertThat(seen).isSorted()
        }
    }

    @Test
    fun `AC-1 record writes the event and publishes the same stored event`() {
        RunPoolFixtures.seed(store, "c1")
        val stream = EventStream(store)
        val recorder = EventRecorder(store, stream)
        val card = requireNotNull(store.card("c1"))
        val event = Event(EventId("rec-1"), now, Actor.Factory("test"), EventType.CARD_CREATED, mapOf("title" to "Seeded"))

        StreamCollector(stream).use { collector ->
            val stored = recorder.record("c1", card, event)

            assertThat(store.eventsAfter(stored.seq - 1).single()).isEqualTo(stored)
            assertThat(stored.cardId).isEqualTo("c1")
            assertThat(collector.read(1).map { it.seq to it.id }).containsExactly(stored.seq to "rec-1")
        }
        stream.close()
    }

    @Test
    fun `AC-2 no production code outside EventRecorder calls recordChange or publish`() {
        val main = Path.of("src/main/kotlin")
        assertThat(main).isDirectory()
        val offenders = Files.walk(main).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() !in setOf("EventRecorder.kt", "EventStream.kt") }
                .filter { file ->
                    val text = Files.readString(file)
                    text.contains("recordChange(") || text.contains(".publish(")
                }
                .map { it.fileName.toString() }
                .toList()
        }
        assertThat(offenders).isEmpty()
        assertThat(Files.readString(main.resolve("shipfloor/daemon/EventRecorder.kt"))).contains("recordChange(").contains("publish(")
    }
}
