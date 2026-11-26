package com.grayvines.runway.system.search

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** The app the search bar hands off to; the same app is the same target, whatever icon object. */
class SearchTarget(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null,
    internal val component: ComponentName? = null,
) {
    override fun equals(other: Any?) =
        other is SearchTarget &&
            other.packageName == packageName &&
            other.label == label &&
            other.component == component

    override fun hashCode() =
        (packageName.hashCode() * 31 + label.hashCode()) * 31 + (component?.hashCode() ?: 0)
}

/**
 * Picks the web-search handler (the configured package, else Firefox, else the first one) and hands
 * a search off to it. The launcher never talks to a search engine itself.
 */
class SearchTargetResolver(context: Context) {
    private val context = context.applicationContext

    /** Every app that can take a web search, as the settings picker lists them. */
    fun handlers(): List<SearchTarget> {
        val pm = context.packageManager
        return exportedHandlers(pm).map { info ->
            // The app's own name and icon: an activity may call itself just "Search".
            val app = info.activityInfo.applicationInfo
            val bitmap = runCatching {
                app.loadIcon(pm).toBitmap(CHOICE_ICON_PX, CHOICE_ICON_PX).asImageBitmap()
            }
                .getOrNull()
            val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(app.packageName)
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            SearchTarget(app.packageName, label, bitmap, component)
        }
    }

    /**
     * Resolves the chosen search handler without loading icons for every candidate on the phone.
     */
    fun resolve(preferredPackage: String?): SearchTarget? {
        val pm = context.packageManager
        val candidates = exportedHandlers(pm)
        val chosen =
            candidates.firstOrNull {
                it.activityInfo.applicationInfo.packageName == preferredPackage
            }
                ?: candidates.firstOrNull { it.activityInfo.applicationInfo.packageName == FIREFOX }
                ?: candidates.firstOrNull()
                ?: return null
        val app = chosen.activityInfo.applicationInfo
        val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(app.packageName)
        val component = ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name)
        return SearchTarget(app.packageName, label, component = component)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun exportedHandlers(pm: PackageManager): List<ResolveInfo> {
        val resolved =
            try {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_WEB_SEARCH),
                    PackageManager.ResolveInfoFlags.of(0),
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not query web search handlers", e)
                return emptyList()
            }
        // Only activities this launcher may start: a browser's private or permission-gated search
        // entry point shows up in the query but refuses us.
        return resolved
            .filter { info ->
                val activity = info.activityInfo
                val permission = activity.permission ?: activity.applicationInfo.permission
                activity.exported &&
                    (permission == null ||
                        context.checkSelfPermission(permission) ==
                            PackageManager.PERMISSION_GRANTED)
            }
            .distinctBy { it.activityInfo.applicationInfo.packageName }
    }

    /**
     * Opens [target]'s search. If it no longer handles the intent, or refuses it (updated,
     * uninstalled, or not exported after all), opens the app instead; with no target at all,
     * nothing happens.
     */
    fun search(target: SearchTarget?) {
        if (target == null) return
        val search = searchIntent(target) ?: return
        try {
            context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "${target.packageName} would not take the search; opening it instead", e)
            open(target)
        } catch (e: SecurityException) {
            Log.w(TAG, "${target.packageName} would not take the search; opening it instead", e)
            open(target)
        }
    }

    /**
     * Opens [target]'s normal front page, without asking for a search; with no target or no launch
     * activity, nothing happens.
     */
    fun open(target: SearchTarget?) {
        val packageName = target?.packageName ?: return
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$packageName could not be opened", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "$packageName could not be opened", e)
        }
    }

    /**
     * Firefox opens plain web-search intents on its home page; its search widget's entry point is
     * what opens the address bar with the keyboard up. Everyone else gets the resolved exported
     * web-search component (falling back to the package when constructed without one).
     */
    private fun searchIntent(target: SearchTarget): Intent? =
        if (target.packageName in FIREFOXES) {
            context.packageManager
                .getLaunchIntentForPackage(target.packageName)
                ?.putExtra("open_to_search", "search_widget")
        } else if (target.component != null) {
            Intent(Intent.ACTION_WEB_SEARCH).setComponent(target.component)
        } else {
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(target.packageName)
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
        const val CHOICE_ICON_PX = 96
        const val TAG = "Runway"
    }
}
