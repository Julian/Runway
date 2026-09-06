package com.grayvines.runway.system.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/** The app the search bar hands off to. */
class SearchTarget(val packageName: String, val label: String, val icon: Drawable)

/**
 * Picks the web-search handler (the configured package, else Firefox, else the first one) and hands
 * a search off to it. The launcher never talks to a search engine itself.
 */
class SearchTargetResolver(private val context: Context) {
    /**
     * Opens [target]'s search. If it no longer handles the intent (updated or uninstalled since it
     * was resolved), opens the app instead; with no target at all, nothing happens.
     */
    fun search(target: SearchTarget?) {
        val packageName = target?.packageName ?: return
        val search = Intent(Intent.ACTION_WEB_SEARCH).setPackage(packageName)
        try {
            context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            openInstead(packageName, e)
        } catch (e: SecurityException) {
            openInstead(packageName, e)
        }
    }

    /** The target no longer handles the search, or refuses it (not exported after all). */
    private fun openInstead(packageName: String, why: Exception) {
        Log.w(TAG, "$packageName would not take the search; opening it instead", why)
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun resolve(preferredPackage: String?): SearchTarget? {
        val pm = context.packageManager
        // Only activities other apps may start: a browser's private search entry point shows up
        // in the query but refuses us.
        val handlers =
            pm.queryIntentActivities(
                    Intent(Intent.ACTION_WEB_SEARCH),
                    PackageManager.ResolveInfoFlags.of(0),
                )
                .filter { it.activityInfo.exported }
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
        const val TAG = "Runway"
    }
}
