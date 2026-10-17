package shipfloor.cli

import java.util.Properties

/** What the build wrote into the CLI: its version. */
internal object BuildInfo {
    private const val RESOURCE = "/shipfloor/cli/build.properties"

    val version: String by lazy {
        val stream = BuildInfo::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("$RESOURCE is missing from the CLI")
        val properties = stream.use { Properties().apply { load(it) } }
        properties.getProperty("version") ?: throw IllegalStateException("$RESOURCE has no version")
    }
}
