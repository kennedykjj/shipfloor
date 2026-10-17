package shipfloor.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import shipfloor.core.Column as BoardColumn

private const val SOCKET_RELATIVE_PATH = "shipfloor/daemon.sock"
private const val OWNER_TOKEN_FILE = "owner.token"
private const val REQUEST_BUFFER = 8
private const val TICK_EVERY_MILLIS = 60_000L
private const val SENT_REFRESH_MILLIS = 2_000L

/** Keys the window handles itself while the palette is open; everything else reaches the text field. */
private val PALETTE_KEYS = setOf(
    Key.Escape, Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd, Key.Enter, Key.NumPadEnter,
)

/** What a click on the tray or on a notification asks of the window. */
private sealed interface WindowRequest {
    /** Brings the window to the front on [cardId], or on the board when it is null. */
    data class Open(val cardId: String?) : WindowRequest

    data object ShowFeed : WindowRequest

    data object ShowSettings : WindowRequest

    data object Quit : WindowRequest
}

private class NoticePorts(val tray: TrayPort, val notifications: NotificationPort)

private object NoTray : TrayPort {
    override fun show(waiting: Int) = Unit

    override fun clear() = Unit

    override fun onActivated(listener: () -> Unit) = Unit
}

private object NoNotifications : NotificationPort {
    override fun notify(title: String, body: String, replacesId: Int): Int = 0

    override fun onActivated(listener: (notificationId: Int) -> Unit) = Unit
}

/** Milliseconds since the JVM started; the startup log uses it to say where the time goes. */
private fun sinceJvmStart(): Long = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

private fun logEvent(event: String, fields: Map<String, String>) {
    System.err.println((listOf("event=$event") + fields.map { "${it.key}=${it.value}" }).joinToString(" "))
}

/** Opens the D-Bus adapters; whatever fails is logged and replaced by a no-op port, so the app still starts. */
private fun openNoticePorts(requests: MutableSharedFlow<WindowRequest>): NoticePorts {
    val connection = try {
        DBusConnectionBuilder.forSessionBus().build()
    } catch (e: Exception) {
        logEvent("owner_notice_unavailable", mapOf("port" to "dbus", "reason" to e.javaClass.simpleName))
        return NoticePorts(NoTray, NoNotifications)
    }
    val tray = try {
        DbusTray(
            connection,
            NoticeLanguage.PT_BR,
            onQuit = { requests.tryEmit(WindowRequest.Quit) },
            onFeed = { requests.tryEmit(WindowRequest.ShowFeed) },
            onSettings = { requests.tryEmit(WindowRequest.ShowSettings) },
        )
    } catch (e: Exception) {
        logEvent("owner_notice_unavailable", mapOf("port" to "tray", "reason" to e.javaClass.simpleName))
        NoTray
    }
    val notifier = try {
        DbusNotifier(connection)
    } catch (e: Exception) {
        logEvent("owner_notice_unavailable", mapOf("port" to "notification", "reason" to e.javaClass.simpleName))
        NoNotifications
    }
    return NoticePorts(tray, notifier)
}

/** [commands] and [closure] are null without a daemon; the card modal then shows no owner action. */
@Composable
private fun OpenCard(
    cardId: String,
    source: CardDetailSource?,
    commands: OwnerCommands?,
    closure: ClosureCommands?,
    boardCard: BoardCard?,
    owner: OwnerMoveController?,
    onClose: () -> Unit,
) {
    val model = remember(cardId) {
        CardDetailModel(cardId, source ?: CardDetailSource { throw IOException("no runtime dir") })
    }
    val controller = remember(cardId, closure) { closure?.let { ClosureController(it, Dispatchers.IO) } }
    val state by model.state.collectAsState()
    val specReview = remember(cardId, commands) { commands?.let { SpecReviewController(it, Dispatchers.IO) } }
    LaunchedEffect(model) { withContext(Dispatchers.IO) { model.refresh() } }
    // The column only changes when the daemon event reaches the detail: after an accepted command, keep reading it.
    val sent = specReview?.state?.collectAsState()?.value?.sent == true
    LaunchedEffect(model, sent) {
        while (sent) {
            delay(SENT_REFRESH_MILLIS)
            withContext(Dispatchers.IO) { model.refresh() }
        }
    }
    CardDetailModal(
        cardId,
        state,
        boardCard = boardCard,
        controller = owner,
        onClose = onClose,
        specReview = specReview,
        closure = controller,
    )
}

