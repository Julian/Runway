package com.grayvines.runway

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The app list follows the device configuration it was built under. */
@RunWith(AndroidJUnit4::class)
class ConfigurationTest : LauncherFixture() {
    @Test
    fun aDensityChangeRasterisesTheIconsAgainForIt() {
        // A change the activity absorbs in place (it is in its configChanges), so nothing but the
        // list itself would notice.
        waitUntil(TIMEOUT_MS) { iconWidth() > 0 }
        val before = iconWidth()
        val densities = device.executeShellCommand("wm density")
        val current =
            checkNotNull(densities.density("Override") ?: densities.density("Physical")) {
                "no density in: $densities"
            }
        device.executeShellCommand("wm density ${(current * SMALLER).toInt()}")
        try {
            waitUntil(LONG_TIMEOUT_MS) { iconWidth() < before }
        } finally {
            // The next test starts from the device as it was, icons included.
            device.executeShellCommand("wm density ${densities.density("Override") ?: "reset"}")
            waitUntil(LONG_TIMEOUT_MS) { iconWidth() == before }
        }
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
