package com.grayvines.runway.baselineprofile

import android.content.Intent
import android.graphics.Point
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.SearchCondition
import androidx.test.uiautomator.Searchable
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journey whose code paths the baseline profile records: open the launcher as the home screen,
 * open the drawer, dismiss its auto-raised keyboard, scroll it, close it, drag a real app out of
 * the drawer onto the home screen, and drag it to another cell. Startup is profiled too, so the
 * first frames come from compiled code.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            // Clear state between macrobenchmark iterations so each run starts with an empty home
            // screen and never collides with the icon placed by an earlier iteration.
            device.executeShellCommand("pm clear $PACKAGE")
            // Our launcher activity answers HOME, not LAUNCHER (that is the settings screen).
            startActivityAndWait(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(PACKAGE)
            )
            assertTrue(
                "Runway did not come up",
                device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_MS),
            )

            // Open the drawer, dismiss the auto-shown keyboard so swipes hit the app list rather
            // than the IME, scroll down and back, then close the drawer cleanly.
            device.openDrawerWithoutKeyboard()
            device.swipe(MIDDLE, LOWER, MIDDLE, UPPER)
            device.swipe(MIDDLE, UPPER, MIDDLE, LOWER)
            device.closeDrawer()

            // Fresh installs start with an empty home screen: open the drawer, find a real app tile
            // below the search bar, hold-drag it onto home (closing the drawer under the drag), and
            // then lift and move that placed icon across the home grid.
            device.openDrawerWithoutKeyboard()
            val (label, start) = device.firstDrawerApp()
            val homeCenter = device.point(MIDDLE, MIDDLE)
            device.holdAndDrag(start, homeCenter)
            assertTrue(
                "Drawer did not close under drag of '$label' from $start to $homeCenter",
                device.wait(Until.gone(By.text(DRAWER_SEARCH_HINT)), TIMEOUT_MS),
            )
            val placed = device.wait(Until.findObject(By.desc(label)), TIMEOUT_MS)
            assertNotNull("Dragged app '$label' did not land on home", placed)
            val placedCenter = placed!!.visibleCenter
            device.holdAndDrag(placedCenter, device.point(RIGHT, UPPER))
        }

    private fun UiDevice.openDrawerWithoutKeyboard() {
        swipe(MIDDLE, MIDDLE, MIDDLE, NEAR_TOP)
        assertTrue(
            "Drawer did not open",
            wait(Until.hasObject(By.text(DRAWER_SEARCH_HINT)), TIMEOUT_MS),
        )
        // Drawer defaults to opening the keyboard; one Back dismisses the IME while keeping the
        // drawer open (if no keyboard came up, reopen so the drawer is guaranteed open).
        pressBack()
        waitForIdle()
        if (!hasObject(By.text(DRAWER_SEARCH_HINT))) {
            swipe(MIDDLE, MIDDLE, MIDDLE, NEAR_TOP)
            assertTrue(
                "Drawer did not reopen",
                wait(Until.hasObject(By.text(DRAWER_SEARCH_HINT)), TIMEOUT_MS),
            )
        }
    }

    private fun UiDevice.closeDrawer() {
        repeat(MAX_BACKS_TO_CLOSE) {
            if (!hasObject(By.text(DRAWER_SEARCH_HINT))) return
            pressBack()
            waitForIdle()
        }
        assertTrue(
            "Drawer did not close",
            wait(Until.gone(By.text(DRAWER_SEARCH_HINT)), TIMEOUT_MS),
        )
    }

    private fun UiDevice.firstDrawerApp(): Pair<String, Point> {
        val found =
            wait(
                object : SearchCondition<Pair<String, Point>?>() {
                    override fun apply(searchable: Searchable): Pair<String, Point>? {
                        val list = searchable.findObject(By.res(DRAWER_LIST_TAG)) ?: return null
                        val node =
                            list.findObjects(By.pkg(PACKAGE)).firstOrNull { candidate ->
                                val desc = candidate.contentDescription
                                val bounds = candidate.visibleBounds
                                !desc.isNullOrBlank() && bounds.width() > 0 && bounds.height() > 0
                            } ?: return null
                        return node.contentDescription to node.visibleCenter
                    }
                },
                TIMEOUT_MS,
            )
        if (found == null) {
            val summary =
                findObjects(By.pkg(PACKAGE)).take(20).joinToString("; ") { n ->
                    "cls=${n.className} res=${n.resourceName} click=${n.isClickable} text=${n.text} desc=${n.contentDescription} bounds=${n.visibleBounds}"
                }
            assertNotNull("No app icon found in drawer list (nodes: $summary)", found)
        }
        return found!!
    }

    /** A swipe between points given as shares of the screen's width and height. */
    private fun UiDevice.swipe(x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = STEPS) {
        swipe(
            (displayWidth * x1).toInt(),
            (displayHeight * y1).toInt(),
            (displayWidth * x2).toInt(),
            (displayHeight * y2).toInt(),
            steps,
        )
        waitForIdle()
    }

    private fun UiDevice.point(x: Float, y: Float) =
        Point((displayWidth * x).toInt(), (displayHeight * y).toInt())

    /**
     * Holds stationary past Runway's 550 ms lift threshold, then drags smoothly to [end] in a
     * single pointer gesture (no ACTION_UP between hold and move).
     */
    private fun UiDevice.holdAndDrag(start: Point, end: Point) {
        swipe(arrayOf(start, start, start, end), DRAG_SEGMENT_STEPS)
        waitForIdle()
    }

    private companion object {
        const val PACKAGE = "com.grayvines.runway"
        const val DRAWER_SEARCH_HINT = "Search apps"
        const val DRAWER_LIST_TAG = "drawer-list"
        const val TIMEOUT_MS = 10_000L
        const val MAX_BACKS_TO_CLOSE = 2
        const val STEPS = 20
        // Two stationary segments (~750 ms, well above the 550 ms lift hold) + one move (~375 ms).
        const val DRAG_SEGMENT_STEPS = 75
        const val MIDDLE = 0.5f
        const val NEAR_TOP = 0.15f
        const val UPPER = 0.25f
        const val LOWER = 0.75f
        const val RIGHT = 0.75f
    }
}
