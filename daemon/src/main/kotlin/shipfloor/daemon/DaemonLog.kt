package shipfloor.daemon

/** Structured daemon log: an event name and `key=value` fields. Never message content. */
fun interface DaemonLog {
    fun log(event: String, fields: Map<String, String>)
}

/** Prints `event=<e> key=value ...` lines to stderr. */
object StderrDaemonLog : DaemonLog {
    override fun log(event: String, fields: Map<String, String>) {
        val line = buildString {
            append("event=").append(event)
            fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        }
        System.err.println(line)
    }
}
