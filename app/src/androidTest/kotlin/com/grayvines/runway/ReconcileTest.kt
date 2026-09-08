package com.grayvines.runway

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.autoFill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Placements whose app is gone are cleaned up whenever the app list is rebuilt. */
@RunWith(AndroidJUnit4::class)
class ReconcileTest : LauncherFixture() {
    @Test
    fun aPlacementForAnAppThatIsNoLongerInstalledGoesOnTheNextAppListRefresh() {
        // Stage what an uninstall while the launcher was down leaves behind: a row with no app.
        val ghost = AppRef("com.example.gone/.Main", 0)
        runBlocking {
            val real = graph.appRepository.apps.first { it.isNotEmpty() }.map { it.ref }
            graph.workspace.autoFill(
                listOf(ghost) + real,
                settings.columns,
                settings.pageRows,
                settings.dockSlots,
            )
        }
        waitUntil(TIMEOUT_MS) { placed(ghost) }
        graph.appRepository.refresh()
        waitUntil(TIMEOUT_MS) { !placed(ghost) }
    }

    private fun placed(ref: AppRef) = runBlocking {
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .any { it.component == ref.component && it.profile == ref.profile }
    }
}
