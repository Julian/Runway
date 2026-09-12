package com.grayvines.runway.ui.drawer

import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder

/** What the drawer's search field holds and does. */
class DrawerQuery(val text: String, val onChange: (String) -> Unit, val onSubmit: () -> Unit)

/** The apps [query] narrows the drawer to. Empty or blank shows everything. */
fun List<AppEntry>.matching(query: String): List<AppEntry> = matching(query) { it.label }

/**
 * Case and accents aside ([LabelOrder.folded]): entries whose label, or a word of it, starts with
 * [query] come first, then those that merely contain it, each group in the order given.
 */
internal fun <T> List<T>.matching(query: String, label: (T) -> String): List<T> {
    val wanted = LabelOrder.folded(query.trim())
    if (wanted.isEmpty()) return this
    val folded = associateWith { LabelOrder.folded(label(it)) }
    val (starts, contains) =
        filter { folded.getValue(it).contains(wanted) }
            .partition { entry -> folded.getValue(entry).split(' ').any { it.startsWith(wanted) } }
    return starts + contains
}
