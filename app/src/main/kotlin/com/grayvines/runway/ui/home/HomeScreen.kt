package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drawer.AppDrawer
import com.grayvines.runway.ui.drawer.DrawerMotion
import com.grayvines.runway.ui.menu.ItemMenu
import com.grayvines.runway.ui.menu.ItemMenuActions
import com.grayvines.runway.ui.menu.ItemMenuState
import kotlinx.coroutines.flow.Flow

/** Fraction of a grid cell's shorter side left empty around an icon. */
private const val ICON_INSET = 0.3f

private val DRAG_CORNER = 28.dp

/** A dwell flips every 450 ms; the scroll must be over well before the next tick. */
private const val FLIP_SCROLL_MS = 250

/**
 * The launcher surface. The grid is [Settings.columns] × [Settings.rows]; the search bar and the
 * dock each occupy one full row and the pages get the rest. Icons everywhere share one size,
 * derived from the grid cell.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    goHome: Flow<Unit>,
    flipHomePage: Flow<Int>,
    flipDockPage: Flow<Int>,
    onLaunch: (HomeItem) -> Unit,
    onSearch: () -> Unit,
    drag: DragSession,
    itemMenu: ItemMenuState?,
    itemMenuActions: ItemMenuActions,
    onDismissItemMenu: () -> Unit,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,
    onLaunchApp: (AppEntry) -> Unit,
    onHomePagePositioned: (page: Int, Bounds) -> Unit,
    onHomePageShown: (page: Int) -> Unit,
    onDockPagePositioned: (page: Int, Bounds) -> Unit,
    onDockPageShown: (page: Int) -> Unit,
) {
    if (!state.loaded) return
    val settings = state.settings
    val homePager = rememberPagerState { state.homePages.size }
    val dockPager = rememberPagerState { state.dockPages.size }
    PagerCommands(homePager, goHome, flipHomePage)
    PageFlips(dockPager, flipDockPage)
    LaunchedEffect(homePager) { snapshotFlow { homePager.currentPage }.collect(onHomePageShown) }
    LaunchedEffect(dockPager) { snapshotFlow { dockPager.currentPage }.collect(onDockPageShown) }

    // Sized from the inset-free root so the drag overlay can use root pixel coordinates.
    BoxWithConstraints(Modifier.fillMaxSize().dragTracking(drag)) {
        val insets = WindowInsets.systemBars.asPaddingValues()
        val cell = cellSize(DpSize(maxWidth, maxHeight), insets, settings)
        val iconSize = min(cell.width, cell.height) * (1f - ICON_INSET)
        val scope = rememberCoroutineScope()
        val drawer = remember { DrawerMotion(scope) }
        // The home area steps back for a lifted icon and for the drawer alike.
        val lift = maxOf(liftProgress(lifting = drag.state != null), drawer.revealed.value)
        drawer.laidOut(with(LocalDensity.current) { maxHeight.toPx() })
        LaunchedEffect(drawerOpen) { drawer.settle(drawerOpen) }
        val releaseDrawer = { velocity: Float ->
            drawer.release(velocity, drawerOpen, onOpenDrawer, onCloseDrawer)
        }
        HomeColumn(
            state = state,
            homePager = homePager,
            dockPager = dockPager,
            cell = cell,
            iconSize = iconSize,
            onLaunch = onLaunch,
            onSearch = onSearch,
            drag = drag,
            pagesModifier = Modifier.pullsDrawer(drawer, releaseDrawer),
            onHomePagePositioned = onHomePagePositioned,
            onDockPagePositioned = onDockPagePositioned,
            modifier = Modifier.fillMaxSize().pulledBack(lift).padding(insets),
        )
        DragOverlay(
            drag = drag,
            item = state.item(drag.draggedId ?: drag.settling?.itemId),
            cell = cell,
            iconSize = iconSize,
            lift = lift,
        )
        if (itemMenu != null) {
            ItemMenu(itemMenu, itemMenuActions, onDismiss = onDismissItemMenu)
        }
        AppDrawer(
            revealed = drawer.revealed.value,
            open = drawerOpen,
            apps = state.apps,
            columns = settings.columns,
            iconSize = iconSize,
            labels = settings.drawerLabels,
            insets = insets,
            onPull = drawer::dragBy,
            onPullEnd = releaseDrawer,
            onLaunch = onLaunchApp,
            onClose = onCloseDrawer,
        )
    }
}

/** A vertical drag on the pages pulls the drawer with it; the pager keeps horizontal swipes. */
private fun Modifier.pullsDrawer(motion: DrawerMotion, onRelease: (velocity: Float) -> Unit) =
    composed {
        val release = rememberUpdatedState(onRelease)
        pointerInput(motion) {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = { release.value(tracker.calculateVelocity().y) },
                onDragCancel = { release.value(0f) },
                onVerticalDrag = { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    motion.dragBy(dy)
                },
            )
        }
    }

