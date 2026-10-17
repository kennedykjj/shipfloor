package shipfloor.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import shipfloor.core.Actor
import shipfloor.core.Card
import shipfloor.core.Column
import shipfloor.core.Event
import shipfloor.core.EventId
import shipfloor.core.EventJson
import shipfloor.core.EventType
import shipfloor.core.ExternalRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import shipfloor.core.TextHash

class ModuleTest {
    @TempDir
    lateinit var dir: Path

    private fun <T> jdbc(path: Path, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use(block)

    private fun scalar(path: Path, sql: String): String =
        jdbc(path) { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } } }

    private fun newerDatabase(version: Int): Path {
        val file = dir.resolve("newer.db")
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE future_table (id INTEGER PRIMARY KEY, note TEXT)")
                s.execute("INSERT INTO future_table (note) VALUES ('from the future')")
                s.execute("PRAGMA user_version = $version")
            }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    @Test
    fun `module is named after its Gradle project`() {
        assertThat(MODULE_NAME).isEqualTo("store")
    }

    @Test
    fun `AC-1 creates a new database at schema version 4 in WAL mode`() {
        val file = dir.resolve("fresh.db")

        Database.open(file).close()

        assertThat(Files.exists(file)).isTrue()
        assertThat(Database.SCHEMA_VERSION).isEqualTo(7)
        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(scalar(file, "PRAGMA journal_mode")).isEqualTo("wal")
        val tables = jdbc(file) { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }
        assertThat(tables).contains("card", "event")
    }

    @Test
    fun `AC-2 reopening a current database keeps schema and data identical`() {
        val file = dir.resolve("current.db")
        Database.open(file).close()
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT)")
                s.execute("INSERT INTO probe (note) VALUES ('kept')")
            }
        }
        val schemaBefore = scalar(file, "SELECT group_concat(sql, ';') FROM (SELECT sql FROM sqlite_master ORDER BY name)")

        Database.open(file).close()

        assertThat(scalar(file, "SELECT group_concat(sql, ';') FROM (SELECT sql FROM sqlite_master ORDER BY name)"))
            .isEqualTo(schemaBefore)
        assertThat(scalar(file, "SELECT note FROM probe")).isEqualTo("kept")
        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(scalar(file, "PRAGMA journal_mode")).isEqualTo("wal")
    }

    @Test
    fun `AC-3 fails on a newer database citing found and supported versions`() {
        val found = Database.SCHEMA_VERSION + 6
        val file = newerDatabase(found)

        assertThatThrownBy { Database.open(file) }
            .isInstanceOf(DatabaseSchemaTooNewException::class.java)
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining(found.toString())
            .hasMessageContaining(Database.SCHEMA_VERSION.toString())
            .hasMessageContaining(file.toString())
    }

    @Test
    fun `AC-4 leaves a newer database byte for byte unchanged when open fails`() {
        val file = newerDatabase(Database.SCHEMA_VERSION + 1)
        val before = Files.readAllBytes(file)
        val filesBefore = Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() }

        assertThatThrownBy { Database.open(file) }
            .isInstanceOf(DatabaseSchemaTooNewException::class.java)

        assertThat(Files.readAllBytes(file)).isEqualTo(before)
        assertThat(Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
            .isEqualTo(filesBefore)
    }

    @Test
    fun `AC-1 spec write puts the file at the card path and records path and hash`() {
        val specsDir = dir.resolve("specs")
        val db = dir.resolve("spec1.db")
        val text = "# Spec\n\nconteúdo ✓\n"

        Database.open(db).use { SpecStore(it, specsDir).write("card-1", text) }

        val path = specsDir.resolve("card-1.md")
        assertThat(Files.readString(path)).isEqualTo(text)
        assertThat(scalar(db, "SELECT path FROM spec WHERE card_id = 'card-1'")).isEqualTo(path.toString())
        assertThat(scalar(db, "SELECT hash FROM spec WHERE card_id = 'card-1'")).isEqualTo(TextHash.of(text).value)
    }

    @Test
    fun `AC-2 spec read reports divergence when the file is altered or removed`() {
        val specsDir = dir.resolve("specs")
        Database.open(dir.resolve("spec2.db")).use { database ->
            val store = SpecStore(database, specsDir)
            val text = "approved text"
            store.write("card-2", text)
            assertThat(store.read("card-2")).isEqualTo(SpecRead.Valid(text, TextHash.of(text)))
            val path = specsDir.resolve("card-2.md")

            Files.writeString(path, "edited outside")
            assertThat(store.read("card-2"))
                .isEqualTo(SpecRead.Diverged(path, TextHash.of(text), TextHash.of("edited outside")))

            Files.delete(path)
            assertThat(store.read("card-2")).isEqualTo(SpecRead.Diverged(path, TextHash.of(text), null))
        }
    }

    @Test
    fun `AC-3 spec read of a card without spec says not registered`() {
        Database.open(dir.resolve("spec3.db")).use { database ->
            assertThat(SpecStore(database, dir.resolve("specs")).read("card-3"))
                .isEqualTo(SpecRead.NotRegistered("card-3"))
        }
    }

    private fun anEvent(id: String, type: EventType = EventType.CARD_CREATED, at: String = "2026-10-15T10:00:00Z") =
        Event(EventId(id), Instant.parse(at), Actor.Owner, type, mapOf("k" to "v"))

    private fun withStore(block: (CardEventStore, Path) -> Unit) {
        val file = dir.resolve("events.db")
        Database.open(file).use { db -> block(CardEventStore(db), file) }
    }

    @Test
    fun `AC-1 records card and event together`() = withStore { store, _ ->
        val card = Card.create("Write docs", Column.INBOX)
        val event = anEvent("e1")

        store.recordChange("c1", card, event)

        assertThat(store.card("c1")).isEqualTo(card)
        assertThat(store.events("c1")).containsExactly(event)
    }

    @Test
    fun `AC-1 later change replaces the card and appends an event`() = withStore { store, _ ->
        val first = anEvent("e1")
        val second = anEvent("e2", EventType.CARD_MOVED, "2026-10-15T11:00:00Z")
        val moved = Card.create("Write docs", Column.READY_FOR_REFINEMENT)
        store.recordChange("c1", Card.create("Write docs", Column.INBOX), first)

        store.recordChange("c1", moved, second)

        assertThat(store.card("c1")).isEqualTo(moved)
        assertThat(store.events("c1")).containsExactly(first, second)
    }

    @Test
    fun `AC-2 failed event write leaves card as it was and no new event`() = withStore { store, _ ->
        val original = Card.create("Write docs", Column.INBOX)
        val first = anEvent("e1")
        store.recordChange("c1", original, first)

        assertThatThrownBy {
            store.recordChange("c1", Card.create("Write docs", Column.CODING), anEvent("e1", EventType.CARD_MOVED))
        }.isInstanceOf(Exception::class.java)

        assertThat(store.card("c1")).isEqualTo(original)
        assertThat(store.events("c1")).containsExactly(first)
    }

    @Test
    fun `AC-3 failed card write leaves card as it was and no new event`() = withStore { store, file ->
        val original = Card.create("Write docs", Column.INBOX)
        val first = anEvent("e1")
        store.recordChange("c1", original, first)
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TRIGGER fail_card_insert BEFORE INSERT ON card BEGIN SELECT RAISE(ABORT, 'card write failed'); END")
                s.execute("CREATE TRIGGER fail_card_update BEFORE UPDATE ON card BEGIN SELECT RAISE(ABORT, 'card write failed'); END")
            }
        }

        assertThatThrownBy {
            store.recordChange("c1", Card.create("Write docs", Column.CODING), anEvent("e2", EventType.CARD_MOVED))
        }.isInstanceOf(Exception::class.java)

        assertThat(store.card("c1")).isEqualTo(original)
        assertThat(store.events("c1")).containsExactly(first)
    }

    @Test
    fun `AC-4 recorded event stays identical after later changes and failed attempts`() = withStore { store, _ ->
        val first = anEvent("e1")
        store.recordChange("c1", Card.create("Write docs", Column.INBOX), first)

        store.recordChange("c1", Card.create("Write docs", Column.CODING), anEvent("e2", EventType.CARD_MOVED, "2026-10-15T11:00:00Z"))
        assertThatThrownBy {
            store.recordChange("c1", Card.create("Other", Column.INBOX), anEvent("e1", EventType.OWNER_APPROVED))
        }.isInstanceOf(Exception::class.java)

        assertThat(store.events("c1").first { it.id == first.id }).isEqualTo(first)
        assertThat(store.events("c1")).hasSize(2)
    }

    private val v1Ddl = listOf(
        "CREATE TABLE card (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, column_name TEXT NOT NULL)",
        "CREATE TABLE event (id TEXT NOT NULL PRIMARY KEY, card_id TEXT NOT NULL, at TEXT NOT NULL, " +
            "actor TEXT NOT NULL, type TEXT NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE INDEX event_card_id ON event(card_id)",
    )
    private val v2Ddl = v1Ddl + "CREATE TABLE spec (card_id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, hash TEXT NOT NULL)"

    // Ids are deliberately not in lexicographic order and share one instant: only insertion order can sort them.
    private val legacyEvents = listOf(
        anEvent("e-b", EventType.CARD_CREATED, "2026-10-15T10:00:00Z"),
        anEvent("e-a", EventType.CARD_MOVED, "2026-10-15T10:00:00Z"),
        anEvent("e-c", EventType.OWNER_APPROVED, "2026-10-15T10:00:00Z"),
    )

    private fun legacyDatabase(name: String, version: Int, withSpec: Boolean): Path {
        val file = dir.resolve(name)
        jdbc(file) { c ->
            c.createStatement().use { s ->
                (if (withSpec) v2Ddl else v1Ddl).forEach { s.execute(it) }
                s.execute("INSERT INTO card (id, title, column_name) VALUES ('c1', 'Write docs', 'INBOX')")
                if (withSpec) s.execute("INSERT INTO spec (card_id, path, hash) VALUES ('c1', '/specs/c1.md', 'abc123')")
            }
            c.prepareStatement(
                "INSERT INTO event (id, card_id, at, actor, type, payload_json) VALUES (?, 'c1', ?, 'owner', ?, ?)",
            ).use { p ->
                legacyEvents.forEach { e ->
                    p.setString(1, e.id.value)
                    p.setString(2, e.at.toString())
                    p.setString(3, e.type.name)
                    p.setString(4, EventJson.encode(e))
                    p.executeUpdate()
                }
            }
            c.createStatement().use { it.execute("PRAGMA user_version = $version") }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    private fun columns(file: Path, table: String): List<String> =
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info($table)").use { rs ->
                    generateSequence { if (rs.next()) "${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("pk")}" else null }.toList()
                }
            }
        }

    private fun longs(file: Path, sql: String): List<Long> =
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
            }
        }

    @Test
    fun `AC-1 history has increasing database-assigned seq and reads in that order schema-v3`() = withStore { store, file ->
        val base = "2026-10-15T10:00:00Z"
        val written = listOf("e-z", "e-m", "e-a").map { anEvent(it, EventType.CARD_MOVED, base) }
        written.forEach { store.recordChange("c1", Card.create("Write docs", Column.INBOX), it) }

        assertThat(columns(file, "event").map { it.substringBefore(":") }).contains("seq")
        val seqs = longs(file, "SELECT seq FROM event WHERE card_id = 'c1' ORDER BY rowid")
        assertThat(seqs).hasSize(3).isSorted()
        assertThat(seqs.toSet()).hasSize(3)
        assertThat(store.events("c1")).containsExactlyElementsOf(written)
    }

    @Test
    fun `AC-2 event for an unknown card is rejected and for a known card accepted schema-v3`() = withStore { store, file ->
        Database.open(dir.resolve("fk.db")).use { db ->
            val q = db.queries.schemaQueries
            assertThatThrownBy {
                q.insertEvent("e-orphan", "ghost", "2026-10-15T10:00:00Z", "owner", "CARD_CREATED", "{}")
            }.isInstanceOf(Exception::class.java)
            assertThat(q.eventPayloadsByCard("ghost").executeAsList()).isEmpty()
        }

        store.recordChange("c1", Card.create("Write docs", Column.INBOX), anEvent("e1"))
        store.recordChange("c1", Card.create("Write docs", Column.CODING), anEvent("e2", EventType.CARD_MOVED))
        assertThat(store.events("c1")).hasSize(2)
        assertThat(longs(file, "SELECT count(*) FROM event WHERE card_id NOT IN (SELECT id FROM card)")).containsExactly(0L)
    }

    private fun assertMigratedLegacy(file: Path, withSpec: Boolean) {
        Database.open(file).use { db ->
            assertThat(CardEventStore(db).card("c1")).isEqualTo(Card.create("Write docs", Column.INBOX))
            assertThat(CardEventStore(db).events("c1")).containsExactlyElementsOf(legacyEvents)
            if (withSpec) {
                assertThat(db.queries.schemaQueries.selectSpec("c1").executeAsOne().hash).isEqualTo("abc123")
            }
        }
        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(longs(file, "SELECT seq FROM event ORDER BY seq")).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `AC-3 version 1 database migrates to the new version keeping all data schema-v3`() {
        assertMigratedLegacy(legacyDatabase("v1.db", 1, withSpec = false), withSpec = false)
    }

    @Test
    fun `AC-3 version 2 database migrates to the new version keeping cards events and specs schema-v3`() {
        assertMigratedLegacy(legacyDatabase("v2.db", 2, withSpec = true), withSpec = true)
    }

    @Test
    fun `AC-4 migrated v1 and v2 databases end with the same schema as a fresh one schema-v3`() {
        val fresh = dir.resolve("fresh-schema.db")
        Database.open(fresh).close()
        val v1 = legacyDatabase("m1.db", 1, withSpec = false).also { Database.open(it).close() }
        val v2 = legacyDatabase("m2.db", 2, withSpec = true).also { Database.open(it).close() }

        assertThat(scalar(fresh, "PRAGMA user_version")).isEqualTo("7")
        listOf(v1, v2).forEach { migrated ->
            listOf("card", "event", "spec").forEach { table ->
                assertThat(columns(migrated, table)).isEqualTo(columns(fresh, table))
            }
            assertThat(scalar(migrated, "SELECT group_concat(name) FROM (SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name)"))
                .isEqualTo(scalar(fresh, "SELECT group_concat(name) FROM (SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name)"))
            assertThat(scalar(migrated, "SELECT count(*) FROM pragma_foreign_key_list('event')")).isEqualTo("1")
        }
    }

    @Test
    fun `AC-5 changed card is updated in place and its events stay linked schema-v3`() = withStore { store, file ->
        store.recordChange("c1", Card.create("Write docs", Column.INBOX), anEvent("e1"))
        store.recordChange("c2", Card.create("Other", Column.INBOX), anEvent("e2"))
        val rowidBefore = longs(file, "SELECT rowid FROM card WHERE id = 'c1'")
        val moved = Card.create("Write docs v2", Column.CODING)

        store.recordChange("c1", moved, anEvent("e3", EventType.CARD_MOVED, "2026-10-15T11:00:00Z"))

        assertThat(longs(file, "SELECT rowid FROM card WHERE id = 'c1'")).isEqualTo(rowidBefore)
        assertThat(longs(file, "SELECT count(*) FROM card")).containsExactly(2L)
        assertThat(store.card("c1")).isEqualTo(moved)
        assertThat(store.events("c1").map { it.id.value }).containsExactly("e1", "e3")
        assertThat(longs(file, "SELECT count(*) FROM event WHERE card_id NOT IN (SELECT id FROM card)")).containsExactly(0L)
    }

    @Test
    fun `AC-4 cards lists every stored card by insertion order with current state`() = withStore { store, _ ->
        assertThat(store.cards()).isEmpty()
        store.recordChange("c1", Card.create("First", Column.INBOX), anEvent("e1"))
        store.recordChange("c2", Card.create("Second", Column.INBOX), anEvent("e2"))
        val moved = Card.create("First", Column.READY_FOR_REFINEMENT)

        store.recordChange("c1", moved, anEvent("e3", EventType.CARD_MOVED))

        assertThat(store.cards()).containsExactly(
            StoredCard("c1", moved),
            StoredCard("c2", Card.create("Second", Column.INBOX)),
        )
    }

    private fun ref(provider: String, externalId: String, at: String = "2026-10-10T09:30:00Z") =
        ExternalRef(provider, externalId, "https://tracker.example/$externalId", Instant.parse(at), TextHash.of("text $externalId"))

    private fun insertReference(file: Path, cardId: String, position: Int, r: ExternalRef) {
        jdbc(file) { c ->
            c.prepareStatement(
                "INSERT INTO card_reference (card_id, position, provider, external_id, url, imported_at, text_hash) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { p ->
                p.setString(1, cardId)
                p.setInt(2, position)
                p.setString(3, r.provider)
                p.setString(4, r.externalId)
                p.setString(5, r.url)
                p.setString(6, r.importedAt.toString())
                p.setString(7, r.textHash.value)
                p.executeUpdate()
            }
        }
    }

    @Test
    fun `AC-1 references are written and read back equal and in order`() = withStore { store, _ ->
        val card = Card.create("Imported", Column.INBOX)
            .withReference(ref("jira", "PROJ-2"))
            .withReference(ref("github", "7", "2026-10-11T12:00:00Z"))
            .withReference(ref("jira", "PROJ-1"))

        store.recordChange("c1", card, anEvent("e1"))

        assertThat(store.card("c1")).isEqualTo(card)
        assertThat(store.card("c1")?.references).containsExactlyElementsOf(card.references)
        assertThat(store.cards()).containsExactly(StoredCard("c1", card))

        val trimmed = Card.create("Imported", Column.CODING).withReference(ref("github", "7", "2026-10-11T12:00:00Z"))
        store.recordChange("c1", trimmed, anEvent("e2", EventType.CARD_MOVED))
        assertThat(store.card("c1")).isEqualTo(trimmed)
    }

    @Test
    fun `AC-2 card without references reads back with an empty list`() = withStore { store, _ ->
        val card = Card.create("Plain", Column.INBOX)

        store.recordChange("c1", card, anEvent("e1"))

        assertThat(store.card("c1")?.references).isEmpty()
        assertThat(store.card("c1")).isEqualTo(card)
    }

    @Test
    fun `AC-3 version 3 database with cards and events migrates to 4 without losing data`() {
        val file = dir.resolve("v3.db")
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE card (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, column_name TEXT NOT NULL)")
                s.execute(
                    "CREATE TABLE event (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, " +
                        "card_id TEXT NOT NULL REFERENCES card(id), at TEXT NOT NULL, actor TEXT NOT NULL, " +
                        "type TEXT NOT NULL, payload_json TEXT NOT NULL)",
                )
                s.execute("CREATE INDEX event_card_id ON event(card_id)")
                s.execute("CREATE TABLE spec (card_id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, hash TEXT NOT NULL)")
                s.execute("INSERT INTO card (id, title, column_name) VALUES ('c1', 'Write docs', 'INBOX')")
                s.execute("INSERT INTO card (id, title, column_name) VALUES ('c2', 'Other', 'CODING')")
            }
            c.prepareStatement(
                "INSERT INTO event (id, card_id, at, actor, type, payload_json) VALUES (?, 'c1', ?, 'owner', ?, ?)",
            ).use { p ->
                legacyEvents.forEach { e ->
                    p.setString(1, e.id.value)
                    p.setString(2, e.at.toString())
                    p.setString(3, e.type.name)
                    p.setString(4, EventJson.encode(e))
                    p.executeUpdate()
                }
            }
            c.createStatement().use { it.execute("PRAGMA user_version = 3") }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

        Database.open(file).use { db ->
            val store = CardEventStore(db)
            assertThat(store.cards()).containsExactly(
                StoredCard("c1", Card.create("Write docs", Column.INBOX)),
                StoredCard("c2", Card.create("Other", Column.CODING)),
            )
            assertThat(store.card("c1")?.references).isEmpty()
            assertThat(store.events("c1")).containsExactlyElementsOf(legacyEvents)
        }
        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(longs(file, "SELECT seq FROM event ORDER BY seq")).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `AC-2 version 4 database migrates to 5 keeping data and gains the card project link`() {
        val file = dir.resolve("v4.db")
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE card (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, column_name TEXT NOT NULL)")
                s.execute(
                    "CREATE TABLE event (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, " +
                        "card_id TEXT NOT NULL REFERENCES card(id), at TEXT NOT NULL, actor TEXT NOT NULL, " +
                        "type TEXT NOT NULL, payload_json TEXT NOT NULL)",
                )
                s.execute("CREATE INDEX event_card_id ON event(card_id)")
                s.execute("CREATE TABLE spec (card_id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, hash TEXT NOT NULL)")
                s.execute(
                    "CREATE TABLE card_reference (card_id TEXT NOT NULL REFERENCES card(id), position INTEGER NOT NULL, " +
                        "provider TEXT NOT NULL, external_id TEXT NOT NULL, url TEXT NOT NULL, imported_at TEXT NOT NULL, " +
                        "text_hash TEXT NOT NULL, PRIMARY KEY (card_id, provider, external_id))",
                )
                s.execute("INSERT INTO card (id, title, column_name) VALUES ('c1', 'Write docs', 'INBOX')")
                s.execute(
                    "INSERT INTO card_reference VALUES ('c1', 0, 'jira', 'PROJ-1', 'https://tracker.example/PROJ-1', " +
                        "'2026-10-10T09:30:00Z', '${TextHash.of("text PROJ-1").value}')",
                )
                s.execute("PRAGMA user_version = 4")
            }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

        Database.open(file).use { db ->
            val store = CardEventStore(db)
            assertThat(store.card("c1")).isEqualTo(
                Card.create("Write docs", Column.INBOX).withReference(ref("jira", "PROJ-1")),
            )
            assertThat(store.projectOf("c1")).isNull()
            store.assignProject("c1", "/repos/alpha")
            assertThat(store.projectOf("c1")).isEqualTo("/repos/alpha")
        }
        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        val fresh = dir.resolve("fresh-v5.db").also { Database.open(it).close() }
        assertThat(columns(file, "card_project")).isEqualTo(columns(fresh, "card_project")).isNotEmpty()
    }

    @Test
    fun `AC-2 projectOf answers the assigned repo per card and null for an unlinked card`() = withStore { store, _ ->
        store.recordChange("c1", Card.create("A", Column.INBOX), anEvent("e1"))
        store.recordChange("c2", Card.create("B", Column.INBOX), anEvent("e2"))
        store.recordChange("c3", Card.create("C", Column.INBOX), anEvent("e3"))

        store.assignProject("c1", "/repos/alpha")
        store.assignProject("c2", "/repos/beta")

        assertThat(store.projectOf("c1")).isEqualTo("/repos/alpha")
        assertThat(store.projectOf("c2")).isEqualTo("/repos/beta")
        assertThat(store.projectOf("c3")).isNull()
        assertThat(store.projectOf("ghost")).isNull()
    }

    @Test
    fun `AC-4 database refuses a second reference with the same provider and id on one card`() = withStore { store, file ->
        val card = Card.create("Imported", Column.INBOX).withReference(ref("jira", "PROJ-1"))
        store.recordChange("c1", card, anEvent("e1"))

        assertThatThrownBy { insertReference(file, "c1", 1, ref("jira", "PROJ-1", "2026-10-12T00:00:00Z")) }
            .isInstanceOf(Exception::class.java)

        assertThat(longs(file, "SELECT count(*) FROM card_reference WHERE card_id = 'c1'")).containsExactly(1L)
        assertThat(store.card("c1")).isEqualTo(card)
    }

    @Test
    fun `AC-5 same provider and id on different cards are both accepted`() = withStore { store, _ ->
        val shared = ref("jira", "PROJ-1")
        val first = Card.create("First", Column.INBOX).withReference(shared)
        val second = Card.create("Second", Column.INBOX).withReference(shared)

        store.recordChange("c1", first, anEvent("e1"))
        store.recordChange("c2", second, anEvent("e2"))

        assertThat(store.card("c1")).isEqualTo(first)
        assertThat(store.card("c2")).isEqualTo(second)
    }

    private fun perms(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private fun existingDatabase(name: String, mode: String): Path {
        val file = dir.resolve(name)
        Database.open(file).use { CardEventStore(it).recordChange("c1", Card.create("Write docs", Column.INBOX), anEvent("e1")) }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
        return file
    }

    @Test
    fun `AC-1 new database and its wal and shm files are owner-only`() {
        val file = dir.resolve("owner-new.db")

        Database.open(file).use { db ->
            CardEventStore(db).recordChange("c1", Card.create("Write docs", Column.INBOX), anEvent("e1"))

            // The store's own connection leaves no side files at rest; a reader holding the WAL database
            // open (as a second process would) is what makes SQLite create -wal and -shm.
            DriverManager.getConnection("jdbc:sqlite:$file").use { reader ->
                reader.createStatement().use { it.execute("SELECT count(*) FROM sqlite_master") }
                reader.createStatement().use { it.execute("PRAGMA user_version") }
                assertThat(perms(file)).isEqualTo("rw-------")
                assertSideFilesOwnerOnly()
            }
        }
    }

    private fun assertSideFilesOwnerOnly() {
        run {
            assertThat(Files.exists(dir.resolve("owner-new.db-wal"))).isTrue()
            assertThat(Files.exists(dir.resolve("owner-new.db-shm"))).isTrue()
            assertThat(perms(dir.resolve("owner-new.db-wal"))).isEqualTo("rw-------")
            assertThat(perms(dir.resolve("owner-new.db-shm"))).isEqualTo("rw-------")
        }
    }

    @Test
    fun `AC-2 existing database open to group or others is refused citing path and permission owner-only`() {
        listOf("rw-r--r--", "rw-rw----", "rw----r--").forEach { mode ->
            val file = existingDatabase("open-$mode.db", mode)

            assertThatThrownBy { Database.open(file) }
                .isInstanceOf(RuntimeException::class.java)
                .satisfies({ e -> assertThat(e.javaClass.simpleName).isEqualTo("DatabaseInsecurePermissionsException") })
                .hasMessageContaining(file.toString())
                .hasMessageContaining(mode)
        }
    }

    @Test
    fun `AC-3 refused database keeps its permission and content owner-only`() {
        val file = existingDatabase("refused.db", "rw-r--r--")
        val before = Files.readAllBytes(file)
        val filesBefore = Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() }

        assertThatThrownBy { Database.open(file) }
            .satisfies({ e -> assertThat(e.javaClass.simpleName).isEqualTo("DatabaseInsecurePermissionsException") })

        assertThat(perms(file)).isEqualTo("rw-r--r--")
        assertThat(Files.readAllBytes(file)).isEqualTo(before)
        assertThat(Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() }).isEqualTo(filesBefore)
    }

    @Test
    fun `AC-4 existing database with 0600 or 0400 opens normally owner-only`() {
        val rw = existingDatabase("rw.db", "rw-------")
        Database.open(rw).use { assertThat(CardEventStore(it).card("c1")).isEqualTo(Card.create("Write docs", Column.INBOX)) }
        assertThat(perms(rw)).isEqualTo("rw-------")

        val ro = existingDatabase("ro.db", "r--------")
        Database.open(ro).use { assertThat(CardEventStore(it).events("c1")).hasSize(1) }
    }

    @Test
    fun `recordChange returns the stored seq and eventsAfter lists later events in order`() = withStore { store, file ->
        val e1 = anEvent("e1")
        val e2 = anEvent("e2", EventType.CARD_MOVED)
        val e3 = anEvent("e3", EventType.CARD_MOVED)

        val s1 = store.recordChange("c1", Card.create("A", Column.INBOX), e1)
        val s2 = store.recordChange("c2", Card.create("B", Column.INBOX), e2)
        val s3 = store.recordChange("c1", Card.create("A", Column.CODING), e3)

        assertThat(longs(file, "SELECT seq FROM event ORDER BY seq")).containsExactly(s1, s2, s3)
        assertThat(s1).isLessThan(s2)
        assertThat(s2).isLessThan(s3)
        assertThat(store.eventsAfter(0)).containsExactly(
            StoredEvent(s1, "c1", e1),
            StoredEvent(s2, "c2", e2),
            StoredEvent(s3, "c1", e3),
        )
        assertThat(store.eventsAfter(s2)).containsExactly(StoredEvent(s3, "c1", e3))
        assertThat(store.eventsAfter(s3)).isEmpty()
    }

    private fun rawEvent(file: Path, id: String, cardId: String, at: String, type: EventType, payloadJson: String) {
        jdbc(file) { c ->
            c.prepareStatement(
                "INSERT INTO event (id, card_id, at, actor, type, payload_json) VALUES (?, ?, ?, 'owner', ?, ?)",
            ).use { p ->
                p.setString(1, id)
                p.setString(2, cardId)
                p.setString(3, at)
                p.setString(4, type.name)
                p.setString(5, payloadJson)
                p.executeUpdate()
            }
        }
    }

    private fun queryPlan(file: Path, sql: String): String =
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("detail") else null }.joinToString(" | ")
                }
            }
        }

    @Test
    fun `AC-1 costEventsBetween returns only cost events of the day in seq order without decoding others`() = withStore { store, file ->
        val card = Card.create("Cost", Column.INBOX)
        val from = Instant.parse("2026-10-16T00:00:00Z")
        val to = Instant.parse("2026-10-17T00:00:00Z")
        fun cost(id: String, at: String) = anEvent(id, EventType.RUN_COST_RECORDED, at)
        val lateInDay = cost("in-late", "2026-10-16T12:00:00.5Z")
        val atStart = cost("in-start", "2026-10-16T00:00:00Z")
        val fractionAtStart = cost("in-start-frac", "2026-10-16T00:00:00.5Z")
        val lastMilli = cost("in-end", "2026-10-16T23:59:59.999Z")
        val written = listOf(
            cost("out-before", "2026-10-15T23:59:59.5Z"),
            lateInDay,
            anEvent("moved", EventType.CARD_MOVED, "2026-10-16T13:00:00Z"),
            atStart,
            cost("out-at-end", "2026-10-17T00:00:00Z"),
            fractionAtStart,
            cost("out-after-frac", "2026-10-17T00:00:00.5Z"),
            lastMilli,
        )
        written.forEach { store.recordChange("c1", card, it) }
        rawEvent(file, "corrupt", "c1", "2026-10-16T14:00:00Z", EventType.CARD_MOVED, "this is not json")

        val found = store.costEventsBetween(from, to)

        assertThat(found.map { it.event }).containsExactly(lateInDay, atStart, fractionAtStart, lastMilli)
        assertThat(found.map { it.seq }).isSorted()
        assertThat(found.map { it.cardId }.toSet()).containsExactly("c1")
        assertThat(store.costEventsBetween(to, to)).isEmpty()
    }

    @Test
    fun `AC-1 the cost-of-the-day query is served by the type and date index`() = withStore { store, file ->
        store.recordChange("c1", Card.create("Cost", Column.INBOX), anEvent("e1", EventType.RUN_COST_RECORDED))

        val plan = queryPlan(
            file,
            "SELECT * FROM event WHERE type = 'RUN_COST_RECORDED' AND at >= '2026-10-16T00:00:00' " +
                "AND at < '2026-10-17T00:00:00' ORDER BY seq",
        )

        assertThat(plan).contains("event_type_at")
    }

    @Test
    fun `AC-2 lastSeq gives the highest seq of the card for the type and actor, or null`() = withStore { store, file ->
        val card = Card.create("Blockable", Column.INBOX)
        store.recordChange("c1", card, anEvent("b1", EventType.CARD_BLOCKED))
        val ownerMove = store.recordChange("c1", card, anEvent("m-owner", EventType.CARD_MOVED))
        val blockedLast = store.recordChange("c1", card, anEvent("b2", EventType.CARD_BLOCKED))
        val agentMove = store.recordChange(
            "c1",
            card,
            Event(EventId("m-agent"), Instant.parse("2026-10-15T10:00:00Z"), Actor.Agent("engineer"), EventType.CARD_MOVED, mapOf("k" to "v")),
        )
        store.recordChange("c2", card, anEvent("other-card", EventType.CARD_BLOCKED))
        rawEvent(file, "corrupt", "c1", "2026-10-15T10:00:00Z", EventType.CARD_BLOCKED, "this is not json")
        val corruptSeq = longs(file, "SELECT max(seq) FROM event WHERE id = 'corrupt'").single()

        assertThat(store.lastSeq("c1", EventType.CARD_BLOCKED)).isEqualTo(corruptSeq)
        assertThat(store.lastSeq("c1", EventType.CARD_MOVED, Actor.Owner)).isEqualTo(ownerMove)
        assertThat(store.lastSeq("c1", EventType.CARD_MOVED, Actor.Agent("engineer"))).isEqualTo(agentMove)
        assertThat(store.lastSeq("c1", EventType.CARD_MOVED)).isEqualTo(agentMove)
        assertThat(blockedLast).isLessThan(agentMove)
        assertThat(store.lastSeq("c1", EventType.RUN_COST_RECORDED)).isNull()
        assertThat(store.lastSeq("c1", EventType.CARD_MOVED, Actor.Factory("scheduler"))).isNull()
        assertThat(store.lastSeq("ghost", EventType.CARD_BLOCKED)).isNull()
    }

    @Test
    fun `AC-2 the last-event query is served by the card, type and actor index`() = withStore { store, file ->
        store.recordChange("c1", Card.create("Blockable", Column.INBOX), anEvent("e1", EventType.CARD_BLOCKED))

        val withActor = queryPlan(
            file,
            "SELECT MAX(seq) FROM event WHERE card_id = 'c1' AND type = 'CARD_MOVED' AND actor = 'owner'",
        )
        val withoutActor = queryPlan(file, "SELECT MAX(seq) FROM event WHERE card_id = 'c1' AND type = 'CARD_BLOCKED'")

        assertThat(withActor).contains("event_card_type_actor")
        assertThat(withoutActor).contains("event_card_type_actor")
    }

    @Test
    fun `AC-3 version 5 database with data gains the cost indexes without losing data`() {
        val file = dir.resolve("v5.db")
        val card = Card.create("Write docs", Column.INBOX)
        val cost = anEvent("cost-1", EventType.RUN_COST_RECORDED, "2026-10-16T09:00:00Z")
        val blocked = anEvent("blocked-1", EventType.CARD_BLOCKED, "2026-10-16T10:00:00Z")
        Database.open(file).use { db ->
            val store = CardEventStore(db)
            store.recordChange("c1", card, anEvent("e1"))
            store.recordChange("c1", card, cost)
            store.recordChange("c1", card, blocked)
            store.assignProject("c1", "/repos/alpha")
        }
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("DROP INDEX IF EXISTS event_type_at")
                s.execute("DROP INDEX IF EXISTS event_card_type_actor")
                s.execute("PRAGMA user_version = 5")
            }
        }
        val seqsBefore = longs(file, "SELECT seq FROM event ORDER BY seq")
        val indexSql = "SELECT group_concat(name) FROM (SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name)"
        assertThat(scalar(file, indexSql)).doesNotContain("event_type_at").doesNotContain("event_card_type_actor")

        Database.open(file).use { db ->
            val store = CardEventStore(db)
            assertThat(store.card("c1")).isEqualTo(card)
            assertThat(store.projectOf("c1")).isEqualTo("/repos/alpha")
            assertThat(store.events("c1").map { it.id.value }).containsExactly("e1", "cost-1", "blocked-1")
            assertThat(store.costEventsBetween(Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-17T00:00:00Z")).map { it.event })
                .containsExactly(cost)
            assertThat(store.lastSeq("c1", EventType.CARD_BLOCKED)).isEqualTo(seqsBefore.last())
        }

        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(longs(file, "SELECT seq FROM event ORDER BY seq")).isEqualTo(seqsBefore)
        val fresh = dir.resolve("fresh-v6.db").also { Database.open(it).close() }
        assertThat(scalar(file, indexSql)).isEqualTo(scalar(fresh, indexSql)).contains("event_type_at", "event_card_type_actor")
    }

    @Test
    fun `AC-4 version 6 database with cards and events gains the project table and keeps everything`() {
        val file = dir.resolve("v6.db")
        val card = Card.create("Write docs", Column.INBOX)
        val first = anEvent("e1")
        val second = anEvent("e2", EventType.CARD_MOVED, "2026-10-16T11:00:00Z")
        Database.open(file).use { db ->
            val store = CardEventStore(db)
            store.recordChange("c1", card, first)
            store.recordChange("c1", card, second)
            store.assignProject("c1", "/repos/alpha")
        }
        jdbc(file) { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS project")
                s.execute("PRAGMA user_version = 6")
            }
        }
        val seqsBefore = longs(file, "SELECT seq FROM event ORDER BY seq")

        Database.open(file).use { db ->
            val store = CardEventStore(db)
            assertThat(store.card("c1")).isEqualTo(card)
            assertThat(store.events("c1")).containsExactly(first, second)
            assertThat(store.projectOf("c1")).isEqualTo("/repos/alpha")

            val paths = ProjectPathStore(db)
            assertThat(paths.all()).isEmpty()
            paths.add("/repos/alpha", Instant.parse("2026-10-16T12:00:00Z"))
            assertThat(paths.all()).containsExactly("/repos/alpha")
        }

        assertThat(scalar(file, "PRAGMA user_version")).isEqualTo("7")
        assertThat(longs(file, "SELECT seq FROM event ORDER BY seq")).isEqualTo(seqsBefore)
    }

    @Test
    fun `AC-5 project paths persist across reopen once each and in registration order`() {
        val file = dir.resolve("paths.db")
        Database.open(file).use { db ->
            val paths = ProjectPathStore(db)
            paths.add("/repos/zeta", Instant.parse("2026-10-16T10:00:00Z"))
            paths.add("/repos/alpha", Instant.parse("2026-10-16T10:01:00Z"))
            paths.add("/repos/zeta", Instant.parse("2026-10-16T10:02:00Z"))
        }

        Database.open(file).use { db ->
            assertThat(ProjectPathStore(db).all()).containsExactly("/repos/zeta", "/repos/alpha")
        }
    }

    @Test
    fun `AC-5 public API has no way to alter or delete an event`() {
        val names = CardEventStore::class.java.methods
            .filter { it.declaringClass == CardEventStore::class.java }
            .map { it.name }

        assertThat(names).contains("recordChange", "events", "card")
        assertThat(names).noneMatch { n ->
            listOf("update", "delete", "alter", "remove", "set", "edit", "replace").any { n.lowercase().startsWith(it) }
        }
    }
}
