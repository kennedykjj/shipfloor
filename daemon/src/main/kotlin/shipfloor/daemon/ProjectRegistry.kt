package shipfloor.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import shipfloor.core.InvalidPolicyException
import shipfloor.core.ProjectPolicies
import shipfloor.store.ProjectPathStore

/** [build] and [test] are command tokens (split on spaces, no shell); [ran] is the text a passing test run prints. */
data class ProjectLayer(
    val name: String,
    val path: String,
    val build: List<String>? = null,
    val test: List<String>? = null,
    val ran: String? = null,
)

data class Project(
    val name: String,
    val repo: String,
    val layers: List<ProjectLayer>,
    val policies: ProjectPolicies,
    val forbidden: List<String> = emptyList(),
)

/** The project's `factory.yml` on `main` is invalid; [field] names the offending field. */
class InvalidProjectConfigException(val field: String, message: String) : RuntimeException(message)

class ProjectNotFoundException(message: String) : RuntimeException(message)

class ProjectAlreadyRegisteredException(message: String) : RuntimeException(message)

/** Projects the daemon knows, in memory. A refused registration changes nothing. */
class ProjectRegistry(
    private val source: ProjectConfigSource,
    private val paths: ProjectPathStore? = null,
    private val log: DaemonLog = StderrDaemonLog,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val projects = LinkedHashMap<Path, Project>()

    @Synchronized
    fun register(repo: Path): Project {
        val canonical = canonicalRepo(repo)
        if (canonical in projects) {
            throw ProjectAlreadyRegisteredException("project already registered: $canonical")
        }
        val project = load(canonical)
        paths?.add(canonical.toString(), clock.instant())
        projects[canonical] = project
        return project
    }

    /** Registers in memory the persisted paths, reading each `factory.yml` from main now; failures are logged per path. */
    @Synchronized
    fun restore() {
        for (stored in paths?.all().orEmpty()) {
            try {
                val canonical = canonicalRepo(Path.of(stored))
                if (canonical in projects) continue
                projects[canonical] = load(canonical)
            } catch (e: InvalidProjectConfigException) {
                log.log("project_restore_failed", mapOf("path" to stored, "field" to e.field))
            } catch (e: ProjectNotFoundException) {
                log.log("project_restore_failed", mapOf("path" to stored, "field" to "path"))
            }
        }
    }

    private fun load(canonical: Path): Project {
        val text = source.read(canonical)
            ?: throw ProjectNotFoundException("$CONFIG_FILE not found on main in $canonical")
        return parse(text, canonical)
    }

    @Synchronized
    fun list(): List<Project> = projects.values.toList()

    private fun canonicalRepo(repo: Path): Path {
        val canonical = try {
            repo.toRealPath()
        } catch (e: IOException) {
            throw ProjectNotFoundException("repository not found: $repo")
        }
        if (!Files.isDirectory(canonical) || !Files.exists(canonical.resolve(".git"))) {
            throw ProjectNotFoundException("not a git repository: $canonical")
        }
        return canonical
    }

    private fun parse(text: String, repo: Path): Project {
        val root = try {
            Load(LoadSettings.builder().setCodePointLimit(MAX_CHARS).setMaxAliasesForCollections(MAX_ALIASES).build())
                .loadFromString(text)
        } catch (e: YamlEngineException) {
            throw InvalidProjectConfigException("factory.yml", "$CONFIG_FILE is not valid YAML")
        }
        val map = asMap(root, "factory.yml")
        val projectSection = asMap(map["project"], "project")
        val name = (projectSection["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw InvalidProjectConfigException("project.name", "project.name must be a non-empty text")
        val layers = parseLayers(map["layers"])
        val policies = try {
            ProjectPolicies.from(map.filterKeys { it in POLICY_KEYS })
        } catch (e: InvalidPolicyException) {
            throw InvalidProjectConfigException(e.field, "invalid policy field ${e.field}")
        }
        return Project(name, repo.toString(), layers, policies, parseForbidden(map["forbidden"]))
    }

    private fun parseForbidden(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        val items = raw as? List<*> ?: throw InvalidProjectConfigException("forbidden", "forbidden must be a list")
        return items.mapIndexed { index, item ->
            val field = "forbidden[$index]"
            val path = (item as? String)?.takeIf { it.isNotBlank() }
                ?: throw InvalidProjectConfigException(field, "$field must be a non-empty text")
            if (path.startsWith("/") || path.split("/").any { it == ".." }) {
                throw InvalidProjectConfigException(field, "$field must be a relative path without '..'")
            }
            path
        }
    }

    private fun parseLayers(raw: Any?): List<ProjectLayer> {
        val items = raw as? List<*>
        if (items.isNullOrEmpty()) throw InvalidProjectConfigException("layers", "layers must be a non-empty list")
        val layers = items.mapIndexed { index, item ->
            val field = "layers[$index]"
            val layer = asMap(item, field)
            val name = (layer["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw InvalidProjectConfigException("$field.name", "$field.name must be a non-empty text")
            val path = (layer["path"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw InvalidProjectConfigException("$field.path", "$field.path must be a non-empty text")
            ProjectLayer(
                name,
                path,
                build = optionalCommand(layer["build"], "$field.build"),
                test = optionalCommand(layer["test"], "$field.test"),
                ran = optionalText(layer["ran"], "$field.ran"),
            )
        }
        val duplicated = layers.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicated != null) {
            throw InvalidProjectConfigException("layers", "layers has a duplicated name: ${duplicated.key}")
        }
        return layers
    }

    private fun optionalText(raw: Any?, field: String): String? {
        if (raw == null) return null
        return (raw as? String)?.takeIf { it.isNotBlank() }
            ?: throw InvalidProjectConfigException(field, "$field must be a non-empty text")
    }

    private fun optionalCommand(raw: Any?, field: String): List<String>? =
        optionalText(raw, field)?.trim()?.split(WHITESPACE)

    private fun asMap(raw: Any?, field: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: throw InvalidProjectConfigException(field, "$field must be a mapping")
        return map.entries.associate { (key, value) ->
            (key as? String ?: throw InvalidProjectConfigException(field, "$field has a key that is not text")) to value
        }
    }

    // Keys the registry does not read (board, language, limits...) belong to the V0
    // factory.yml and are ignored here; the fields it does read are validated.
    private companion object {
        val WHITESPACE = Regex(" +")
        const val CONFIG_FILE = ".shipfloor/factory.yml"
        const val MAX_CHARS = 64 * 1024
        const val MAX_ALIASES = 10
        val POLICY_KEYS = setOf("delivery_mode", "signers", "conventions", "tracker", "jira_site", "jira_project", "dor")
    }
}
