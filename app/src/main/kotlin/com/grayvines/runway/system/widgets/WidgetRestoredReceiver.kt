package com.grayvines.runway.system.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grayvines.runway.appGraph
import com.grayvines.runway.data.remapWidgets
import com.grayvines.runway.ui.attempt
import kotlinx.coroutines.launch

/**
 * Hears from the system when a backup restore has given this host's widgets fresh ids, puts those
 * ids in the layout in place of the old ones, and gives back any no placement uses.
 */
class WidgetRestoredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED) return
        if (intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, 0) != WidgetHost.HOST_ID) return
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        if (oldIds.size != newIds.size || oldIds.isEmpty()) return
        val remapped = oldIds.indices.associate { i -> oldIds[i] to newIds[i] }
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                attempt("remap restored widget ids") {
                    graph.workspace.remapWidgets(remapped).forEach(graph.widgets::deleteId)
                }
            } finally {
                pending?.finish()
            }
        }
    }
}
