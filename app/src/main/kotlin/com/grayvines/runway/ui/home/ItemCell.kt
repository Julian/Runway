package com.grayvines.runway.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.system.apps.AppEntry

private const val ICON_BITMAP_SIZE = 256

/** One cell's content: an app icon of [iconSize] (optionally labelled), or a placeholder. */
@Composable
fun ItemCell(item: HomeItem, iconSize: Dp, labels: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (item.app != null) {
                AppIcon(item.app, Modifier.size(iconSize))
            } else {
                Placeholder(item.kind)
            }
        }
        if (labels) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, modifier: Modifier = Modifier) {
    val bitmap =
        remember(app.key) { app.icon.toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE).asImageBitmap() }
    Image(bitmap = bitmap, contentDescription = app.label, modifier = modifier)
}

@Composable
private fun Placeholder(kind: ItemKind) {
    Text(kind.name, color = Color.White, style = MaterialTheme.typography.labelSmall)
}
