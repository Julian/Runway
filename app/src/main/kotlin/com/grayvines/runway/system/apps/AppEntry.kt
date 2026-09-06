package com.grayvines.runway.system.apps

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.grayvines.runway.data.AppRef

/** One launchable activity in one user profile. */
class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val profileSerial: Long,
    val label: String,
    val icon: Drawable,
) {
    /** How the app is referred to in the database. */
    val ref: AppRef
        get() = AppRef(component.flattenToString(), profileSerial)

    /** Stable identity across processes. */
    val key: String
        get() = "$profileSerial/${component.flattenToString()}"
}
