package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.grayvines.runway.ui.drag.DragState

/** Draws the lifted cell under the pointer, in root coordinates, above everything else. */
@Composable
fun DragOverlay(drag: DragState?, item: HomeItem?, cell: DpSize, iconSize: Dp) {
    val app = item?.app
    if (drag == null || app == null) return
    Box(
        modifier =
            Modifier.offset {
                    IntOffset(
                        (drag.pointer.x - drag.grab.x).toInt(),
                        (drag.pointer.y - drag.grab.y).toInt(),
                    )
                }
                .size(cell),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(app, Modifier.size(iconSize))
    }
}
