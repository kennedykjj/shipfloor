package shipfloor.store

import java.time.Instant

/** Canonical repository paths of registered projects, kept in registration order. */
class ProjectPathStore(private val database: Database) {
    /** Records the path once; a path already stored keeps its original position and timestamp. */
    fun add(path: String, at: Instant) {
        database.queries.schemaQueries.insertProject(path, at.toString())
    }

    fun all(): List<String> = database.queries.schemaQueries.allProjects().executeAsList()
}
