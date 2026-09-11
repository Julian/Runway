package com.grayvines.runway.ui.widgets

import android.content.ComponentName
import android.os.UserHandle

/**
 * The system's part in adding a widget, which only an activity can ask for: the one-time leave to
 * bind widgets at all, and a widget's own setup screen. Each answers once the user has.
 */
interface WidgetPrompts {
    /**
     * Asks the user to let Runway bind widgets, with [id] and [provider] as the first; true once
     * they have, after which [id] is bound.
     */
    suspend fun requestBind(id: Int, provider: ComponentName, profile: UserHandle): Boolean

    /**
     * Runs the setup screen of the widget bound to [id]; true if it finished rather than cancelled.
     */
    suspend fun configure(id: Int): Boolean
}
