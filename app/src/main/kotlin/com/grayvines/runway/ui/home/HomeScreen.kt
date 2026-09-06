package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.min
import com.grayvines.runway.ui.drag.Bounds
import kotlinx.coroutines.flow.Flow

/** Fraction of a grid cell's shorter side left empty around an icon. */
private const val ICON_INSET = 0.3f

/**
 * The launcher surface. The grid is [Settings.columns] × [Settings.rows]; the search bar and the
 * dock each occupy one full row and the pages get the rest. Icons everywhere share one size,
 * derived from the grid cell.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    goHome: Flow<Unit>,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession,
    onHomePagePositioned: (page: Int, Bounds) -> Unit,
    onDockPagePositioned: (page: Int, Bounds) -> Unit,
) {
    if (!state.loaded) return
    val settings = state.settings
    val homePager = rememberPagerState { state.homePages.size }
    val dockPager = rememberPagerState { state.dockPages.size }
    LaunchedEffect(goHome) { goHome.collect { homePager.animateScrollToPage(0) } }

    // Sized from the inset-free root so the drag overlay can use root pixel coordinates.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insets = WindowInsets.systemBars.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val usableWidth =
            maxWidth -
                insets.calculateLeftPadding(direction) -
                insets.calculateRightPadding(direction)
        val usableHeight =
            maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()
        val cell = DpSize(usableWidth / settings.columns, usableHeight / settings.rows)
        val iconSize = min(cell.width, cell.height) * (1f - ICON_INSET)
        Column(Modifier.fillMaxSize().padding(insets)) {
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

private fun HomeState.item(id: Long?): HomeItem? = id?.let {
    (homePages + dockPages).flatMap { p -> p.items }.firstOrNull { it.id == id }
}
