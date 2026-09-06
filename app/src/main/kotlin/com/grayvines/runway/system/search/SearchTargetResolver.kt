package com.grayvines.runway.system.search

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** The app the search bar hands off to. */
class SearchTarget(val packageName: String, val label: String, val icon: Drawable)

/** Picks the web-search handler: the configured package, else Firefox, else the first one. */
class SearchTargetResolver(private val context: Context) {
    fun resolve(preferredPackage: String?): SearchTarget? {
        val pm = context.packageManager
        val handlers =
            pm.queryIntentActivities(
                Intent(Intent.ACTION_WEB_SEARCH),
                PackageManager.ResolveInfoFlags.of(0),
            )
        val chosen =
            handlers.firstOrNull { it.activityInfo.packageName == preferredPackage }
                ?: handlers.firstOrNull { it.activityInfo.packageName == FIREFOX }
                ?: handlers.firstOrNull()
        return chosen?.let {
            SearchTarget(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm))
        }
    }

    private companion object {
        const val FIREFOX = "org.mozilla.firefox"
    }
}
