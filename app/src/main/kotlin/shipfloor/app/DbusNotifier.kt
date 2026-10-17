package shipfloor.app

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

private const val SERVICE = "org.freedesktop.Notifications"
private const val OBJECT_PATH = "/org/freedesktop/Notifications"
private const val DEFAULT_ACTION = "default"
private const val URGENCY_NORMAL: Byte = 1
private const val APP_NAME = "Shipfloor"

@DBusInterfaceName(SERVICE)
interface Notifications : DBusInterface {
    @Suppress("FunctionName", "LongParameterList")
    fun Notify(
        appName: String,
        replacesId: UInt32,
        icon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: @JvmSuppressWildcards Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)
}

/** `org.freedesktop.Notifications` on the session bus; a thin adapter with no decisions of its own. */
class DbusNotifier(private val connection: DBusConnection) : NotificationPort {
    private val service = connection.getRemoteObject(SERVICE, OBJECT_PATH, Notifications::class.java)

    override fun notify(title: String, body: String, replacesId: Int): Int =
        try {
            service.Notify(
                APP_NAME,
                UInt32(replacesId.toLong()),
                "",
                title,
                body,
                listOf(DEFAULT_ACTION, "Abrir"),
                mapOf("urgency" to Variant(URGENCY_NORMAL)),
                -1,
            ).toInt()
        } catch (e: DBusException) {
            throw DbusUnavailableException("notify failed", e)
        }

    override fun onActivated(listener: (notificationId: Int) -> Unit) {
        try {
            connection.addSigHandler(
                Notifications.ActionInvoked::class.java,
                DBusSigHandler<Notifications.ActionInvoked> { signal ->
                    if (signal.actionKey == DEFAULT_ACTION) listener(signal.id.toInt())
                },
            )
        } catch (e: DBusException) {
            throw DbusUnavailableException("subscribe to notification actions failed", e)
        }
    }
}
