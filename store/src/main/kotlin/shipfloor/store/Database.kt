package shipfloor.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import shipfloor.store.db.ShipfloorDatabase

/** The factory's SQLite database, versioned by `PRAGMA user_version`. */
class Database private constructor(private val driver: SqlDriver) : AutoCloseable {
    val queries: ShipfloorDatabase = ShipfloorDatabase(driver)

    override fun close() = driver.close()

    companion object {
        const val SCHEMA_VERSION = 7

        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

        fun open(path: Path): Database {
            ensureOwnerOnly(path)
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties().apply { setProperty("foreign_keys", "true") })
            try {
                // The version is read before any write PRAGMA: WAL changes the file header.
                val found = driver.readInt("PRAGMA user_version")
                if (found > SCHEMA_VERSION) throw DatabaseSchemaTooNewException(found, SCHEMA_VERSION, path)
                if (found < SCHEMA_VERSION) {
                    // DDL and user_version are transactional in SQLite: all or nothing.
                    // An exception inside transaction {} rolls everything back.
                    ShipfloorDatabase(driver).transaction {
                        if (found == 0) {
                            ShipfloorDatabase.Schema.create(driver)
                        } else {
                            ShipfloorDatabase.Schema.migrate(driver, found.toLong(), SCHEMA_VERSION.toLong())
                        }
                        driver.execute(null, "PRAGMA user_version = $SCHEMA_VERSION", 0)
                    }
                }
                driver.readInt("PRAGMA journal_mode=WAL")
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
            return Database(driver)
        }

        // A new file is created 0600 (the -wal and -shm files inherit its mode); an existing one is only checked.
        private fun ensureOwnerOnly(path: Path) {
            if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
            if (!Files.exists(path)) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                return
            }
            val found = Files.getPosixFilePermissions(path)
            if (found.any { it !in OWNER_ONLY }) {
                throw DatabaseInsecurePermissionsException(path, PosixFilePermissions.toString(found))
            }
        }

        private fun SqlDriver.readInt(sql: String): Int =
            executeQuery(null, sql, { cursor: SqlCursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            }, 0).value.toInt()
    }
}
