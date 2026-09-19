package com.grayvines.runway

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.settings.BackupActions
import com.grayvines.runway.ui.settings.DebugActions
import com.grayvines.runway.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen's pages, the ways into and out of each, and the hidden apps page. */
@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachPageOpensFromTheList_andTheArrowLeadsBack() {
        compose.setContent { Screen() }
        PAGES.forEach { (title, control) ->
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(control).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText(control).assertDoesNotExist()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun backLeadsFromAPageToTheList_andFromTheListOutOfSettings() {
        compose.setContent { Screen() }
        val back = compose.activity.onBackPressedDispatcher
        // Nothing takes Back on the list, so it finishes the activity.
        compose.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
        compose.onNodeWithText("Drawer").performClick()
        compose.runOnIdle { assertTrue(back.hasEnabledCallbacks()) }

        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithText("Swipe sensitivity").assertDoesNotExist()
        compose.onNodeWithText("Home screen").assertIsDisplayed()
        compose.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }

    @Test
    fun hiddenAppsOpensFromTheDrawerPage_andBackLeadsBackThere() {
        compose.setContent { Screen() }
        compose.onNodeWithText("Hidden apps").assertDoesNotExist() // not on the list itself
        compose.onNodeWithText("Drawer").performClick()
        compose.onNodeWithText("Hidden apps").performClick()
        compose.onNodeWithText(ALPHA.label).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Swipe sensitivity").assertIsDisplayed()
        compose.onNodeWithText("Hidden apps").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Swipe sensitivity").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Home screen").assertIsDisplayed()
    }

    @Test
    fun theDrawerPageSaysHowManyAppsAreHidden() {
        var hidden by mutableStateOf(emptySet<AppRef>())
        compose.setContent { Screen(hidden = hidden) }
        compose.onNodeWithText("Drawer").performClick()
        compose.onNodeWithText("None").assertIsDisplayed()
        hidden = setOf(ALPHA.ref)
        compose.onNodeWithText("1 app").assertIsDisplayed()
        // One not installed (a paused profile's, say) is not on the page to count.
        hidden = setOf(ALPHA.ref, BETA.ref, AppRef("com.example/.Gone", 0))
        compose.onNodeWithText("2 apps").assertIsDisplayed()
    }

    @Test
    fun eachAppsSwitchSaysWhetherItIsHidden_andATapAsksForTheOpposite() {
        val asked = mutableListOf<Pair<AppRef, Boolean>>()
        compose.setContent {
            Screen(hidden = setOf(BETA.ref), onHide = { app, hide -> asked += app to hide })
        }
        compose.onNodeWithText("Drawer").performClick()
        compose.onNodeWithText("Hidden apps").performClick()
        compose.onNodeWithText(ALPHA.label).assertIsOff()
        compose.onNodeWithText(BETA.label).assertIsOn()

        compose.onNodeWithText(ALPHA.label).performClick()
        compose.onNodeWithText(BETA.label).performClick()
        assertEquals(listOf(ALPHA.ref to true, BETA.ref to false), asked)
    }

    @Test
    fun theDebugPageIsListedOnlyInADebugBuild() {
        compose.setContent { Screen(debug = false) }
        compose.onNodeWithText("Backup").assertIsDisplayed()
        compose.onNodeWithText("Debug").assertDoesNotExist()
    }

    @Test
    fun anOpenPageStaysOpenWhenTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen() }
        compose.onNodeWithText("Backup").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save a backup").assertIsDisplayed()
    }

    @Composable
    private fun Screen(
        debug: Boolean = true,
        hidden: Set<AppRef> = emptySet(),
        onHide: (AppRef, Boolean) -> Unit = { _, _ -> },
    ) {
        SettingsScreen(
            settings = Settings(),
            searchTargets = emptyList(),
            apps = listOf(ALPHA, BETA),
            hiddenApps = hidden,
            onHide = onHide,
            onChange = {},
            onOpenHome = null,
            backupActions = BackupActions(save = {}, restore = {}),
            debugActions = DebugActions(fillWithAllApps = {}, clearLayout = {}).takeIf { debug },
        )
    }

    private companion object {
        /** Each page's title in the list, and a control only that page shows. */
        val PAGES =
            listOf(
                "Home screen" to "Dock slots",
                "Drawer" to "Swipe sensitivity",
                "Search bar" to "Search with",
                "Gestures" to "On the first page",
                "Backup" to "Save a backup",
                "Debug" to "Fill with all apps",
            )

        val ALPHA = app("Alpha")
        val BETA = app("Beta")

        fun app(label: String) =
            AppEntry(
                ComponentName("com.example", "com.example.$label"),
                Process.myUserHandle(),
                profileSerial = 0,
                label = label,
                icon = ColorDrawable(),
                bitmap = ImageBitmap(1, 1),
            )
    }
}
