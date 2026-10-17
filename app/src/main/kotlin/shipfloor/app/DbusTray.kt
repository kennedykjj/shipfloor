package shipfloor.app

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The session bus (or one of its services) is not usable; a port failure the shell logs and survives. */
class DbusUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)

private const val ITEM_PATH = "/StatusNotifierItem"
private const val MENU_PATH = "/Menu"
private const val WATCHER_SERVICE = "org.kde.StatusNotifierWatcher"
private const val WATCHER_PATH = "/StatusNotifierWatcher"

private const val MENU_ROOT = 0
private const val MENU_OPEN = 1
private const val MENU_WAITING = 2
private const val MENU_QUIT = 3

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    @Suppress("FunctionName")
    fun RegisterStatusNotifierItem(service: String)
}

class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val argb: ByteArray,
) : Struct()

class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val icons: List<IconPixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val description: String,
) : Struct()

@DBusInterfaceName("org.kde.StatusNotifierItem")
@Suppress("FunctionName", "TooManyFunctions")
interface StatusNotifierItem : DBusInterface {
    @DBusBoundProperty(name = "Category")
    fun getCategory(): String

    @DBusBoundProperty(name = "Id")
    fun getId(): String

    @DBusBoundProperty(name = "Title")
    fun getTitle(): String

    @DBusBoundProperty(name = "Status")
    fun getStatus(): String

    @DBusBoundProperty(name = "WindowId")
    fun getWindowId(): Int

    @DBusBoundProperty(name = "IconName")
    fun getIconName(): String

    @DBusBoundProperty(name = "IconPixmap")
    fun getIconPixmap(): List<IconPixmap>

    @DBusBoundProperty(name = "ItemIsMenu")
    fun getItemIsMenu(): Boolean

    @DBusBoundProperty(name = "Menu")
    fun getMenu(): DBusPath

    @DBusBoundProperty(name = "ToolTip")
    fun getToolTip(): ToolTip

    fun Activate(x: Int, y: Int)

    fun SecondaryActivate(x: Int, y: Int)

    fun ContextMenu(x: Int, y: Int)

    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)

    class NewToolTip(path: String) : DBusSignal(path)

    class NewStatus(path: String, val status: String) : DBusSignal(path, status)
}

class MenuNode(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

class MenuProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

class MenuLayout(
    @field:Position(0) val revision: UInt32,
    @field:Position(1) val layout: MenuNode,
) : Tuple()

@DBusInterfaceName("com.canonical.dbusmenu")
@Suppress("FunctionName", "TooManyFunctions")
interface DbusMenu : DBusInterface {
    @DBusBoundProperty(name = "Version")
    fun getVersion(): UInt32

    @DBusBoundProperty(name = "TextDirection")
    fun getTextDirection(): String

    @DBusBoundProperty(name = "Status")
    fun getStatus(): String

    @DBusBoundProperty(name = "IconThemePath")
    fun getIconThemePath(): List<String>

    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayout

    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuProperties>

    fun GetProperty(id: Int, name: String): Variant<*>

    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    fun AboutToShow(id: Int): Boolean

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/**
 * StatusNotifierItem plus `com.canonical.dbusmenu` on the session bus: the tray icon, tooltip and menu
 * (`Abrir Shipfloor`, `N esperando você`, `Sair`). A thin adapter; the decisions are made by [OwnerNoticeShell].
 */
class DbusTray(
    private val connection: DBusConnection,
    private val language: NoticeLanguage,
    private val onQuit: () -> Unit,
) : TrayPort {
    @Volatile private var waiting = 0

    @Volatile private var revision = 1L

    @Volatile private var activated: () -> Unit = {}

    private val neutral = TrayIcons.SIZES.map { TrayIcons.pixmap(it, waiting = false) }
    private val waitingIcons = TrayIcons.SIZES.map { TrayIcons.pixmap(it, waiting = true) }

    private val item = object : StatusNotifierItem {
        override fun getCategory() = "ApplicationStatus"

        override fun getId() = "shipfloor"

        override fun getTitle() = "Shipfloor"

        override fun getStatus() = "Active"

        override fun getWindowId() = 0

        override fun getIconName() = ""

        override fun getIconPixmap() = if (waiting > 0) waitingIcons else neutral

        override fun getItemIsMenu() = false

        override fun getMenu() = DBusPath(MENU_PATH)

        override fun getToolTip() = ToolTip("", emptyList(), NoticeText.tooltip(waiting, language), "")

        override fun Activate(x: Int, y: Int) = activated()

        override fun SecondaryActivate(x: Int, y: Int) = activated()

        override fun ContextMenu(x: Int, y: Int) = Unit

        override fun Scroll(delta: Int, orientation: String) = Unit

        override fun isRemote() = false

        override fun getObjectPath() = ITEM_PATH
    }

    private val menu = object : DbusMenu {
        override fun getVersion() = UInt32(3)

        override fun getTextDirection() = "ltr"

        override fun getStatus() = "normal"

        override fun getIconThemePath() = emptyList<String>()

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayout {
            val children = listOf(MENU_OPEN, MENU_WAITING, MENU_QUIT).map { Variant(node(it), "(ia{sv}av)") }
            val root = MenuNode(MENU_ROOT, mapOf("children-display" to Variant("submenu")), children)
            return MenuLayout(UInt32(revision), root)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) =
            ids.map { MenuProperties(it, properties(it)) }

        override fun GetProperty(id: Int, name: String): Variant<*> = properties(id)[name] ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                MENU_OPEN -> activated()
                MENU_QUIT -> onQuit()
            }
        }

        override fun AboutToShow(id: Int) = false

        override fun isRemote() = false

        override fun getObjectPath() = MENU_PATH
    }

    init {
        try {
            connection.exportObject(ITEM_PATH, item)
            connection.exportObject(MENU_PATH, menu)
            val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            connection.requestBusName(name)
            connection.getRemoteObject(WATCHER_SERVICE, WATCHER_PATH, StatusNotifierWatcher::class.java)
                .RegisterStatusNotifierItem(name)
        } catch (e: DBusException) {
            throw DbusUnavailableException("tray registration failed", e)
        }
    }

    override fun show(waiting: Int) = update(waiting)

    override fun clear() = update(0)

    override fun onActivated(listener: () -> Unit) {
        activated = listener
    }

    private fun update(count: Int) {
        waiting = count
        revision += 1
        try {
            connection.sendMessage(StatusNotifierItem.NewIcon(ITEM_PATH))
            connection.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH))
            connection.sendMessage(DbusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), MENU_ROOT))
        } catch (e: DBusException) {
            throw DbusUnavailableException("tray update failed", e)
        }
    }

    private fun node(id: Int) = MenuNode(id, properties(id), emptyList())

    private fun properties(id: Int): Map<String, Variant<*>> =
        when (id) {
            MENU_OPEN -> mapOf("label" to Variant(if (language == NoticeLanguage.PT_BR) "Abrir Shipfloor" else "Open Shipfloor"))
            MENU_WAITING -> mapOf(
                "label" to Variant(NoticeText.tooltip(waiting, language).substringAfter(" · ")),
                "enabled" to Variant(false),
                "visible" to Variant(waiting > 0),
            )
            MENU_QUIT -> mapOf("label" to Variant(if (language == NoticeLanguage.PT_BR) "Sair" else "Quit"))
            else -> mapOf("children-display" to Variant("submenu"))
        }
}
