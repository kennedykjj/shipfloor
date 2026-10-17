package shipfloor.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import shipfloor.cli.MODULE_NAME as CLI
import shipfloor.daemon.MODULE_NAME as DAEMON

class ClasspathTest {
    @Test
    fun `end-to-end tests reach the daemon and the cli`() {
        assertThat(listOf(DAEMON, CLI)).containsExactly("daemon", "cli")
    }
}
