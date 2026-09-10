package com.grayvines.runway.ui.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import com.grayvines.runway.ui.drag.Bounds
import kotlinx.coroutines.flow.Flow

const val HOME_MENU_TAG = "home-menu"

/** What a long press on empty home space can do. */
class HomeMenuActions(
    val wallpaper: () -> Unit,
    val widgets: () -> Unit,
    val addPage: () -> Unit,
    val settings: () -> Unit,
)

/** The home menu as the screen sees it: where it is open (if at all), and how to drive it. */
class HomeMenuSession(
    val at: Bounds?,
    val actions: HomeMenuActions,
    /** Opens the menu beside [Bounds]: a pressed point, or the search bar's three dots. */
    val onOpen: (Bounds) -> Unit,
    val onDismiss: () -> Unit,
    /** A page to scroll the home pager to, once it exists: where "Add page" put the new one. */
    val showPage: Flow<Int>,
)

/** The home menu, beside [at]: a long press on empty space, or the search bar's three dots. */
@Composable
fun HomeMenu(at: Bounds, actions: HomeMenuActions, onDismiss: () -> Unit) {
    Menu(at, HOME_MENU_TAG, onDismiss) {
        MenuRow("Wallpaper", Icons.Outlined.Wallpaper, actions.wallpaper)
        MenuRow("Widgets", Icons.Outlined.Widgets, actions.widgets)
        MenuRow("Add page", Icons.Outlined.AddBox, actions.addPage)
        MenuRow("Settings", Icons.Outlined.Settings, actions.settings)
    }
}
