package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.min
import kotlinx.coroutines.flow.Flow

/** Fraction of a grid cell's shorter side left empty around an icon. */
private const val ICON_INSET = 0.3f

/**
 * The launcher surface. The grid is [Settings.columns] × [Settings.rows]; the search bar and the
 * dock each occupy one full row and the pages get the rest. Icons everywhere share one size,
 * derived from the grid cell.
 */
@Composable
fun HomeScreen(state: HomeState, goHome: Flow<Unit>, onLaunch: (HomeItem) -> Unit) {
    if (!state.loaded) return
    val settings = state.settings
    val homePager = rememberPagerState { state.homePages.size }
    val dockPager = rememberPagerState { state.dockPages.size }
    LaunchedEffect(goHome) { goHome.collect { homePager.animateScrollToPage(0) } }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        val cell = DpSize(maxWidth / settings.columns, maxHeight / settings.rows)
        val iconSize = min(cell.width, cell.height) * (1f - ICON_INSET)
        Column(Modifier.fillMaxSize()) {
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
            )
        }
    }
}
