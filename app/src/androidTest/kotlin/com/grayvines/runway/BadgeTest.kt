package com.grayvines.runway

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.settings.BadgeStyle
import com.grayvines.runway.model.ActiveNotification
import com.grayvines.runway.ui.home.BADGE_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Notification badges on app icons and folders, their styles, and per-app muting. */
@RunWith(AndroidJUnit4::class)
class BadgeTest : LauncherFixture() {
    @Test
    fun badgesShowCountsCappingAt99Plus_andFollowDotAndOffStyles() {
        val home = apps.first { it.label == firstHomeApp }
        graph.badges.setNotificationsForTest(
            listOf(
                ActiveNotification(
                    packageName = home.component.packageName,
                    profile = home.ref.profile,
                    number = 4,
                )
            )
        )
        waitUntil { badgeCount() == 1 }
        badgeNode().assertTextEquals("4")

        graph.badges.setNotificationsForTest(
            listOf(
                ActiveNotification(
                    packageName = home.component.packageName,
                    profile = home.ref.profile,
                    number = 120,
                )
            )
        )
        waitUntil {
            compose
                .onAllNodes(hasTestTag(BADGE_TAG) and hasText("99+"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        runBlocking { graph.settings.update { it.copy(badgeStyle = BadgeStyle.DOT) } }
        waitUntil {
            badgeCount() == 1 &&
                compose
                    .onAllNodes(hasTestTag(BADGE_TAG) and hasText("99+"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }

        runBlocking { graph.settings.update { it.copy(badgeStyle = BadgeStyle.OFF) } }
        waitUntil { badgeCount() == 0 }
    }

    @Test
    fun folderBadgeAggregatesContainedApps_andMuteSuppressesEverywhere() {
        val grid = useGrid(columns = 5, rows = 7)
        val first = apps.first { it.label == firstHomeApp }
        val second = apps.first { it.label == labelAtHomeCell(1, 0) }
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }

        graph.badges.setNotificationsForTest(
            listOf(
                ActiveNotification(first.component.packageName, first.ref.profile, number = 2),
                ActiveNotification(second.component.packageName, second.ref.profile, number = 3),
            )
        )
        waitUntil {
            compose
                .onAllNodes(hasTestTag(BADGE_TAG) and hasText("5"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Mute the first app from the drawer's item menu: the folder's badge drops to 3.
        openDrawer()
        hold(drawerApp(first.label))
        release()
        menuRow("Mute badges").assertIsDisplayed().performClick()
        waitUntil {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isEmpty()
        }
        pressBack()
        awaitDrawerClosed()
        waitUntil {
            compose
                .onAllNodes(hasTestTag(BADGE_TAG) and hasText("3"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Unmute it again from the drawer's item menu: the count returns.
        openDrawer()
        hold(drawerApp(first.label))
        release()
        menuRow("Unmute badges").assertIsDisplayed().performClick()
        waitUntil {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isEmpty()
        }
        pressBack()
        awaitDrawerClosed()
        waitUntil {
            compose
                .onAllNodes(hasTestTag(BADGE_TAG) and hasText("5"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onAllNodesWithTag(BADGE_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun badgeNode() = compose.onNodeWithTag(BADGE_TAG, useUnmergedTree = true)

    private fun badgeCount() =
        compose
            .onAllNodes(
                hasTestTag(BADGE_TAG) and !hasAnyAncestor(hasTestTag(ITEM_MENU_TAG)),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .size
}
