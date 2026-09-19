package com.grayvines.runway.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.system.apps.AppEntry

/**
 * Every app, in the drawer's order, each with a switch that hides it from the drawer. The list is
 * the whole page and scrolls itself, lazily: there can be hundreds of apps.
 */
@Composable
internal fun HiddenAppsPage(
    apps: List<AppEntry>,
    hidden: Set<AppRef>,
    onHide: (app: AppRef, hidden: Boolean) -> Unit,
    padding: PaddingValues,
) {
    LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "A hidden app is left out of the drawer and its search, and taken out of any " +
                    "drawer folder. On the home screen and in the dock it stays where it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(apps, key = { it.key }) { app ->
            val isHidden = app.ref in hidden
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(isHidden, role = Role.Switch) { onHide(app.ref, it) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(app.bitmap, contentDescription = null, Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Text(app.label, Modifier.weight(1f))
                Switch(checked = isHidden, onCheckedChange = null)
            }
        }
    }
}
