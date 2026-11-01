package com.grayvines.runway.system.badges

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.grayvines.runway.appGraph

/**
 * Bound by the system while Runway has notification access. Forwards connection and notification
 * changes to [BadgeHost], which holds the live counts for the UI.
 */
class BadgeListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        appGraph.badges.onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        appGraph.badges.onListenerDisconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        appGraph.badges.refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        appGraph.badges.refresh()
    }

    override fun onDestroy() {
        appGraph.badges.onListenerDisconnected(this)
        super.onDestroy()
    }
}
