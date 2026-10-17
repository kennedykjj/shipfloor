package shipfloor.store

import java.nio.file.Path

/** The database file is readable or writable by group or others; it holds the factory's state and audit trail. */
class DatabaseInsecurePermissionsException(val path: Path, val found: String) :
    RuntimeException("database file $path has permission $found; only the owner may access it (rw-------): " +
            "restrict it with chmod 600 before opening")
