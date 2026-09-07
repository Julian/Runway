package com.grayvines.runway.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.home.AppIcon
import com.grayvines.runway.ui.home.DragHandlers
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.dragAfterLongPress

const val DRAWER_TAG = "drawer"
const val DRAWER_ITEM_TAG = "drawer-app"
const val DRAWER_SEARCH_TAG = "drawer-search"
const val DRAWER_LIST_TAG = "drawer-list"

/** Opaque once on: icons showing through would be noise over icons. */
private val SURFACE = Color(0xFF0E0E10)

/** Arriving, it is translucent and a little larger than the screen, and settles onto it. */
private const val FROM_SCALE = 1.08f

/** Fully opaque this far in, well before it has settled. */
private const val OPAQUE_AT = 0.6f

/** Room between the edges of the screen and what is on the drawer. */
private val MARGIN = 16.dp

/** The search field matches the home screen's bar: the same height, glass, and quiet white. */
private val FIELD_HEIGHT = 48.dp
private val GLASS = 24.dp
private const val INK_ALPHA = 0.85f
private const val HINT_ALPHA = 0.5f

/**
 * Every launchable app, alphabetically, on an opaque surface, under a search field that narrows the
 * list as you type ([query]) and takes the keyboard as the drawer opens when [keyboard] says so.
 * Drawn [revealed] of the way up from the bottom edge, following the finger, translucent and
 * slightly larger while it arrives so it reads as settling onto the screen rather than sliding
 * across it. Closes on back, and pulling the list down past its top pulls the drawer down with it;
 * letting go decides ([onPullEnd]). Launching an app closes it.
 */
@Composable
fun AppDrawer(
    revealed: Float,
    open: Boolean,
    apps: List<AppEntry>,
    query: DrawerQuery,
    keyboard: Boolean,
    columns: Int,
    iconSize: Dp,
    labels: Boolean,
    insets: PaddingValues,
    onPull: (dy: Float) -> Unit,
    onPullEnd: (velocity: Float) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onClose: () -> Unit,
    drag: DragSession?,
) {
    BackHandler(enabled = open, onBack = onClose)
    // Stays composed while open even when pulled fully down, so the gesture that pulled it can
    // finish and decide; only a closed drawer with nothing showing is gone.
    if (!open && revealed <= 0f) return
    val pull = rememberUpdatedState(onPull)
    val pullEnd = rememberUpdatedState(onPullEnd)
    val shown = rememberUpdatedState(revealed)
    val pullToClose = remember {
        PullToClose(
            revealed = { shown.value },
            onPull = { pull.value(it) },
            onPullEnd = { pullEnd.value(it) },
        )
    }
    val shownApps = remember(apps, query.text) { apps.matching(query.text) }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val away = 1f - shown.value
                alpha = (shown.value / OPAQUE_AT).coerceAtMost(1f)
                scaleX = 1f + (FROM_SCALE - 1f) * away
                scaleY = scaleX
                translationY = away * size.height
            }
            .background(SURFACE)
            .testTag(DRAWER_TAG)
    ) {
        SearchField(
            query,
            keyboard,
            open,
            Modifier.padding(top = insets.calculateTopPadding() + MARGIN / 2)
                .padding(horizontal = MARGIN),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            // Insets pad the content, not the grid: its scrollable then covers the whole screen,
            // so a pull that starts at the edge still pulls. The keyboard, when up, takes the
            // bottom.
            contentPadding = insets.aboveKeyboard(),
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .testTag(DRAWER_LIST_TAG)
                    .nestedScroll(pullToClose),
        ) {
            items(shownApps, key = { it.key }) { app ->
                DrawerApp(
                    app,
                    iconSize,
                    labels,
                    onClick = { onLaunch(app) },
                    drag = drag?.handlersForDrawer(app),
                )
            }
        }
    }
}

/**
 * The list's padding: the margin inside the system bars at the sides, a little under the field, and
 * at the bottom the keyboard's height while it shows, else the bar's.
 */
@Composable
private fun PaddingValues.aboveKeyboard(): PaddingValues {
    val direction = LocalLayoutDirection.current
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    return PaddingValues(
        start = calculateStartPadding(direction) + MARGIN,
        top = MARGIN / 2,
        end = calculateEndPadding(direction) + MARGIN,
        bottom = maxOf(calculateBottomPadding(), keyboard) + MARGIN,
    )
}

/**
 * Narrows the list as you type; Enter launches the best match. Takes focus, and with it the
 * keyboard, as the drawer opens when [keyboard] is set; the field is still there to tap otherwise.
 */
@Composable
private fun SearchField(query: DrawerQuery, keyboard: Boolean, open: Boolean, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(open, keyboard) { if (open && keyboard) focus.requestFocus() }
    val ink = Color.White.copy(alpha = INK_ALPHA)
    val style = MaterialTheme.typography.titleMedium.copy(color = ink)
    Row(
        modifier = modifier.fillMaxWidth().height(FIELD_HEIGHT).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(GLASS),
        )
        BasicTextField(
            value = query.text,
            onValueChange = query.onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { query.onSubmit() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.text.isEmpty()) {
                        Text(
                            "Search apps",
                            style = style.copy(color = ink.copy(alpha = HINT_ALPHA)),
                        )
                    }
                    field()
                }
            },
            modifier =
                Modifier.padding(start = 12.dp)
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag(DRAWER_SEARCH_TAG),
        )
    }
}

@Composable
private fun DrawerApp(
    app: AppEntry,
    iconSize: Dp,
    labels: Boolean,
    onClick: () -> Unit,
    drag: DragHandlers?,
) {
    // The gesture coroutine outlives recompositions: both of these must always be current.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val handlers by rememberUpdatedState(drag)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .onGloballyPositioned { coords = it }
                .clickable(onClick = onClick)
                .dragAfterLongPress(app.key, { coords }, { handlers })
                .padding(vertical = 8.dp)
                .testTag(DRAWER_ITEM_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, Modifier.size(iconSize))
        if (labels) {
            Text(
                text = app.label,
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

/**
 * Scroll the list cannot use moves the drawer instead: downward when the list is at its top, and
 * upward again while the drawer is part way down. Letting go reports the velocity.
 */
private class PullToClose(
    private val revealed: () -> Float,
    private val onPull: (Float) -> Unit,
    private val onPullEnd: (Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (revealed() < 1f && available.y < 0f) {
            onPull(available.y)
            return available
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (available.y > 0f) onPull(available.y)
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        onPullEnd(available.y)
        return Velocity.Zero
    }
}
