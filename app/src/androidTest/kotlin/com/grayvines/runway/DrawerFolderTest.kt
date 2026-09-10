package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.observeDrawerPlacements
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.renameFolder
import com.grayvines.runway.ui.drawer.DRAWER_FOLDER_TAG
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_SEARCH_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.folder.FOLDER_ITEM_TAG
import com.grayvines.runway.ui.folder.FOLDER_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Folders that live in the drawer: made from an app's menu, shown above the grid, deletable. */
@RunWith(AndroidJUnit4::class)
class DrawerFolderTest : LauncherFixture() {
    private val first: String
        get() = labels[0]

    private val second: String
        get() = labels[1]

    @Test
    fun newFolderFromAnAppsMenuPutsAFolderAboveTheGridWithTheAppInsideIt() {
        openDrawer()
        hold(drawerApp(first))
        release()
        menuRow("New folder").performClick()
        waitUntil { drawerFolderTiles().size == 1 }
        assertEquals(listOf(listOf(first)), drawerFolders())
        // Out of the grid, above it: the tile comes before every app.
        val tile = compose.onNodeWithTag(DRAWER_FOLDER_TAG).fetchSemanticsNode().boundsInRoot
        val apps = compose.onAllNodesWithTag(DRAWER_ITEM_TAG).fetchSemanticsNodes()
        assertTrue(
            apps.none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(first) == true
            }
        )
        assertTrue(apps.all { it.boundsInRoot.top >= tile.top })
    }

    @Test
    fun anAppInADrawerFolderIsStillFoundBySearching() {
        makeDrawerFolder(first)
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).performTextInput(first)
        waitUntil { drawerApp(first).isDisplayedOrFalse() }
        assertTrue(drawerFolderTiles().isEmpty()) // the section steps aside while searching
    }

    @Test
    fun tappingADrawerFolderOpensItOverTheDrawer() {
        makeDrawerFolder(first)
        tap(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose
            .onNode(hasTestTag(FOLDER_ITEM_TAG) and hasContentDescription(first))
            .assertIsDisplayed()
        compose.onNodeWithTag(DRAWER_TAG).assertExists() // still there behind the sheet
    }

    @Test
    fun openingADrawerFolderTakesTheKeyboardOffTheSearchField() {
        makeDrawerFolder(first)
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).assertIsFocused() // the drawer opened with it
        tap(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).assertIsNotFocused()
        // Keystrokes now go nowhere near the hidden field.
        device.executeShellCommand("input text xyz")
        compose.waitForIdle()
        val typed =
            compose
                .onNodeWithTag(DRAWER_SEARCH_TAG)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
        assertEquals("", typed)
        // And with no keyboard up, back closes the sheet itself.
        device.pressBack()
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(DRAWER_TAG).assertExists()
    }

    @Test
    fun addToFolderMovesAnAppIntoIt_andAnAppIsInOneDrawerFolderAtMost() {
        makeDrawerFolder(first)
        hold(drawerApp(second))
        release()
        menuRow("Add to Folder").performClick()
        awaitFolders(listOf(listOf(first, second)))
        tap(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose
            .onNode(hasTestTag(FOLDER_ITEM_TAG) and hasContentDescription(second))
            .assertIsDisplayed()
        // HOME closes the sheet and leaves the drawer; Back would first take the keyboard down.
        sendHomeIntent()
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty() }

        // The app is out of the grid now, so search is the way to its menu; a second folder for
        // it takes it out of the first.
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).performTextInput(second)
        waitUntil { drawerApp(second).isDisplayedOrFalse() }
        hold(drawerApp(second))
        release()
        menuRow("New folder").performClick()
        awaitFolders(listOf(listOf(first), listOf(second)))
    }

    @Test
    fun deletingADrawerFolderPutsItsAppsBackInTheGrid() {
        makeDrawerFolder(first)
        hold(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        release()
        menuRow("Delete folder").performClick()
        waitUntil { drawerFolderTiles().isEmpty() }
        waitUntil { drawerApp(first).isDisplayedOrFalse() }
        assertTrue(runBlocking { graph.workspace.observeFolders().first() }.isEmpty())
    }

    /**
     * Waits for the drawer folders to hold [expected], and says what they hold if they never do.
     */
    @Test
    fun draggingADrawerFolderOntoAPagePlacesItThereAndKeepsItInTheDrawer() {
        // A free cell first: the first page is full by default. (The grid is measured off the
        // first home app, so before it goes.)
        val grid = useGrid(settings.columns, settings.rows)
        runBlocking { graph.workspace.removeItem(placementOf(firstHomeApp)!!.id) }
        waitUntil { !icon(firstHomeApp).isDisplayedOrFalse() }
        makeDrawerFolder(first)
        val folderId = runBlocking {
            graph.workspace.observeDrawerPlacements().first().single().folderId
        }

        val tile = compose.onNodeWithTag(DRAWER_FOLDER_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { down(tile.center) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -LIFT_NUDGE_PX)) }
        waitUntil {
            compose
                .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        dragOn(to = grid.homeCell(0, 0))
        release()

        waitUntil { folderAt(0, 0) == listOf(first) }
        val placed = runBlocking {
            graph.workspace.observe(Container.HOME).first().pages.first().items.single {
                it.x == 0 && it.y == 0
            }
        }
        assertEquals(ItemKind.FOLDER to folderId, placed.kind to placed.folderId)
        assertEquals(listOf(listOf(first)), drawerFolders()) // still in the drawer too
        // One folder in two places: renamed here, it is renamed in the drawer.
        runBlocking { graph.workspace.renameFolder(folderId!!, "Tools") }
        waitUntil {
            compose
                .onAllNodesWithContentDescription("Tools", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun awaitFolders(expected: List<List<String>>) {
        runCatching { waitUntil { drawerFolders() == expected } }
        assertEquals(expected, drawerFolders())
    }

    /** Opens the drawer and makes a drawer folder of [label] through its menu. */
    private fun makeDrawerFolder(label: String) {
        openDrawer()
        hold(drawerApp(label))
        release()
        menuRow("New folder").performClick()
        waitUntil { drawerFolderTiles().size == 1 }
    }

    private fun drawerFolderTiles() =
        compose.onAllNodesWithTag(DRAWER_FOLDER_TAG).fetchSemanticsNodes()

    /** Each drawer folder's app labels, in the order the folders were made. */
    private fun drawerFolders(): List<List<String>> = runBlocking {
        val folders = graph.workspace.observeFolders().first().associateBy { it.id }
        val installed = graph.appRepository.apps.first()
        graph.workspace.observeDrawerPlacements().first().mapNotNull { placement ->
            folders[placement.folderId]?.apps?.map { ref ->
                installed.first { it.ref == ref }.label
            }
        }
    }
}
