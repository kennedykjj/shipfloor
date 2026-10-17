package shipfloor.daemon

import java.time.Instant

/** Tells whether the process that owns a run lock is still the same live process. */
fun interface ProcessProbe {
    fun isAlive(pid: Long, startedAt: Instant): Boolean
}

/** Alive only if the PID exists and its start instant matches, so a recycled PID does not count. */
object SystemProcessProbe : ProcessProbe {
    override fun isAlive(pid: Long, startedAt: Instant): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        val actualStart = handle.info().startInstant().orElse(null) ?: return false
        return actualStart == startedAt
    }
}
