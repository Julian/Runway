package com.grayvines.runway.system

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * The system's notification shade. Android hides the call that pulls it down, but launchers have
 * reached it by name for years; the EXPAND_STATUS_BAR permission in the manifest is what allows it.
 */
class NotificationShade(context: Context) {
    private val context = context.applicationContext

    private val expandMethod: Method? by lazy {
        try {
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel")
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "could not resolve expandNotificationsPanel", e)
            null
        }
    }

    /** Pulls the shade down. False when this Android refuses, so the caller can say so. */
    @SuppressLint("WrongConstant", "PrivateApi")
    fun open(): Boolean {
        val statusBar = context.getSystemService("statusbar") ?: return false
        val method = expandMethod ?: return false
        return try {
            method.invoke(statusBar)
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
