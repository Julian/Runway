package com.grayvines.runway.system.apps

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.grayvines.runway.data.AppRef

/**
 * One launchable activity in one user profile. Two entries are the same app when they name the same
 * activity in the same profile with the same label and an icon of the same pixels, whatever icon
 * object each carries: a fresh list after a package change must not read as a change to every app
 * in it, while an app whose icon was redrawn, or a list rasterised again for a new density, must.
 */
class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val profileSerial: Long,
    val label: String,
    val icon: Drawable,
    /** The icon as drawn everywhere, rasterised once as the list loads, off the main thread. */
    val bitmap: ImageBitmap,
) {
    /** How the app is referred to everywhere: component plus profile serial. */
    val ref: AppRef = AppRef(component.flattenToString(), profileSerial)

    override fun equals(other: Any?) =
        other is AppEntry &&
            other.ref == ref &&
            other.label == label &&
            (other.bitmap === bitmap ||
                other.bitmap.asAndroidBitmap().sameAs(bitmap.asAndroidBitmap()))

    override fun hashCode() = ref.hashCode() * 31 + label.hashCode()

    override fun toString() = "AppEntry($label, $ref)"
}
