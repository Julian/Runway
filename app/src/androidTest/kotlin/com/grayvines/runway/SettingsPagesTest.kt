package com.grayvines.runway

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.settings.BackupActions
import com.grayvines.runway.ui.settings.DebugActions
import com.grayvines.runway.ui.settings.SettingsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen's list of pages, and the ways into and out of each. */
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
    private fun Screen(debug: Boolean = true) {
        SettingsScreen(
            settings = Settings(),
            searchTargets = emptyList(),
            apps = emptyList(),
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
    }
}
