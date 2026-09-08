package com.grayvines.runway.system.wallpaper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/** Hands wallpaper choosing to the system: its picker knows every source, we know none. */
class WallpaperPicker(private val context: Context) {
    /** Opens the system wallpaper picker; false if this device has none. */
    fun pick(): Boolean =
        try {
            context.startActivity(
                Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no wallpaper picker on this device", e)
            false
        }

    private companion object {
        const val TAG = "Runway"
    }
}
