package com.grayvines.runway

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The app list follows the device configuration it was built under. */
@RunWith(AndroidJUnit4::class)
class ConfigurationTest : LauncherFixture() {
    /** The density to put back, once this test has changed it. */
    private var restore: String? = null
    private var before = 0

    @Test
    fun aDensityChangeRasterisesTheIconsAgainForIt() {
        // A change the activity absorbs in place (it is in its configChanges), so nothing but the
        // list itself would notice.
        waitUntil(TIMEOUT_MS) { iconWidth() > 0 }
        before = iconWidth()
        val densities = device.executeShellCommand("wm density")
        val current =
            checkNotNull(densities.density("Override") ?: densities.density("Physical")) {
                "no density in: $densities"
            }
        restore = densities.density("Override")?.toString() ?: "reset"
        device.executeShellCommand("wm density ${(current * SMALLER).toInt()}")
        waitUntil(LONG_TIMEOUT_MS) { iconWidth() < before }
    }

    /**
     * The next test starts from the device as it was, icons included, whichever way this one ended;
     * a restore that is slow to show is not this test's failure.
     */
    @After
    fun restoreDensity() {
        val density = restore ?: return
        device.executeShellCommand("wm density $density")
        runCatching { waitUntil(LONG_TIMEOUT_MS) { iconWidth() == before } }
    }

    private fun iconWidth() = graph.appRepository.apps.value.firstOrNull()?.bitmap?.width ?: 0

    /** "Physical density: 480", then "Override density: 379" when one is set. */
    private fun String.density(which: String) =
        lineSequence()
            .firstOrNull { it.startsWith("$which density") }
            ?.substringAfter(": ")
            ?.trim()
            ?.toInt()

    private companion object {
        /** Enough of a change for a smaller icon at any dpi, not so much the screen is unusable. */
        const val SMALLER = 0.6f
    }
}
