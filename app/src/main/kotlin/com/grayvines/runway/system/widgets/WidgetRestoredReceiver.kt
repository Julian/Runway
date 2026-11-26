package com.grayvines.runway.system.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grayvines.runway.appGraph
import com.grayvines.runway.data.remapWidgets
import com.grayvines.runway.ui.attempt
import kotlinx.coroutines.Dispatchers
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
        val (remapped, extraUnused) = parseWidgetRemap(oldIds, newIds)
        if (remapped.isEmpty() && extraUnused.isEmpty()) return
        val graph = context.appGraph
        val pending = goAsync()
        graph.appScope.launch(Dispatchers.IO) {
            try {
                attempt("remap restored widget ids") {
                    val unused = graph.workspace.remapWidgets(remapped) + extraUnused
                    unused.forEach { id -> runCatching { graph.widgets.deleteId(id) } }
                }
            } finally {
                pending?.finish()
            }
        }
    }
}

/**
 * Pairs [oldIds] with [newIds], ignoring invalid new ids (`<= 0`) and collecting any valid new id
 * paired with an invalid old id (`<= 0`) or superseded by a duplicate old id so the caller can
 * release it instead of leaking it.
 */
internal fun parseWidgetRemap(
    oldIds: IntArray,
    newIds: IntArray,
): Pair<Map<Int, Int>, Set<Int>> {
    if (oldIds.size != newIds.size || oldIds.isEmpty()) return emptyMap<Int, Int>() to emptySet()
    val remapped = LinkedHashMap<Int, Int>(oldIds.size)
    val unused = mutableSetOf<Int>()
    for (i in oldIds.indices) {
        val oldId = oldIds[i]
        val newId = newIds[i]
        if (newId <= 0) continue
        if (oldId <= 0) {
            unused += newId
        } else {
            remapped.put(oldId, newId)?.let(unused::add)
        }
    }
    unused -= remapped.values.toSet()
    return remapped to unused
}