private const val TOKEN_RELATIVE_PATH = "shipfloor/owner.token"

fun main() {
    logEvent("app_startup", mapOf("phase" to "main", "ms" to sinceJvmStart().toString()))
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
    // A second launch only asks the running app to show its window.
    val instance = runtimeDir?.let { SingleInstance.tryAcquire(Path.of(it, "shipfloor")) }
    if (instance is SingleInstance.Result.Running) {
        val reached = instance.pid?.let(SingleInstance::activate) == true
        logEvent("app_already_running", mapOf("shown" to reached.toString()))
        return
    }
    val monitor = runtimeDir?.let {
        ConnectionMonitor(SocketDaemonProbe(Path.of(it, SOCKET_RELATIVE_PATH)), SystemctlDaemonStarter(), Clock.systemUTC())
    }
    val feed = runtimeDir?.let { FeedRuntime(Path.of(it, SOCKET_RELATIVE_PATH), Clock.systemUTC(), ZoneId.systemDefault()) }
    val noRuntimeDir: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Disconnected(DisconnectReason.NO_RUNTIME_DIR))

    val requests =MutableSharedFlow<WindowRequest>(extraBufferCapacity = REQUEST_BUFFER)
    val ports = openNoticePorts(requests)
    logEvent("app_startup", mapOf("phase" to "ports", "ms" to sinceJvmStart().toString()))
    val shell = OwnerNoticeShell(
        ports.tray,
        ports.notifications,
        Clock.systemUTC(),
        ZoneId.systemDefault(),
        NoticeLanguage.PT_BR,
        ::logEvent,
    ) { cardId -> requests.tryEmit(WindowRequest.Open(cardId)) }
    val settingsClient = runtimeDir?.let {
        SocketSettingsClient(Path.of(it, SOCKET_RELATIVE_PATH), Path.of(it, TOKEN_RELATIVE_PATH))
    }

    val board = runtimeDir?.let {
        BoardMonitor(
            SocketBoardSource(Path.of(it, SOCKET_RELATIVE_PATH)),
            monitor?.state ?: noRuntimeDir,
            Dispatchers.IO,
            shell::onEvent,
            shell::onSnapshot,
            SocketColumnTimesSource(Path.of(it, SOCKET_RELATIVE_PATH)),
        )
    }
    val cardSource = runtimeDir?.let { SocketCardDetailSource(Path.of(it, SOCKET_RELATIVE_PATH)) }
    val closureCommands = runtimeDir?.let {
        val socket = Path.of(it, SOCKET_RELATIVE_PATH)
        SocketClosureCommands(socket, socket.resolveSibling(OWNER_TOKEN_FILE))
    }
    val pulse = runtimeDir?.let {
        val socket = Path.of(it, SOCKET_RELATIVE_PATH)
        PulseMonitor(SocketPulseSource(socket), SocketCostSummarySource(socket), Clock.systemUTC(), ZoneId.systemDefault(), Dispatchers.IO)
    }

    val ownerCommands = runtimeDir?.let {
        val socket = Path.of(it, SOCKET_RELATIVE_PATH)
        SocketOwnerCommands(socket, socket.resolveSibling(OWNER_TOKEN_FILE))
    }
    val owner = board?.let { monitor -> ownerCommands?.let { OwnerMoveController(it, monitor, Dispatchers.IO) } }

    val noBoard: StateFlow<BoardView?> = MutableStateFlow(null)
    val noPulse: StateFlow<PulseView> = MutableStateFlow(PulseView.loading())

    // The readings start now, while the window and the graphics are still being created, so the board is ready
    // when the first frame is.
    val background = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            logEvent("background_failed", mapOf("reason" to failure.javaClass.simpleName))
        },
    )
    monitor?.let { background.launch { it.run() } }
    board?.let { background.launch { it.run() } }
    pulse?.let { background.launch { it.run() } }

    application {
        var showSettings by remember { mutableStateOf(false) }
        val state by (monitor?.state ?: noRuntimeDir).collectAsState()
        val view by (board?.view ?: noBoard).collectAsState()
        val pulseView by (pulse?.view ?: noPulse).collectAsState()
        val connected = state == ConnectionState.Connected
        var showFeed by remember { mutableStateOf(false) }
        var windowVisible by remember { mutableStateOf(true) }
        val hasTray = ports.tray !== NoTray
        var detail by remember { mutableStateOf(DetailModalState()) }
        var listColumn by remember { mutableStateOf<BoardColumn?>(null) }
        var density by remember { mutableStateOf(BoardDensity.CARDS) }
        val escapeClaim = remember { EscapeClaim() }
        var palette by remember { mutableStateOf(PaletteState()) }
        var themeChoice by remember { mutableStateOf(ThemePreference.load()) }
        val paletteList = remember(view, palette.query) {
            paletteEntries(view?.board?.cards.orEmpty(), PaletteView.entries, palette.query)
        }

        fun choose(entry: PaletteEntry?) {
            when (entry) {
                is PaletteEntry.Card -> detail = detail.open(entry.card.id)
                is PaletteEntry.View -> {
                    detail = detail.close()
                    listColumn = null
                    showSettings = entry.view == PaletteView.SETTINGS && settingsClient != null
                    showFeed = entry.view == PaletteView.FEED
                }
                null -> Unit
            }
        }

        fun onPaletteKey(event: KeyEvent): Boolean {
            val shortcut = (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K
            return when {
                shortcut -> {
                    if (event.type == KeyEventType.KeyDown) palette = palette.toggle()
                    true
                }
                event.key == Key.Escape && !palette.open && detail.cardId != null && !escapeClaim.textFieldFocused -> {
                    if (event.type == KeyEventType.KeyDown) {
                        detail = detail.escape(paletteOpen = false, textFieldFocused = false)
                    }
                    true
                }
                event.key == Key.Escape && !palette.open && listColumn != null && !escapeClaim.textFieldFocused -> {
                    if (event.type == KeyEventType.KeyDown) listColumn = null
                    true
                }
                !palette.open || event.key !in PALETTE_KEYS -> false
                event.type != KeyEventType.KeyDown -> true
                else -> {
                    when (event.key) {
                        Key.Escape -> palette = palette.close()
                        Key.DirectionDown -> palette = palette.move(Direction.DOWN, paletteList.size)
                        Key.DirectionUp -> palette = palette.move(Direction.UP, paletteList.size)
                        Key.MoveHome -> palette = palette.move(Direction.FIRST, paletteList.size)
                        Key.MoveEnd -> palette = palette.move(Direction.LAST, paletteList.size)
                        else -> {
                            val choice = palette.select(paletteList)
                            palette = choice.state
                            choose(choice.entry)
                        }
                    }
                    true
                }
            }
        }

        LaunchedEffect(state) { logEvent("app_startup", mapOf("phase" to "connection_${state.javaClass.simpleName}", "ms" to sinceJvmStart().toString())) }
        // Every board event moves the cursor; the strip reads again instead of waiting for its 5 s tick.
        LaunchedEffect(view?.board?.lastSeq) { pulse?.refresh() }
        LaunchedEffect(feed, connected) { if (feed != null && connected) feed.run() }
        LaunchedEffect(Unit) {
            while (true) {
                delay(TICK_EVERY_MILLIS)
                withContext(Dispatchers.IO) { shell.tick() }
            }
        }
        Window(
            // The X hides the window and leaves the app in the tray; without a tray there would be no way back.
            onCloseRequest = { if (hasTray) windowVisible = false else exitApplication() },
            visible = windowVisible,
            title = Texts.TITLE,
            icon = remember { BitmapPainter(TrayIcons.renderTile(256).toComposeImageBitmap()) },
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition.PlatformDefault),
            onPreviewKeyEvent = ::onPaletteKey,
        ) {
            window.minimumSize = Dimension(360, 240)
            LaunchedEffect(Unit) { logEvent("app_startup", mapOf("phase" to "window", "ms" to sinceJvmStart().toString())) }
            LaunchedEffect(view != null) { if (view != null) logEvent("app_startup", mapOf("phase" to "board", "ms" to sinceJvmStart().toString())) }
            LaunchedEffect(Unit) {
                requests.collect { request ->
                    when (request) {
                        is WindowRequest.Open -> {
                            windowVisible = true
                            window.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                            showFeed = false
                            showSettings = false
                            request.cardId?.let { detail = detail.open(it) }
                        }
                        WindowRequest.ShowFeed -> {
                            windowVisible = true
                            window.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                            showSettings = false
                            showFeed = true
                        }
                        WindowRequest.ShowSettings -> {
                            windowVisible = true
                            window.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                            showSettings = settingsClient != null
                        }
                        WindowRequest.Quit -> exitApplication()
                    }
                }
            }
            val openSettings = settingsClient?.let { { showSettings = true } }
            val feedAvailable = feed != null && connected
            val systemDark = isSystemInDarkTheme()
            Tokens.dark = when (themeChoice) {
                ThemeChoice.SYSTEM -> systemDark
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            fun selectSection(section: AppSection) {
                showFeed = section == AppSection.FEED
            }
            fun openPalette() {
                palette = palette.toggle()
            }
            fun chooseTheme(choice: ThemeChoice) {
                themeChoice = choice
                ThemePreference.save(choice)
            }
            CompositionLocalProvider(LocalEscapeClaim provides escapeClaim) {
            Box(Modifier.fillMaxSize()) {
                when {
                    showSettings && settingsClient != null -> SettingsScreen(settingsClient, onBack = { showSettings = false })
                    feed != null && feedAvailable && showFeed -> ScreenWithTabs(AppSection.FEED, ::selectSection, feedAvailable, openSettings, ::openPalette, themeChoice, ::chooseTheme) {
                        val feedState by feed.client.state.collectAsState()
                        val scope = rememberCoroutineScope()
                        FeedScreen(
                            feedState,
                            LocalDate.now(feed.clock.withZone(feed.zone)),
                            onSelectDay = { day -> scope.launch(Dispatchers.IO) { feed.client.selectDay(day) } },
                            onSelectStage = { stage -> scope.launch(Dispatchers.IO) { feed.client.selectStage(stage) } },
                        )
                    }
                    // The belt stays on screen (dimmed) once it has been drawn; before that, the connection screen.
                    view != null -> ScreenWithTabs(AppSection.BOARD, ::selectSection, feedAvailable, openSettings, ::openPalette, themeChoice, ::chooseTheme) {
                        FactoryScreen(
                            view = view,
                            pulse = pulseView,
                            connected = connected,
                            controller = owner,
                            onReload = board?.let { monitor -> monitor::resync },
                            openCardId = detail.cardId,
                            focusReturnCardId = detail.focusReturnCardId,
                            onOpenCard = { detail = detail.open(it) },
                            onShowAll = { listColumn = it },
                            density = density,
                            onDensity = { density = it },
                        )
                    }
                    else -> ConnectionScreen(state, onOpenSettings = openSettings)
                }
                val onBoard = view != null && !(showSettings && settingsClient != null) && !(feed != null && feedAvailable && showFeed)
                if (onBoard) {
                    listColumn?.let { column ->
                        ColumnListModal(
                            column = column,
                            cards = view?.board?.cardsIn(column).orEmpty(),
                            density = density,
                            onOpenCard = { detail = detail.open(it) },
                            onClose = { listColumn = null },
                        )
                    }
                    detail.cardId?.let { id ->
                        OpenCard(id, cardSource, ownerCommands, closureCommands, view?.board?.cards?.firstOrNull { it.id == id }, owner) {
                            detail = detail.close()
                        }
                    }
                }
                if (palette.open) {
                    PaletteScreen(
                        state = palette,
                        entries = paletteList,
                        onQuery = { palette = palette.withQuery(it) },
                        onChoose = { index ->
                            val choice = palette.copy(active = index).select(paletteList)
                            palette = choice.state
                            choose(choice.entry)
                        },
                        onClose = { palette = palette.close() },
                    )
                }
            }
            }
        }
    }
    background.cancel()
}

/** The app bar above the active screen. */
@Composable
private fun ScreenWithTabs(
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    feedAvailable: Boolean,
    onSettings: (() -> Unit)?,
    onSearch: () -> Unit,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Tokens.ground)) {
        AppBar(section, feedAvailable, onSelect, onSettings, onSearch, theme, onTheme)
        Box(Modifier.fillMaxSize()) { content() }
    }
}
