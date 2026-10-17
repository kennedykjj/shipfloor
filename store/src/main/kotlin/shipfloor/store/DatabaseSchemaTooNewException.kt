package shipfloor.store

import java.nio.file.Path

/** The database file was written by a newer version of the code than this one. */
class DatabaseSchemaTooNewException(val found: Int, val supported: Int, val path: Path) :
    RuntimeException("database schema version $found is newer than the supported version $supported: $path; " +
            "update the application or use a database file written by this version")
