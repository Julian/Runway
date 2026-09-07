package com.grayvines.runway.system.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/** The app the search bar hands off to; the same app is the same target, whatever icon object. */
class SearchTarget(val packageName: String, val label: String, val icon: Drawable) {
    override fun equals(other: Any?) =
        other is SearchTarget && other.packageName == packageName && other.label == label

    override fun hashCode() = packageName.hashCode() * 31 + label.hashCode()
}

/**
 * Picks the web-search handler (the configured package, else Firefox, else the first one) and hands
 * a search off to it. The launcher never talks to a search engine itself.
 */
class SearchTargetResolver(private val context: Context) {
    /** Every app that can take a web search, as the settings picker lists them. */
    fun handlers(): List<SearchTarget> {
        val pm = context.packageManager
        // Only activities other apps may start: a browser's private search entry point shows up
        // in the query but refuses us.
        return pm.queryIntentActivities(
                Intent(Intent.ACTION_WEB_SEARCH),
                PackageManager.ResolveInfoFlags.of(0),
            )
            .filter { it.activityInfo.exported }
            .map {
                // The app's own name and icon: an activity may call itself just "Search".
                val app = it.activityInfo.applicationInfo
                SearchTarget(app.packageName, app.loadLabel(pm).toString(), app.loadIcon(pm))
            }
    }

    fun resolve(preferredPackage: String?): SearchTarget? {
        val handlers = handlers()
        return handlers.firstOrNull { it.packageName == preferredPackage }
            ?: handlers.firstOrNull { it.packageName == FIREFOX }
            ?: handlers.firstOrNull()
    }

    /**
     * Opens [target]'s search. If it no longer handles the intent, or refuses it (updated,
     * uninstalled, or not exported after all), opens the app instead; with no target at all,
     * nothing happens.
     */
    fun search(target: SearchTarget?) {
        val packageName = target?.packageName ?: return
        val search = searchIntent(packageName) ?: return
        try {
            context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            openInstead(packageName, e)
        } catch (e: SecurityException) {
            openInstead(packageName, e)
        }
    }

    /**
     * Firefox opens plain web-search intents on its home page; its search widget's entry point is
     * what opens the address bar with the keyboard up. Everyone else gets the web-search intent.
     */
    private fun searchIntent(packageName: String): Intent? =
        if (packageName in FIREFOXES) {
            context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.putExtra("open_to_search", "search_widget")
        } else {
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(packageName)
        }

    /** The target no longer handles the search, or refuses it (not exported after all). */
    private fun openInstead(packageName: String, why: Exception) {
        Log.w(TAG, "$packageName would not take the search; opening it instead", why)
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        const val FIREFOX = "org.mozilla.firefox"
        val FIREFOXES =
            setOf(
                FIREFOX,
                "org.mozilla.firefox_beta",
                "org.mozilla.fenix",
                "org.mozilla.fennec_fdroid",
            )
        const val TAG = "Runway"
    }
}
