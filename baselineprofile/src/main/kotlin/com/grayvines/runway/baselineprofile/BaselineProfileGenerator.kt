package com.grayvines.runway.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journey whose code paths the baseline profile records: open the launcher as the home screen,
 * open the drawer, scroll it, close it, and lift an icon. Startup is profiled too, so the first
 * frames come from compiled code.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            // Our launcher activity answers HOME, not LAUNCHER (that is the settings screen).
            startActivityAndWait(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(PACKAGE)
            )
            device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_MS)

            // Up from the middle: the drawer opens. Then scroll it down and back, and close it.
            device.swipe(MIDDLE, MIDDLE, MIDDLE, NEAR_TOP)
            device.swipe(MIDDLE, LOWER, MIDDLE, UPPER)
            device.swipe(MIDDLE, UPPER, MIDDLE, LOWER)
            device.pressBack()
            device.waitForIdle()

            // Lift an icon (a long, still swipe) and carry it a little way.
            device.swipe(LEFT, UPPER, LEFT, UPPER, HOLD_STEPS)
            device.swipe(LEFT, UPPER, MIDDLE, MIDDLE)
            device.waitForIdle()
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

    private companion object {
        const val PACKAGE = "com.grayvines.runway"
        const val TIMEOUT_MS = 10_000L
        const val STEPS = 20
        const val HOLD_STEPS = 60 // long enough to read as a long press
        const val MIDDLE = 0.5f
        const val NEAR_TOP = 0.15f
        const val UPPER = 0.25f
        const val LOWER = 0.75f
        const val LEFT = 0.25f
    }
}