/** Search bar, pages and dock, stacked; every page shares [cell]. */
@Composable
private fun HomeColumn(
    state: HomeState,
    homePager: PagerState,
    dockPager: PagerState,
    cell: DpSize,
    iconSize: Dp,
    onLaunch: (HomeItem) -> Unit,
    onSearch: () -> Unit,
    drag: DragSession,
    onHomePagePositioned: (page: Int, Bounds) -> Unit,
    onDockPagePositioned: (page: Int, Bounds) -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the pages alone: the dock and search bar do not pull the drawer. */
    pagesModifier: Modifier = Modifier,
) {
    val settings = state.settings
    val dockSlot = DpSize(cell.width * settings.columns / settings.dockSlots, cell.height)
    Column(modifier) {
        if (settings.searchBarAtTop) {
            SearchBar(rowHeight = cell.height, target = state.searchTarget, onSearch = onSearch)
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
            modifier = Modifier.weight(1f).then(pagesModifier),
        )
        if (!settings.searchBarAtTop) {
            SearchBar(rowHeight = cell.height, target = state.searchTarget, onSearch = onSearch)
        }
        Dock(
            pages = state.dockPages,
            pagerState = dockPager,
            slots = settings.dockSlots,
            slot = dockSlot,
            iconSize = iconSize,
            labels = settings.dockLabels,
            onLaunch = onLaunch,
            drag = drag,
            onPagePositioned = onDockPagePositioned,
        )
    }
}

/**
 * The root drag tracker, built once: rebuilding a pointer-input modifier restarts it mid-gesture.
 */
@Composable
private fun Modifier.dragTracking(drag: DragSession): Modifier {
    val current = rememberUpdatedState(drag)
    return this.then(remember { Modifier.tracksDrag { current.value } })
}

/** Drives the home pager from outside: HOME returns to page 1, edge dwells flip pages. */
@Composable
private fun PagerCommands(pager: PagerState, goHome: Flow<Unit>, flipPage: Flow<Int>) {
    LaunchedEffect(goHome) { goHome.collect { pager.animateScrollToPage(0) } }
    PageFlips(pager, flipPage)
}

/**
 * Flips [pager] by each delta on [flipPage]; deltas with no page to go to are ignored. The scroll
 * is shorter than the dwell between ticks, so each page settles before the next flip.
 */
@Composable
private fun PageFlips(pager: PagerState, flipPage: Flow<Int>) {
    LaunchedEffect(flipPage) {
        flipPage.collect { delta ->
            val next = pager.currentPage + delta
            if (next in 0 until pager.pageCount) {
                pager.animateScrollToPage(next, animationSpec = tween(FLIP_SCROLL_MS))
            }
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
 * One progress for the whole pick-up, 0 at rest and 1 fully lifted: the icon comes forward as the
 * home area steps back, and on release both return on the settle spring.
 */
@Composable
private fun liftProgress(lifting: Boolean): Float {
    val lift = remember { Animatable(0f) }
    LaunchedEffect(lifting) {
        if (lifting) {
            lift.animateTo(1f, DragMotion.lift)
        } else {
            lift.animateTo(0f, DragMotion.settle)
        }
    }
    return lift.value
}

/** Pulled back by [lift] (0 at rest, 1 fully lifted) behind a faint rounded border. */
private fun Modifier.pulledBack(lift: Float): Modifier {
    val zoom = DragMotion.lerp(1f, DragMotion.ZOOM, lift)
    val borderAlpha = (DragMotion.BORDER_ALPHA * lift).coerceIn(0f, 1f)
    return graphicsLayer {
            scaleX = zoom
            scaleY = zoom
        }
        .border(1.dp, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(DRAG_CORNER))
}

private fun HomeState.item(id: Long?): HomeItem? = id?.let {
    (homePages + dockPages).flatMap { p -> p.items }.firstOrNull { it.id == id }
}
