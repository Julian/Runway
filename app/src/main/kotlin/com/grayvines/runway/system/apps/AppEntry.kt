package com.grayvines.runway.system.apps

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import com.grayvines.runway.data.AppRef

/**
 * One launchable activity in one user profile. Two entries are the same app when they name the same
 * activity in the same profile with the same label, whatever icon object each carries: a fresh list
 * after a package change must not read as a change to every app in it.
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
    /** How the app is referred to in the database. */
    val ref: AppRef
        get() = AppRef(component.flattenToString(), profileSerial)

    /** Stable identity across processes. */
    val key: String = "$profileSerial/${component.flattenToString()}"

    override fun equals(other: Any?) = other is AppEntry && other.key == key && other.label == label

    override fun hashCode() = key.hashCode() * 31 + label.hashCode()

    override fun toString() = "AppEntry($label, $key)"
}
