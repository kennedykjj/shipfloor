package shipfloor.daemon

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Free text from outside the factory, wrapped so a consumer never mistakes it for an instruction. */
data class UntrustedText(val source: String, val text: String) {
    val trust: String = "untrusted"
}

/** An MCP session is bound to one project ([Project.repo]), one agent role and one card. */
data class McpSession(val repo: String, val role: String, val cardId: String)

/** Opens sessions and hands out one random bearer token per session. */
class McpSessions(private val clock: Clock = Clock.systemUTC(), private val ttl: Duration = DEFAULT_TTL) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Entry>()
    private val httpSessions = ConcurrentHashMap<String, String>()

    private class Entry(val session: McpSession, val expiresAt: Instant)

    init {
        require(!ttl.isZero && !ttl.isNegative) { "ttl must be positive" }
    }

    fun open(repo: String, role: String, cardId: String): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[digest(token)] = Entry(McpSession(repo, role, cardId),clock.instant().plus(ttl))
        return token
    }

    /** Looks the token up by its digest, so the comparison never depends on where a guess first differs. Expired tokens are dropped. */
    fun find(token: String): McpSession? {
        val wanted = digest(token)
        var found: Entry? = null
        var foundKey: String? = null
        sessions.forEach { (key, entry) ->
            if (MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), wanted.toByteArray(Charsets.UTF_8))) {
                found = entry
                foundKey = key
            }
        }
        val entry = found ?: return null
        if (!clock.instant().isBefore(entry.expiresAt)) {
            foundKey?.let(::forget)
            return null
        }
        return entry.session
    }

    /** Revokes the token and forgets every HTTP session it opened. */
    fun close(token: String) {
        forget(digest(token))
    }

    /** Ties an HTTP session id to the token that opened it. */
    fun bind(httpSessionId: String, token: String) {
        val key = digest(token)
        if (sessions.containsKey(key)) httpSessions[httpSessionId] = key
    }

    /** True only when [httpSessionId] was opened by [token] and both are still live. */
    fun owns(httpSessionId: String, token: String): Boolean {
        val bound = httpSessions[httpSessionId] ?: return false
        val wanted = digest(token)
        return MessageDigest.isEqual(bound.toByteArray(Charsets.UTF_8), wanted.toByteArray(Charsets.UTF_8)) &&
            sessions.containsKey(bound)
    }

    private fun forget(key: String) {
        sessions.remove(key)
        httpSessions.values.removeIf { it == key }
    }

    private fun digest(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofHours(24)
        private const val TOKEN_BYTES = 32
    }
}

/** Accepts a request only on the exact loopback host, with no `Origin` and a valid bearer token. */
class McpRequestGuard(private val sessions: McpSessions, private val port: Int) {

    fun authorize(host: String?, origin: String?, authorization: String?, sessionId: String? = null): McpSession? {
        if (origin != null) return null
        if (host != "127.0.0.1:$port") return null
        val header = authorization ?: return null
        if (!header.startsWith(BEARER)) return null
        val token = header.substring(BEARER.length)
        if (token.isEmpty()) return null
        val session = sessions.find(token) ?: return null
        if (sessionId != null && !sessions.owns(sessionId, token)) return null
        return session
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}
