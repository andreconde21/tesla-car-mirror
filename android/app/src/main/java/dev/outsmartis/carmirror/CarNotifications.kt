package dev.outsmartis.carmirror

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The phone's notifications, for the car: turn-by-turn from navigation apps (Google Maps, Waze:
 * their ongoing navigation notification) for the car's navigation card, and new messages and
 * alerts as short banners, when the car's "Notifications" setting is on.
 *
 * Nothing is stored: each notification goes to the connected car, if any, and is forgotten.
 */
class CarNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications }.getOrNull()?.firstOrNull { isNavigation(it) }?.let { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val l = listener ?: return
        if (isNavigation(sbn)) {
            navKey = sbn.key
            l(navigation(sbn))
            return
        }
        alert(sbn)?.let(l)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == navKey) {
            navKey = null
            lastNav = ""
            listener?.invoke(JSONObject().put("t", "nav").put("off", true))
        }
    }

    private fun isNavigation(sbn: StatusBarNotification) =
        sbn.notification.category == Notification.CATEGORY_NAVIGATION && sbn.isOngoing

    /** Google Maps: title "200 m", text "Rua Augusta", large icon = the turn arrow. */
    private fun navigation(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val e = n.extras
        val msg = JSONObject().put("t", "nav").put("pkg", sbn.packageName)
            .put("title", e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
            .put("text", e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            .put("sub", e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
        val key = msg.toString()
        if (key != lastNav) lastNavIcon = n.getLargeIcon()?.let { png(it, 96) }
        lastNav = key
        lastNavIcon?.let { msg.put("icon", it) }
        return msg
    }

    /** A banner-worthy notification: new, not silent, not ongoing, not media or progress. */
    private fun alert(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category in QUIET_CATEGORIES || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return null
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        // updates of the same notification (a chat re-posting its history) only show when the text changed
        val sig = "$title\u0000$text"
        if (recent[sbn.key] == sig) return null
        recent[sbn.key] = sig
        if (recent.size > 100) recent.keys.take(50).forEach { recent.remove(it) }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        return JSONObject().put("t", "notif").put("pkg", sbn.packageName).put("app", label)
            .put("title", title.take(120)).put("text", text.take(300))
    }

    private fun png(icon: Icon, size: Int): String? = runCatching {
        val d = icon.loadDrawable(this) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private var navKey: String? = null
    private var lastNav = ""
    private var lastNavIcon: String? = null
    private val recent = LinkedHashMap<String, String>()

    companion object {
        private val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_STATUS,
            Notification.CATEGORY_SYSTEM, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_NAVIGATION,
        )

        @Volatile var instance: CarNotifications? = null
            private set

        /** The connected car (nav updates always; banners only while the car wants them). */
        @Volatile var listener: ((JSONObject) -> Unit)? = null

        /** Current navigation state, sent when a car connects. */
        fun currentNav(): JSONObject? {
            val s = instance ?: return null
            val sbn = runCatching { s.activeNotifications }.getOrNull()?.firstOrNull { s.isNavigation(it) } ?: return null
            s.navKey = sbn.key
            return s.navigation(sbn)
        }

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, CarNotifications::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
