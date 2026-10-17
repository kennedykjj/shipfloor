package shipfloor.adapters

import java.net.URI
import java.net.URISyntaxException

/** Names the offending field only, never its value. */
class InvalidRuntimeProviderException(field: String) : RuntimeException("invalid runtime provider: $field")

/** Where the claude subprocess sends its model calls; credentials are never part of it. */
sealed interface RuntimeProvider {
    /** Variables the claude subprocess needs for this provider; throws [InvalidRuntimeProviderException] when invalid. */
    fun environment(): Map<String, String> = emptyMap()

    /** Provider variables this provider lets through from the daemon environment instead of clearing them. */
    fun inheritedVariables(): Set<String> = emptySet()

    data object Anthropic : RuntimeProvider

    data class Bedrock(val region: String, val profile: String? = null) : RuntimeProvider {
        override fun environment(): Map<String, String> {
            if (region.isBlank()) throw InvalidRuntimeProviderException(REGION_FIELD)
            if (profile != null && profile.isBlank()) throw InvalidRuntimeProviderException(PROFILE_FIELD)
            return buildMap {
                put("CLAUDE_CODE_USE_BEDROCK", "1")
                put("AWS_REGION", region)
                profile?.let { put("AWS_PROFILE", it) }
            }
        }

        override fun inheritedVariables(): Set<String> = if (profile == null) setOf("AWS_PROFILE") else emptySet()
    }

    data class Vertex(val region: String, val projectId: String) : RuntimeProvider {
        override fun environment(): Map<String, String> {
            if (region.isBlank()) throw InvalidRuntimeProviderException(REGION_FIELD)
            if (projectId.isBlank()) throw InvalidRuntimeProviderException(PROJECT_FIELD)
            return mapOf(
                "CLAUDE_CODE_USE_VERTEX" to "1",
                "CLOUD_ML_REGION" to region,
                "ANTHROPIC_VERTEX_PROJECT_ID" to projectId,
            )
        }
    }

    data class Proxy(val baseUrl: String) : RuntimeProvider {
        override fun environment(): Map<String, String> {
            if (!isValidUrl(baseUrl)) throw InvalidRuntimeProviderException(URL_FIELD)
            return mapOf("ANTHROPIC_BASE_URL" to baseUrl)
        }

        override fun toString(): String = "Proxy(baseUrl=<redacted>)"
    }

    companion object {
        const val PROVIDER_FIELD = "SHIPFLOOR_PROVIDER"
        const val REGION_FIELD = "SHIPFLOOR_PROVIDER_REGION"
        const val PROFILE_FIELD = "SHIPFLOOR_PROVIDER_PROFILE"
        const val PROJECT_FIELD = "SHIPFLOOR_PROVIDER_PROJECT"
        const val URL_FIELD = "SHIPFLOOR_PROVIDER_URL"

        /** Reads and validates the provider configuration; absent `SHIPFLOOR_PROVIDER` means [Anthropic]. */
        fun fromEnv(env: Map<String, String>): RuntimeProvider {
            val provider: RuntimeProvider = when (env[PROVIDER_FIELD]?.trim()?.lowercase()) {
                null, "", "anthropic" -> Anthropic
                "bedrock" -> Bedrock(env[REGION_FIELD].orEmpty(), env[PROFILE_FIELD]?.takeIf { it.isNotBlank() })
                "vertex" -> Vertex(env[REGION_FIELD].orEmpty(), env[PROJECT_FIELD].orEmpty())
                "proxy" -> Proxy(env[URL_FIELD].orEmpty())
                else -> throw InvalidRuntimeProviderException(PROVIDER_FIELD)
            }
            provider.environment()
            return provider
        }

        private fun isValidUrl(value: String): Boolean {
            val uri = try {
                URI(value)
            } catch (e: URISyntaxException) {
                return false
            }
            return uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }
    }
}
