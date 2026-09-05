package com.grayvines.runway.system.apps

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

/** One launchable activity in one user profile. */
class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val profileSerial: Long,
    val label: String,
    val icon: Drawable,
) {
    /** Stable identity across processes: profile serial + component. */
    val key: String
        get() = "$profileSerial/${component.flattenToShortString()}"
}
