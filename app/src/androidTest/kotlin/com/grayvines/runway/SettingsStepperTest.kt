package com.grayvines.runway

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.settings.BackupActions
import com.grayvines.runway.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen's steppers against a store that answers a write behind. */
@RunWith(AndroidJUnit4::class)
class SettingsStepperTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aStepIsAppliedToTheStoredValueNotTheOneOnScreen() {
        // The screen still shows the defaults (as it does until the store's first emission, and
        // between two quick taps); what is stored is something else.
        var stored = Settings(columns = 5)
        compose.setContent {
            SettingsScreen(
                settings = Settings(),
                searchTargets = emptyList(),
                onChange = { transform -> stored = transform(stored) },
                onOpenHome = null,
                backupActions = BackupActions(save = {}, restore = {}),
                debugActions = null,
            )
        }
        val plus = compose.onAllNodesWithText("+")[0] // the first stepper: the grid's columns
        plus.performClick()
        plus.performClick()
        assertEquals(7, stored.columns) // 5 + 1 + 1, not the screen's 7 + 1 twice over
        compose.onAllNodes(hasText("−"))[0].performClick()
        assertEquals(6, stored.columns)
    }
}
