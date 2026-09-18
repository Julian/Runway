package com.grayvines.runway

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.NEW_FOLDER_NAME
import com.grayvines.runway.data.addToDrawerFolder
import com.grayvines.runway.data.createDrawerFolder
import com.grayvines.runway.data.observeDrawerPlacements
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.placeFolder
import com.grayvines.runway.data.renameFolder
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drawer.DRAWER_FOLDER_TAG
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_SEARCH_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.folder.FOLDER_ADD_SEARCH_TAG
import com.grayvines.runway.ui.folder.FOLDER_ADD_TAG
import com.grayvines.runway.ui.folder.FOLDER_CANDIDATE_TAG
import com.grayvines.runway.ui.folder.FOLDER_EDIT_TAG
import com.grayvines.runway.ui.folder.FOLDER_ITEM_TAG
import com.grayvines.runway.ui.folder.FOLDER_REMOVE_TAG
import com.grayvines.runway.ui.folder.FOLDER_TAG
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
    fun anAppsMenuOffersANewFolderButNoFolderToJoin() {
        makeDrawerFolder(first)
        hold(drawerApp(second))
        release()
        menuRow("New folder").assertIsDisplayed()
        compose.onAllNodes(hasText("Add to", substring = true)).assertCountEquals(0)
        sendHomeIntent() // closes the menu
    }

    @Test
    fun newFolderForTheOnlyAppOfAnotherLeavesNoEmptyFolderBehind() {
        makeDrawerFolder(first)
        // Out of the grid, so search is the way to its menu.
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).performTextInput(first)
        waitUntil { drawerApp(first).isDisplayedOrFalse() }
        hold(drawerApp(first))
        release()
        menuRow("New folder").performClick()
        awaitFolders(listOf(listOf(first))) // one folder, the new one; not an empty one too
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
        // And with no keyboard up, back closes the sheet itself, and the field has focus again.
        pressBack()
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(DRAWER_TAG).assertExists()
        waitUntil { compose.onNodeWithTag(DRAWER_SEARCH_TAG).fetchSemanticsNode().isFocused() }
    }

    private fun SemanticsNode.isFocused() = config.getOrNull(SemanticsProperties.Focused) == true

    @Test
    fun anEditedDrawerFolderEndsInAPlusThatListsEveryAppNotInIt() {
        openDrawerFolderOf(first)
        compose.onAllNodesWithTag(FOLDER_ADD_TAG).assertCountEquals(0) // until the pencil
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(second).isDisplayedOrFalse() }
        compose
            .onAllNodes(hasTestTag(FOLDER_CANDIDATE_TAG) and hasContentDescription(first))
            .assertCountEquals(0)
        compose.onAllNodesWithTag(FOLDER_ITEM_TAG).assertCountEquals(0) // the list, not the folder
        compose.onNodeWithTag(FOLDER_ADD_SEARCH_TAG).assertIsDisplayed()
        sendHomeIntent()
    }

    @Test
    fun tappingAListedAppAddsItAtOnce_andTheListStaysForTheNext() {
        val third = labels[2]
        openDrawerFolderOf(first)
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(second).isDisplayedOrFalse() }

        tap(candidate(second))
        awaitFolders(listOf(listOf(first, second)))
        waitUntil { !candidate(second).isDisplayedOrFalse() }
        tap(candidate(third))
        awaitFolders(listOf(listOf(first, second, third)))

        // Done goes back to the folder, with both in it.
        tap(compose.onNode(hasText("Done") and hasAnyAncestor(hasTestTag(FOLDER_TAG))))
        waitUntil { compose.onAllNodesWithTag(FOLDER_ADD_TAG).fetchSemanticsNodes().isNotEmpty() }
        folderApp(second).assertIsDisplayed()
        folderApp(third).assertIsDisplayed()
        sendHomeIntent()
    }

    @Test
    fun aFolderListsItsAppsAsTheDrawerDoes() {
        // Added last, but first by label: the sheet shows it first all the same.
        openDrawerFolderOf(second)
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(first).isDisplayedOrFalse() }

        tap(candidate(first))
        awaitFolders(listOf(listOf(first, second)))
        tap(compose.onNode(hasText("Done") and hasAnyAncestor(hasTestTag(FOLDER_TAG))))
        waitUntil { folderApp(first).isDisplayedOrFalse() }

        assertEquals(listOf(first, second), shownFolderApps())
        sendHomeIntent()
    }

    @Test
    fun typingNarrowsTheList_andSaysSoWhenNothingMatches() {
        openDrawerFolderOf(first)
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        // One of the first few listed, and so on screen, that the query will not match.
        val unmatched =
            labels.subList(2, NEIGHBOURS).first {
                !LabelOrder.folded(it).contains(LabelOrder.folded(second))
            }
        waitUntil { candidate(unmatched).isDisplayedOrFalse() }

        compose.onNodeWithTag(FOLDER_ADD_SEARCH_TAG).performTextInput(second)
        waitUntil { !candidate(unmatched).isDisplayedOrFalse() }
        candidate(second).assertIsDisplayed()

        compose.onNodeWithTag(FOLDER_ADD_SEARCH_TAG).performTextInput("qqqzzz")
        waitUntil {
            compose.onAllNodes(hasText("No apps match")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag(FOLDER_CANDIDATE_TAG).assertCountEquals(0)
        sendHomeIntent()
    }

    @Test
    fun backLeavesTheList_thenTheEditing_thenClosesTheFolder() {
        openDrawerFolderOf(first)
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(second).isDisplayedOrFalse() }

        pressBack()
        waitUntil { compose.onAllNodesWithTag(FOLDER_ADD_TAG).fetchSemanticsNodes().isNotEmpty() }
        folderApp(first).assertIsDisplayed()
        compose.onAllNodesWithTag(FOLDER_CANDIDATE_TAG).assertCountEquals(0)

        pressBack() // out of the editing the plus stood in
        waitUntil { compose.onAllNodesWithTag(FOLDER_ADD_TAG).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(FOLDER_TAG).assertIsDisplayed()

        pressBack()
        waitUntil { compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(DRAWER_TAG).assertExists()
    }

    @Test
    fun addingAnAppFromAnotherDrawerFolderMovesIt_andTheFolderItLeftEmptyGoes() {
        makeDrawerFolder(first)
        val firstFolder = runBlocking {
            graph.workspace.observeDrawerPlacements().first().single().folderId!!
        }
        runBlocking { graph.workspace.renameFolder(firstFolder, FIRST_FOLDER) }
        hold(drawerApp(second))
        release()
        menuRow("New folder").performClick()
        awaitFolders(listOf(listOf(first), listOf(second)))

        val tile =
            compose.onNode(hasTestTag(DRAWER_FOLDER_TAG) and hasContentDescription(FIRST_FOLDER))
        waitUntil { tile.isDisplayedOrFalse() }
        tap(tile)
        waitUntil { compose.onAllNodesWithTag(FOLDER_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
        editFolder()
        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(second).isDisplayedOrFalse() }
        tap(candidate(second))
        awaitFolders(listOf(listOf(first, second)))
        sendHomeIntent()
    }

    @Test
    fun aDrawerFolderPlacedOnAPageOffersThePlusThereToo() {
        val home = placementOf(firstHomeApp)!!
        runBlocking {
            val folderId = graph.workspace.createDrawerFolder(apps.first().ref)
            graph.workspace.removeItem(home.id)
            graph.workspace.placeFolder(
                folderId,
                Container.HOME,
                home.pageIndex!!,
                home.x!!,
                home.y!!,
            )
        }
        waitUntil { folderAt(home.x!!, home.y!!) == listOf(first) }
        tap(compose.onNodeWithContentDescription(NEW_FOLDER_NAME, useUnmergedTree = true))
        waitUntil { compose.onAllNodesWithTag(FOLDER_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
        editFolder()
        compose.onNodeWithTag(FOLDER_ADD_TAG).assertIsDisplayed()
        sendHomeIntent()
    }

    @Test
    fun thePencilPutsAnXOnEachApp_andAnXPutsThatAppBackInTheGridAtOnce() {
        makeDrawerFolder(first)
        runBlocking {
            val folderId = graph.workspace.observeDrawerPlacements().first().single().folderId!!
            graph.workspace.addToDrawerFolder(folderId, apps[1].ref)
        }
        tap(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        waitUntil { folderApp(second).isDisplayedOrFalse() }
        compose.onAllNodesWithTag(FOLDER_REMOVE_TAG).assertCountEquals(0)

        tap(compose.onNodeWithTag(FOLDER_EDIT_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_REMOVE_TAG).fetchSemanticsNodes().size == 2 }
        tap(removeChip(first))
        awaitFolders(listOf(listOf(second)))
        waitUntil { !folderApp(first).isDisplayedOrFalse() }
        removeChip(second).assertIsDisplayed() // still editing what is left

        sendHomeIntent() // closes the sheet, not the drawer
        awaitGone(FOLDER_TAG)
        waitUntil { drawerApp(first).isDisplayedOrFalse() }
        awaitFolders(listOf(listOf(second)))
    }

    @Test
    fun anXOnTheLastAppLeavesTheFolderOpenWithItsPlus_andClosingItEmptyDeletesIt() {
        openDrawerFolderOf(first)
        tap(compose.onNodeWithTag(FOLDER_EDIT_TAG))
        waitUntil { removeChip(first).isDisplayedOrFalse() }
        tap(removeChip(first))
        awaitFolders(listOf(emptyList()))
        compose.onNodeWithTag(FOLDER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_ADD_TAG).assertIsDisplayed()
        tap(compose.onNodeWithTag(FOLDER_EDIT_TAG)) // the check; an empty folder keeps its plus
        compose.waitForIdle()
        compose.onNodeWithTag(FOLDER_ADD_TAG).assertIsDisplayed()

        sendHomeIntent()
        awaitGone(FOLDER_TAG)
        waitUntil { drawerFolderTiles().isEmpty() }
        waitUntil { runBlocking { graph.workspace.observeFolders().first() }.isEmpty() }
        waitUntil { drawerApp(first).isDisplayedOrFalse() }
    }

    @Test
    fun aFolderEmptiedByItsXsAndRefilledFromItsPlusStaysWhenClosed() {
        openDrawerFolderOf(first)
        tap(compose.onNodeWithTag(FOLDER_EDIT_TAG))
        waitUntil { removeChip(first).isDisplayedOrFalse() }
        tap(removeChip(first))
        awaitFolders(listOf(emptyList()))

        tap(compose.onNodeWithTag(FOLDER_ADD_TAG))
        waitUntil { candidate(second).isDisplayedOrFalse() }
        tap(candidate(second))
        awaitFolders(listOf(listOf(second)))
        tap(compose.onNode(hasText("Done") and hasAnyAncestor(hasTestTag(FOLDER_TAG))))
        waitUntil { folderApp(second).isDisplayedOrFalse() }

        sendHomeIntent()
        awaitGone(FOLDER_TAG)
        compose.waitForIdle()
        awaitFolders(listOf(listOf(second)))
        waitUntil { drawerFolderTiles().size == 1 }
    }

    @Test
    fun holdingAnAppInAFolderOffersAppInfoAndUninstall_butNoFolderRows() {
        openDrawerFolderOf(first)
        compose.waitForIdle() // the sheet has finished growing under the finger to come
        hold(folderApp(first))
        release()
        menuRow("App info").assertIsDisplayed()
        menuRow("Uninstall").assertIsDisplayed()
        menuRow("New folder").assertDoesNotExist()
        compose.onAllNodes(hasText("Remove", substring = true)).assertCountEquals(0)
        sendHomeIntent() // closes the menu
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

        lift(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
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

    /** Opens the drawer, makes a drawer folder of [label], and opens that folder's sheet. */
    private fun openDrawerFolderOf(label: String) {
        makeDrawerFolder(label)
        tap(compose.onNodeWithTag(DRAWER_FOLDER_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Taps the open folder's pencil, which is where its plus and its x's are. */
    private fun editFolder() {
        tap(compose.onNodeWithTag(FOLDER_EDIT_TAG))
        waitUntil { compose.onAllNodesWithTag(FOLDER_ADD_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun candidate(label: String) =
        compose.onNode(hasTestTag(FOLDER_CANDIDATE_TAG) and hasContentDescription(label))

    private fun removeChip(label: String) =
        compose.onNode(hasTestTag(FOLDER_REMOVE_TAG) and hasContentDescription("Remove $label"))

    private fun folderApp(label: String) =
        compose.onNode(hasTestTag(FOLDER_ITEM_TAG) and hasContentDescription(label))

    private fun drawerFolderTiles() =
        compose.onAllNodesWithTag(DRAWER_FOLDER_TAG).fetchSemanticsNodes()

    /**
     * Each drawer folder's app labels, as the folder lists them: in its hand order, or as the
     * drawer sorts them. One read: a write landing between a folders query and a placements query
     * showed placements of a folder the other query had not seen, and so nothing at all.
     */
    private fun drawerFolders(): List<List<String>> = runBlocking {
        val installed = graph.appRepository.apps.first()
        val workspace = graph.workspace
        workspace.read {
            val apps = workspace.dao.folderApps().groupBy { it.folderId }
            val folders = workspace.dao.folders().associateBy { it.id }
            workspace.dao
                .items()
                .filter { it.container == Container.DRAWER }
                .mapNotNull { placement ->
                    folders[placement.folderId]?.let { folder ->
                        val held = apps[folder.id].orEmpty()
                        val labels = held.map { row ->
                            installed.first { it.ref == AppRef(row.component, row.profile) }.label
                        }
                        val handSorted = held.any { it.position != null }
                        if (handSorted) labels else labels.sortedWith(LabelOrder.comparator())
                    }
                }
        }
    }

    private companion object {
        /** Far enough into the apps that one of them will not match a query for another. */
        const val NEIGHBOURS = 6
        const val FIRST_FOLDER = "Kept apart"
    }
}
