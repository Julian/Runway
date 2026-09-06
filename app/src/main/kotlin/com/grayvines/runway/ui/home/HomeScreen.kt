package com.grayvines.runway.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.drag.Bounds
import kotlinx.coroutines.flow.Flow

/** Fraction of a grid cell's shorter side left empty around an icon. */
private const val ICON_INSET = 0.3f

/** While dragging the home area pulls back a little, as if seen from a step further away. */
private const val DRAG_ZOOM = 0.94f
private val DRAG_CORNER = 28.dp
private const val DRAG_BORDER_ALPHA = 0.35f

/**
 * The launcher surface. The grid is [Settings.columns] × [Settings.rows]; the search bar and the
 * dock each occupy one full row and the pages get the rest. Icons everywhere share one size,
 * derived from the grid cell.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    goHome: Flow<Unit>,
    flipPage: Flow<Int>,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession,
    onHomePagePositioned: (page: Int, Bounds) -> Unit,
    onHomePageShown: (page: Int) -> Unit,
    onDockPagePositioned: (page: Int, Bounds) -> Unit,
    onDockPageShown: (page: Int) -> Unit,
) {
    if (!state.loaded) return
    val settings = state.settings
    val homePager = rememberPagerState { state.homePages.size }
    val dockPager = rememberPagerState { state.dockPages.size }
    PagerCommands(homePager, goHome, flipPage)
    LaunchedEffect(homePager) { snapshotFlow { homePager.currentPage }.collect(onHomePageShown) }
    LaunchedEffect(dockPager) { snapshotFlow { dockPager.currentPage }.collect(onDockPageShown) }

    // Sized from the inset-free root so the drag overlay can use root pixel coordinates.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insets = WindowInsets.systemBars.asPaddingValues()
        val cell = cellSize(DpSize(maxWidth, maxHeight), insets, settings)
        val iconSize = min(cell.width, cell.height) * (1f - ICON_INSET)
        Column(Modifier.fillMaxSize().pulledBackWhile(drag.state != null).padding(insets)) {
            if (settings.searchBarAtTop) {
                SearchBar(rowHeight = cell.height, target = state.searchTarget)
            }
            Workspace(
                pages = state.homePages,
                pagerState = homePager,
                columns = settings.columns,
                rows = settings.pageRows,
                cell = cell,
                iconSize = iconSize,
                labels = settings.homeLabels,
                onLaunch = onLaunch,
                drag = drag,
                onPagePositioned = onHomePagePositioned,
                modifier = Modifier.weight(1f),
            )
            if (!settings.searchBarAtTop) {
                SearchBar(rowHeight = cell.height, target = state.searchTarget)
            }
            Dock(
                pages = state.dockPages,
                pagerState = dockPager,
                slots = settings.dockSlots,
                rowHeight = cell.height,
                iconSize = iconSize,
                labels = settings.dockLabels,
                onLaunch = onLaunch,
                drag = drag,
                onPagePositioned = onDockPagePositioned,
            )
        }
        DragOverlay(
            drag = drag.state,
            item = state.item(drag.draggedId),
            cell = cell,
            iconSize = iconSize,
        )
    }
}

/** Drives the home pager from outside: HOME returns to page 1, edge dwells flip pages. */
@Composable
private fun PagerCommands(pager: PagerState, goHome: Flow<Unit>, flipPage: Flow<Int>) {
    LaunchedEffect(goHome) { goHome.collect { pager.animateScrollToPage(0) } }
    LaunchedEffect(flipPage) {
        flipPage.collect { delta ->
            val next = pager.currentPage + delta
            if (next in 0 until pager.pageCount) pager.animateScrollToPage(next)
        }
    }
}

/** One grid cell: the window minus system bars, divided by the grid. */
@Composable
private fun cellSize(window: DpSize, insets: PaddingValues, settings: Settings): DpSize {
    val direction = LocalLayoutDirection.current
    val width =
        window.width -
            insets.calculateLeftPadding(direction) -
            insets.calculateRightPadding(direction)
    val height = window.height - insets.calculateTopPadding() - insets.calculateBottomPadding()
    return DpSize(width / settings.columns, height / settings.rows)
}

/**
 * Zooms out slightly with a faint rounded border while [active]; reports the zoom for hit-testing.
 */
@Composable
private fun Modifier.pulledBackWhile(active: Boolean): Modifier {
    val zoom by animateFloatAsState(if (active) DRAG_ZOOM else 1f, label = "zoom")
    val borderAlpha by animateFloatAsState(if (active) DRAG_BORDER_ALPHA else 0f, label = "border")
    return graphicsLayer {
            scaleX = zoom
            scaleY = zoom
        }
        .border(1.dp, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(DRAG_CORNER))
}

private fun HomeState.item(id: Long?): HomeItem? = id?.let {
    (homePages + dockPages).flatMap { p -> p.items }.firstOrNull { it.id == id }
}
