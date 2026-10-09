package au.buzz.ryzewave.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import au.buzz.ryzewave.App
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/**
 * Receives every notification posted on the phone (once the user has granted notification access) and hands it
 * to the [NotificationForwarder]. Bound by the system; nothing to start. Settings > Notifications shows whether
 * access is granted ([isAccessGranted]) and whether the system has bound us ([connected]).
 */
class WatchNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.i(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val quiet = runCatching { runBlocking {
            val settings = App.graph.settings
            if (!settings.quietHoursEnabled.first()) false else {
                val start = settings.quietHoursStart.first()
                val end = settings.quietHoursEnd.first()
                val hour = LocalTime.now().hour
                if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end
            }
        } }.getOrDefault(false)
        if (quiet) return
        val notification = n.notification ?: return
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        val posted = PostedNotification(
            packageName = n.packageName,
            key = n.key,
            title = title,
            text = text,
            appLabel = appLabel(this, n.packageName),
            ongoing = n.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            groupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
        val queued = App.graph.notifications.offer(posted)
        Log.i(TAG, "posted ${n.packageName} ${n.key}: ${if (queued) "queued" else "dropped"}")
    }

    companion object {
        private const val TAG = "WatchNotify"

        /** True between [onListenerConnected] and [onListenerDisconnected] (the system has bound the service). */
        @Volatile var connected = false
            private set

        fun component(context: Context): ComponentName = ComponentName(context, WatchNotificationListener::class.java)

        /** Whether the user has granted notification access to this app (Settings > Notification access). */
        fun isAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun appLabel(context: Context, packageName: String): String? = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
