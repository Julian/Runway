package com.grayvines.runway

import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real launcher on the device. Seeds the layout with every installed app, so it replaces
 * whatever layout the debug build had.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFlowTest {
    @get:Rule val compose = createAndroidComposeRule<LauncherActivity>()

    private val app = ApplicationProvider.getApplicationContext<RunwayApp>()
    private val graph
        get() = app.graph

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Small enough that the fill spans several pages. */
    private val settings = Settings(columns = 4, rows = 5, dockSlots = 3)

    private lateinit var firstDockApp: String
    private lateinit var firstHomeApp: String

    @Before
    fun seed() {
        runBlocking {
            graph.settings.update { settings }
            val all = graph.appRepository.apps.first { it.isNotEmpty() }
            // Our own settings app goes in the first home cell so tests can tap it on page 1.
            val ours = all.first { it.component.packageName == app.packageName }
            val apps =
                (all - ours).take(settings.dockSlots) + ours + (all - ours).drop(settings.dockSlots)
            firstDockApp = apps[0].label
            firstHomeApp = ours.label
            graph.workspace.autoFill(
                apps.map { it.ref },
                settings.columns,
                settings.pageRows,
                settings.dockSlots,
            )
        }
        // The layout arrives through Room flows, which Compose's idling does not track.
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun tappingTheSettingsIconOpensSettings() {
        icon(firstHomeApp).performClick()
        assertTrue(
            "settings screen did not appear",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
    }

    @Test
    fun columnsSettingRelaysOutTheGridLive() {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking { graph.settings.update { it.copy(columns = it.columns + 2) } }
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
    }

    @Test
    fun homeIntentReturnsToTheFirstPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        // Addressed explicitly: the test install resets the device's default home app.
        app.startActivity(
            Intent(Intent.ACTION_MAIN, null, app, LauncherActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun searchBarShowsTheHandoffTarget() {
        val target = graph.searchTargets.resolve(null)
        // Bare CI images may have no browser at all; that is not a launcher bug.
        assumeTrue("no web-search handler installed", target != null)
        compose
            .onNodeWithTag(SEARCH_TARGET_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(target!!.label)
    }

    @Test
    fun iconsShareOneSizeAcrossGridAndDock() {
        val grid = icon(firstHomeApp).fetchSemanticsNode().size
        val dock = icon(firstDockApp).fetchSemanticsNode().size
        assertEquals(grid, dock)
        assertTrue(grid.width > 0)
    }

    /** Icons live inside clickable cells, whose semantics merge; look at the unmerged tree. */
    private fun icon(label: String) =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.isDisplayedOrFalse() = runCatching {
        assertIsDisplayed()
        true
    }
        .getOrDefault(false)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
