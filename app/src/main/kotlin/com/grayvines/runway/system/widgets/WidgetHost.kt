package com.grayvines.runway.system.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.UserHandle

/**
 * The one app widget host of the process, under a fixed host id so the ids it allocates survive
 * restarts and reboots: a stored id is bound and drawable again on the next start with no work of
 * ours. Listening is tied to the launcher activity's visibility.
 */
class WidgetHost(context: Context) {
    private val context = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(this.context)
    private val host = AppWidgetHost(this.context, HOST_ID)

    /** Providers start sending updates; hosted views draw them. */
    fun startListening() = host.startListening()

    fun stopListening() = host.stopListening()

    /** A fresh id to bind a provider to; deleted again if the binding never happens. */
    fun allocateId(): Int = host.allocateAppWidgetId()

    /** Forgets an id: the provider is told, and the id can be handed out again. */
    fun deleteId(id: Int) = host.deleteAppWidgetId(id)

    /** What is bound to [id], or null when nothing is (never bound, or its app is gone). */
    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /**
     * Binds [provider] to [id] without asking, which the system allows once the user has granted
     * this launcher the right to bind widgets; false means the system's bind prompt is needed.
     */
    fun bind(id: Int, provider: ComponentName, user: UserHandle): Boolean =
        manager.bindAppWidgetIdIfAllowed(id, user, provider, null)

    /** The view that draws widget [id]; [context] is the activity's, for its theme. */
    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, id, info)

    companion object {
        /** Never change: the ids in the database were allocated under it. */
        const val HOST_ID = 0x52574159 // "RWAY"
    }
}
