package shipfloor.daemon

import java.nio.file.Path

/** Renders the systemd user unit for the daemon. Pure: the launcher path is the only input. */
object ServiceUnit {
    const val UNIT_NAME = "shipfloor.service"

    private val unsafeChars = setOf(' ', '\t', '\n', '\r', '%', '$', '"', '\'', '\\')

    /** Throws [IllegalArgumentException] when [launcher] is relative or would break or inject unit lines. */
    fun render(launcher: Path): String {
        val text = launcher.toString()
        require(launcher.isAbsolute) { "launcher path must be absolute" }
        require(text.none { it in unsafeChars || it.isISOControl() }) {
            "launcher path must not contain whitespace, control characters, %, \$, quotes or backslash"
        }
        return """
            |[Unit]
            |Description=shipfloor daemon
            |
            |[Service]
            |Type=simple
            |ExecStart=$text
            |Restart=on-failure
            |RestartSec=5
            |SyslogIdentifier=shipfloor
            |NoNewPrivileges=true
            |UMask=0077
            |EnvironmentFile=-%h/.config/shipfloor/service.env
            |
            |[Install]
            |WantedBy=default.target
            |
        """.trimMargin()
    }
}
