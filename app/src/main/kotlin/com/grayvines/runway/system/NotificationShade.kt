package com.grayvines.runway.system

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * The system's notification shade. Android hides the call that pulls it down, but launchers have
 * reached it by name for years; the EXPAND_STATUS_BAR permission in the manifest is what allows it.
 */
class NotificationShade(private val context: Context) {
    /** Pulls the shade down. False when this Android refuses, so the caller can say so. */
    @SuppressLint("WrongConstant", "PrivateApi")
    fun open(): Boolean {
        val statusBar = context.getSystemService("statusbar") ?: return false
        return try {
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(statusBar)
            true
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "could not open the notification shade", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to open the notification shade", e)
            false
        }
    }
}

private const val TAG = "Runway"
