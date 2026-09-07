package com.grayvines.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Folders: made by dropping one icon onto another, and added to the same way. */
@RunWith(AndroidJUnit4::class)
class FolderTest : LauncherFixture() {
    @Test
    fun droppingAnIconOntoAnotherMakesAFolderOfTheTwo() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        assertEquals(listOf(neighbour, firstHomeApp), folderAt(1, 0))
        assertNull("the dropped icon's own placement is gone", placementOf(firstHomeApp))
        compose.onNodeWithContentDescription("Folder", useUnmergedTree = true).assertIsDisplayed()
        assertStillOnLauncher()
    }

    @Test
    fun droppingAnIconOntoAFolderAddsItToTheFolder() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        val third = labelAtHomeCell(2, 0)
        drag(from = third, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0)?.size == 3 }
        assertEquals(third, folderAt(1, 0)?.last())
        assertNull(placementOf(third))
    }

    @Test
    fun droppingAHomeIconOntoADockIconMakesAFolderInTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.dockSlot(0))
        compose.waitUntil(TIMEOUT_MS) { dockFolderAt(0) != null }
        assertEquals(listOf(firstDockApp, firstHomeApp), dockFolderAt(0))
        assertNull(placementOf(firstHomeApp))
    }
}
