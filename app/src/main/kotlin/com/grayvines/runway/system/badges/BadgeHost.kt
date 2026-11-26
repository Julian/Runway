package com.grayvines.runway.system.badges

import android.app.Notification
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.grayvines.runway.model.ActiveNotification
import com.grayvines.runway.model.PackageKey
import com.grayvines.runway.model.badgeCounts
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the live notification badge counts per package and profile while [BadgeListenerService] is
 * connected, and checks or requests notification listener access in system Settings.
 */
class BadgeHost(context: Context, scope: CoroutineScope) {
    private val context = context.applicationContext
    private val notificationManager = this.context.getSystemService(NotificationManager::class.java)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val listenerComponent = ComponentName(this.context, BadgeListenerService::class.java)
    private val profileSerials = ConcurrentHashMap<UserHandle, Long>()
    private val poller =
        BadgePoller<BadgeListenerService>(
            scope = scope,
            dispatcher = Dispatchers.IO,
            onDisconnect = profileSerials::clear,
            onRefreshWhileDisconnected = ::requestRebindIfGranted,
            readCounts = ::readActiveNotifications,
        )

    /** Current notification badge counts per `(packageName, profile)`. Empty when disconnected. */
    val counts: StateFlow<Map<PackageKey, Int>> = poller.counts

    /** True when the user has granted Runway notification listener access in system Settings. */
    @Suppress("TooGenericExceptionCaught")
    fun hasAccess(): Boolean =
        try {
            notificationManager.isNotificationListenerAccessGranted(listenerComponent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not check notification listener access", e)
            false
        }

    /**
     * Opens the system's Notification access screen so the user can grant or revoke access; false
     * when this device has no such screen or refuses it.
     */
    fun openAccessSettings(): Boolean =
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no notification access settings screen", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to open notification access settings", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun requestRebindIfGranted() {
        if (!hasAccess()) return
        try {
            NotificationListenerService.requestRebind(listenerComponent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not request notification listener rebind", e)
        }
    }

    /**
     * Schedules an off-main-thread re-read of active notifications from the connected
     * [BadgeListenerService], or clears [counts] when the service is not connected. Bursts coalesce
     * into a single read. Safe to call on resume in case a callback was missed.
     */
    fun refresh() = poller.refresh()

    @Suppress("TooGenericExceptionCaught")
    private fun readActiveNotifications(listener: BadgeListenerService): Map<PackageKey, Int> {
        val active =
            try {
                listener.activeNotifications
                    ?.mapNotNull { notification ->
                        try {
                            notification.toActiveNotification()
                        } catch (e: RuntimeException) {
                            Log.w(
                                TAG,
                                "could not read notification for ${notification.packageName}",
                                e,
                            )
                            null
                        }
                    }
                    .orEmpty()
            } catch (e: SecurityException) {
                Log.w(TAG, "not allowed to read active notifications", e)
                emptyList()
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not read active notifications", e)
                emptyList()
            }
        return badgeCounts(active)
    }

    internal fun onListenerConnected(listener: BadgeListenerService) = poller.connect(listener)

    internal fun onListenerDisconnected(listener: BadgeListenerService) =
        poller.disconnect(listener)

    /** Sets [counts] from [notifications] for on-device tests, where the system listener is off. */
    internal fun setNotificationsForTest(notifications: Iterable<ActiveNotification>) {
        poller.setForTest(badgeCounts(notifications))
    }

    private fun StatusBarNotification.toActiveNotification(): ActiveNotification {
        val flags = notification.flags
        val groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
        val foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val mediaSession =
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        val profile = profileSerials.getOrPut(user) { userManager.getSerialNumberForUser(user) }
        return ActiveNotification(
            packageName = packageName,
            profile = profile,
            number = notification.number,
            isGroupSummary = groupSummary,
            isOngoing = isOngoing || foregroundService,
            isMedia = mediaSession,
        )
    }

    private companion object {
        const val TAG = "Runway"
    }
}

/**
 * Serialises listener attach/detach against conflated background reads: an initial [refresh] sent
 * before the worker coroutine starts is buffered by the conflated channel, and a disconnect racing
 * an in-flight read cannot be overwritten by stale counts.
 */
internal class BadgePoller<L : Any>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val onDisconnect: () -> Unit = {},
    private val onRefreshWhileDisconnected: (() -> Unit)? = null,
    private val readCounts: (L) -> Map<PackageKey, Int>,
) {
    private val stateLock = Any()
    private var listener: L? = null

    private val _counts = MutableStateFlow<Map<PackageKey, Int>>(emptyMap())
    val counts: StateFlow<Map<PackageKey, Int>> = _counts.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            while (true) {
                requests.receive()
                pollOnce()
            }
        }
    }

    fun connect(connected: L) {
        synchronized(stateLock) { listener = connected }
        requests.trySend(Unit)
    }

    fun disconnect(disconnected: L) {
        synchronized(stateLock) {
            if (listener === disconnected) {
                listener = null
                onDisconnect()
                _counts.value = emptyMap()
            }
        }
    }

    fun refresh() {
        val active =
            synchronized(stateLock) {
                if (listener == null) {
                    _counts.value = emptyMap()
                    false
                } else {
                    true
                }
            }
        if (active || onRefreshWhileDisconnected != null) requests.trySend(Unit)
    }

    fun setForTest(counts: Map<PackageKey, Int>) {
        synchronized(stateLock) { _counts.value = counts }
    }

    private fun pollOnce() {
        val current =
            synchronized(stateLock) {
                listener
                    ?: run {
                        _counts.value = emptyMap()
                        null
                    }
            }
        if (current == null) {
            onRefreshWhileDisconnected?.invoke()
            return
        }
        val next = readCounts(current)
        synchronized(stateLock) {
            if (listener === current) {
                _counts.value = next
            }
        }
    }
}
