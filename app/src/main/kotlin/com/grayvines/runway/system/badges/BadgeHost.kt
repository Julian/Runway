package com.grayvines.runway.system.badges

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import com.grayvines.runway.model.ActiveNotification
import com.grayvines.runway.model.PackageKey
import com.grayvines.runway.model.badgeCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the live notification badge counts per package and profile while [BadgeListenerService] is
 * connected, and checks or requests notification listener access in system Settings.
 */
class BadgeHost(context: Context) {
    private val context = context.applicationContext
    private val notificationManager = this.context.getSystemService(NotificationManager::class.java)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val listenerComponent = ComponentName(this.context, BadgeListenerService::class.java)

    @Volatile private var service: BadgeListenerService? = null

    private val _counts = MutableStateFlow<Map<PackageKey, Int>>(emptyMap())

    /** Current notification badge counts per `(packageName, profile)`. Empty when disconnected. */
    val counts: StateFlow<Map<PackageKey, Int>> = _counts.asStateFlow()

    /** True when the user has granted Runway notification listener access in system Settings. */
    fun hasAccess(): Boolean =
        notificationManager.isNotificationListenerAccessGranted(listenerComponent)

    /** Opens the system's Notification access screen so the user can grant or revoke access. */
    fun openAccessSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Re-reads active notifications from the connected [BadgeListenerService], or clears [counts]
     * when the service is not connected. Safe to call on resume in case a callback was missed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun refresh() {
        val listener = service
        if (listener == null) {
            _counts.value = emptyMap()
            return
        }
        val active =
            try {
                listener.activeNotifications?.map { it.toActiveNotification() }.orEmpty()
            } catch (e: SecurityException) {
                Log.w(TAG, "not allowed to read active notifications", e)
                emptyList()
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not read active notifications", e)
                emptyList()
            }
        _counts.value = badgeCounts(active)
    }

    internal fun onListenerConnected(listener: BadgeListenerService) {
        service = listener
        refresh()
    }

    internal fun onListenerDisconnected(listener: BadgeListenerService) {
        if (service === listener) {
            service = null
            _counts.value = emptyMap()
        }
    }

    /** Sets [counts] from [notifications] for on-device tests, where the system listener is off. */
    internal fun setNotificationsForTest(notifications: Iterable<ActiveNotification>) {
        _counts.value = badgeCounts(notifications)
    }

    private fun StatusBarNotification.toActiveNotification(): ActiveNotification {
        val flags = notification.flags
        val groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
        val foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val mediaSession =
            notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        return ActiveNotification(
            packageName = packageName,
            profile = userManager.getSerialNumberForUser(user),
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
