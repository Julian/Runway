package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drawer.AppDrawer
import com.grayvines.runway.ui.drawer.DrawerQuery
import com.grayvines.runway.ui.drawer.releasesAbandonedPull
import com.grayvines.runway.ui.folder.FolderSheet
import com.grayvines.runway.ui.menu.ItemMenu
import com.grayvines.runway.ui.menu.ItemMenuActions
import com.grayvines.runway.ui.menu.ItemMenuState
import kotlinx.coroutines.flow.Flow

/** Fraction of a grid cell's shorter side left empty around an icon. */
private const val ICON_INSET = 0.3f

private val DRAG_CORNER = 28.dp

/** A dwell flips every 450 ms; the scroll must be over well before the next tick. */

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
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    drag: DragSession,
    itemMenu: ItemMenuState?,
    itemMenuActions: ItemMenuActions,
    onDismissItemMenu: () -> Unit,
    openFolder: OpenFolder?,
    onLaunchFromFolder: (AppEntry) -> Unit,
    onCloseFolder: () -> Unit,
    drawerOpen: Boolean,
    drawerActions: DrawerActions,
    drawerQuery: DrawerQuery,
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
    PageShown(homePager, onHomePageShown)
    PageShown(dockPager, onDockPageShown)

    // Sized from the inset-free root so the drag overlay can use root pixel coordinates.
    val drawer = rememberDrawer(drawerOpen, drawerActions)
    BoxWithConstraints(Modifier.fillMaxSize().dragTracking(drag).releasesAbandonedPull(drawer)) {
        val insets = WindowInsets.systemBars.asPaddingValues()
        val cell = cellSize(DpSize(maxWidth, maxHeight), insets, settings)
        val iconSize = min(cell.width, cell.height) * (1f - ICON_INSET)
        PlaceDrawer(drawer, maxHeight, settings.drawerSwipe)
        // The home area steps back for a lifted icon and for the drawer alike.
        val lift = maxOf(liftProgress(lifting = drag.state != null), drawer.motion.revealed.value)
        HomeColumn(
            state = state,
            homePager = homePager,
            dockPager = dockPager,
            cell = cell,
            iconSize = iconSize,
            onLaunch = onLaunch,
            onSearch = onSearch,
            onOpenSettings = onOpenSettings,
            drag = drag,
            pagesModifier = drawer.pull,
            onHomePagePositioned = onHomePagePositioned,
            onDockPagePositioned = onDockPagePositioned,
            modifier = Modifier.fillMaxSize().pulledBack(lift).padding(insets),
        )
        openFolder?.let { OpenFolder(state, it, iconSize, onLaunchFromFolder, onCloseFolder) }
        itemMenu?.let { ItemMenu(it, itemMenuActions, onDismiss = onDismissItemMenu) }
        AppDrawer(
            revealed = drawer.motion.revealed.value,
            open = drawerOpen,
            apps = state.apps,
            query = drawerQuery,
            keyboard = settings.drawerKeyboard,
            index = settings.drawerIndex,
            columns = settings.drawerColumnsOrHome,
            iconSize = iconSize,
            labels = settings.drawerLabels,
            insets = insets,
            onPull = drawer.motion::dragBy,
            onPullEnd = drawer.release,
            onLaunch = onLaunchApp,
            onClose = drawerActions.close,
            drag = drag,
        )
        // Above the drawer too: an app pulled out of it is lifted while the drawer closes.
        DragOverlay(
            drag = drag,
            item = state.draggedItem(drag),
            cell = cell,
            iconSize = iconSize,
            lift = lift,
        )
    }
}

/** What is being dragged or settling: a placed item, or an app pulled out of the drawer. */
private fun HomeState.draggedItem(drag: DragSession): HomeItem? {
    val newApp = drag.state?.source?.newApp
    if (newApp != null) {
        val app = apps.firstOrNull { it.ref == newApp } ?: return null
        return HomeItem(0, ItemKind.APP, 0, 0, 1, 1, app.label, app)
    }
    return item(drag.draggedId ?: drag.settling?.itemId)
}

/** A vertical drag on the pages pulls the drawer with it; the pager keeps horizontal swipes. */

/** Search bar, pages and dock, stacked; every page shares [cell]. */
@Composable
private fun HomeColumn(
    state: HomeState,
    homePager: PagerState,
    dockPager: PagerState,
    cell: DpSize,
    iconSize: Dp,
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
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
            SearchBar(
                rowHeight = cell.height,
                target = state.searchTarget,
                onSearch = onSearch,
                onMenu = onOpenSettings,
                modifier = pagesModifier, // a swipe up from the search bar opens the drawer too
            )
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
            SearchBar(
                rowHeight = cell.height,
                target = state.searchTarget,
                onSearch = onSearch,
                onMenu = onOpenSettings,
                modifier = pagesModifier, // a swipe up from the search bar opens the drawer too
            )
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

/** The open folder's sheet, for as long as the folder's placement exists. */
@Composable
private fun OpenFolder(
    state: HomeState,
    open: OpenFolder,
    iconSize: Dp,
    onLaunch: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    val folder = state.item(open.itemId) ?: return
    FolderSheet(folder, open.from, iconSize, onLaunch, onClose)
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
