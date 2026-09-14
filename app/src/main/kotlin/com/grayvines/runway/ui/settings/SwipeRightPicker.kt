package com.grayvines.runway.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.settings.SwipeAction
import com.grayvines.runway.system.apps.AppEntry

/**
 * What a swipe right on the first home page does: nothing, or opens an app picked from a list of
 * them all. A chosen app that is not installed (uninstalled since, or restored from another
 * device's backup) stays chosen, and says so.
 */
@Composable
internal fun SwipeRightPicker(
    chosen: SwipeAction?,
    apps: List<AppEntry>,
    onChoose: (SwipeAction?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("On the first page", Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }, modifier = Modifier.widthIn(max = 220.dp)) {
            when (chosen) {
                null -> {
                    Text(NOTHING)
                }
                is SwipeAction.OpenApp -> {
                    val app = apps.firstOrNull { it.ref == chosen.app }
                    if (app != null) {
                        Image(app.bitmap, contentDescription = null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        app?.label ?: "An app not installed",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
    }
    if (open) {
        AppChoiceDialog(
            title = "Swipe right opens",
            apps = apps,
            onChoose = { app ->
                open = false
                onChoose(app?.let { SwipeAction.OpenApp(it.ref) })
            },
            onDismiss = { open = false },
        )
    }
}

/** Nothing, then every app, one per row; [onChoose] is told which, null for nothing. */
@Composable
private fun AppChoiceDialog(
    title: String,
    apps: List<AppEntry>,
    onChoose: (AppEntry?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            LazyColumn {
                item { ChoiceRow(NOTHING, icon = null) { onChoose(null) } }
                items(apps, key = { it.key }) { app ->
                    ChoiceRow(app.label, app.bitmap) { onChoose(app) }
                }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, icon: ImageBitmap?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, Modifier.size(32.dp))
        } else {
            Spacer(Modifier.size(32.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}

private const val NOTHING = "Nothing"
