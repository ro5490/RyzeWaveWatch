package au.buzz.ryzewave.notify

import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.protocol.NotificationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridge between the [WatchNotificationListener] and [WatchApi.sendNotification]: applies the settings (master
 * switch, allow-list, debug forward-all) and the [NotificationFilter], then sends one message at a time through
 * a bounded queue (the newest wins when the watch cannot keep up). Messages that arrive while the watch is not
 * connected are dropped, not queued: a burst of stale texts on reconnect is worse than none. Android-free.
 */
class NotificationForwarder(
    settings: SettingsStore,
    private val watch: WatchApi,
    scope: CoroutineScope,
    ownPackage: String,
    clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { m, t -> println("Notify: $m${t?.let { " ($it)" } ?: ""}") },
) {
    private val filter = NotificationFilter(ownPackage, clock)

    val enabled: StateFlow<Boolean> = settings.notificationsEnabled.stateIn(scope, SharingStarted.Eagerly, false)
    val allowedPackages: StateFlow<Set<String>> = settings.allowedPackages.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val forwardAll: StateFlow<Boolean> = settings.forwardAllNotifications.stateIn(scope, SharingStarted.Eagerly, false)

    private val queue = Channel<OutgoingNotification>(capacity = QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Counters for the Settings screen / tests. */
    @Volatile var sentCount = 0
        private set
    @Volatile var droppedCount = 0
        private set
    @Volatile var lastError: String? = null
        private set

    init {
        scope.launch { for (m in queue) send(m) }
    }

    /** Called by the listener for every posted notification (any thread). Returns true when queued. */
    fun offer(n: PostedNotification): Boolean {
        if (!enabled.value) return dropped("notifications off", n.packageName)
        val out = filter.decide(n, allowedPackages.value, forwardAll.value)
            ?: return dropped(filter.lastDropReason ?: "filtered", n.packageName)
        val st = watch.status.value.state
        if (st != ConnectionState.CONNECTED && st != ConnectionState.SYNCING) return dropped("watch not connected", n.packageName)
        queue.trySend(out)
        return true
    }

    /** Settings > "Send test notification": bypasses the switch and the allow-list, still one at a time. */
    suspend fun sendTest(text: String = TEST_TEXT): Boolean = send(OutgoingNotification(NotificationType.GENERIC, text, "test"))

    private fun dropped(reason: String, pkg: String): Boolean {
        droppedCount++
        log("dropped $pkg: $reason", null)
        return false
    }

    private suspend fun send(m: OutgoingNotification): Boolean = try {
        val ok = watch.sendNotification(m.type, m.text)
        if (ok) {
            sentCount++
            lastError = null
        } else {
            droppedCount++
        }
        ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        droppedCount++
        lastError = e.message ?: e.javaClass.simpleName
        log("send to watch failed for ${m.packageName}: $lastError", null)
        false
    }

    companion object {
        const val QUEUE_CAPACITY = 8
        const val TEST_TEXT = "Dapper's SmartTrax: test"
    }
}
