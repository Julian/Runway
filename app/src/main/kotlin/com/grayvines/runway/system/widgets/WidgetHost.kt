package com.grayvines.runway.system.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.system.apps.LabelOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * A widget the device offers, as the picker lists it: its provider, what to call it and the app it
 * belongs to, a picture of it, and the sizes it asks for, in dp.
 */
class WidgetProvider(
    val providerName: String,
    val key: String = providerName,
    val label: String = "",
    val appLabel: String = "",
    val appIcon: ImageBitmap? = null,
    val preview: ImageBitmap? = null,
    val minWidthDp: Float = 0f,
    val minHeightDp: Float = 0f,
    val targetCellWidth: Int = 0,
    val targetCellHeight: Int = 0,
    val needsSetup: Boolean = false,
    internal val info: AppWidgetProviderInfo? = null,
) {
    /** This provider without its bitmaps, for carrying through a drag after the picker closes. */
    fun withoutPictures() =
        WidgetProvider(
            providerName = providerName,
            key = key,
            label = label,
            appLabel = appLabel,
            minWidthDp = minWidthDp,
            minHeightDp = minHeightDp,
            targetCellWidth = targetCellWidth,
            targetCellHeight = targetCellHeight,
            needsSetup = needsSetup,
            info = info,
        )

    override fun equals(other: Any?) =
        other is WidgetProvider && other.key == key && other.providerName == providerName

    override fun hashCode() = key.hashCode() * 31 + providerName.hashCode()

    override fun toString() = "WidgetProvider($key)"
}

/** The widget insists on its configuration activity before it can be shown. */
val AppWidgetProviderInfo.needsSetup: Boolean
    get() =
        configure != null &&
            widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

/**
 * The provider offers a setup screen that may be run again for a widget already placed, rather than
 * only once as it is added.
 */
val AppWidgetProviderInfo.isReconfigurable: Boolean
    get() =
        configure != null &&
            widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0

/**
 * The system's request to let this launcher bind widgets, with [id] and [provider] as the first; it
 * comes back through the activity that starts it, and on OK [id] is bound.
 */
fun bindWidgetRequest(id: Int, provider: ComponentName, user: UserHandle): Intent =
    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)

/**
 * What the picker needs of the widget host: listing providers, handing out and taking back host
 * ids, and binding a provider to one.
 */
interface Widgets {
    /**
     * Already-loaded home-screen widget providers from memory, or null when not cached or
     * invalidated. Safe to read on the main thread.
     */
    val cachedProviders: List<WidgetProvider>?
        get() = null

    /** A fresh id to bind a provider to; deleted again if the binding never happens. */
    fun allocateId(): Int

    /** Forgets an id: the provider is told, and the id can be handed out again. */
    fun deleteId(id: Int)

    /**
     * Binds [provider] to [id] without asking, which the system allows once the user has granted
     * this launcher the right to bind widgets; false means the system's bind prompt is needed.
     */
    fun bind(id: Int, provider: WidgetProvider): Boolean

    /**
     * Every home-screen widget installed, across profiles, by app and then by name, with pictures:
     * slow on a cache miss, for a picker that is opening, not for the main thread.
     */
    fun providers(): List<WidgetProvider>
}

/**
 * The one app widget host of the process, under a fixed host id so the ids it allocates survive
 * restarts and reboots: a stored id is bound and drawable again on the next start with no work of
 * ours. Listening is tied to the launcher activity's visibility.
 */
class WidgetHost(context: Context) : Widgets {
    private val context = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(this.context)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val infoCache = ConcurrentHashMap<Int, AppWidgetProviderInfo>()
    private val providerCache = WidgetProviderCache()
    private var configuration = Configuration(this.context.resources.configuration)
    private val host =
        object : AppWidgetHost(this.context, HOST_ID) {
            fun dropViews() = clearViews()

            override fun onProviderChanged(appWidgetId: Int, appWidget: AppWidgetProviderInfo?) {
                super.onProviderChanged(appWidgetId, appWidget)
                if (appWidget != null) {
                    infoCache[appWidgetId] = appWidget
                } else {
                    infoCache.remove(appWidgetId)
                }
                providerCache.invalidate()
            }

            override fun onProvidersChanged() {
                super.onProvidersChanged()
                infoCache.clear()
                providerCache.invalidate()
            }

            override fun onAppWidgetRemoved(appWidgetId: Int) {
                super.onAppWidgetRemoved(appWidgetId)
                infoCache.remove(appWidgetId)
            }
        }
    private var listeningActivity: Activity? = null
    private var viewActivity: Activity? = null

    override val cachedProviders: List<WidgetProvider>?
        get() = providerCache.peek()

    /** Providers start sending updates; hosted views draw them. */
    @Suppress("TooGenericExceptionCaught")
    fun startListening(activity: Activity) {
        viewActivity = activity
        // Provider metadata updates while stopped are not replayed as PendingHostUpdates.
        infoCache.clear()
        try {
            host.startListening()
            listeningActivity = activity
        } catch (e: RuntimeException) {
            listeningActivity = null
            Log.w(TAG, "could not start listening to widgets", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun stopListening(activity: Activity) {
        if (listeningActivity === activity) {
            listeningActivity = null
            try {
                host.stopListening()
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not stop listening to widgets", e)
            }
        }
    }

    /**
     * Drops the host's cached views when [activity] is still the current launcher activity, so a
     * replaced activity is not kept alive until its widgets are drawn again, while an older
     * instance finishing after its replacement has started leaves the replacement's views alone.
     */
    @Suppress("TooGenericExceptionCaught")
    fun clearViews(activity: Activity) {
        if (viewActivity === activity) {
            viewActivity = null
            try {
                host.dropViews()
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not clear widget views", e)
            }
        }
    }

    override fun allocateId(): Int = host.allocateAppWidgetId()

    @Suppress("TooGenericExceptionCaught")
    override fun deleteId(id: Int) {
        infoCache.remove(id)
        try {
            host.deleteAppWidgetId(id)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not delete widget id $id", e)
        }
    }

    /** What is bound to [id], or null when nothing is (never bound, or its app is gone). */
    @Suppress("TooGenericExceptionCaught")
    fun info(id: Int): AppWidgetProviderInfo? =
        infoCache[id]
            ?: try {
                manager.getAppWidgetInfo(id)?.also { infoCache[id] = it }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not read widget info for $id", e)
                null
            }

    @Suppress("TooGenericExceptionCaught")
    override fun bind(id: Int, provider: WidgetProvider): Boolean {
        val info = provider.info ?: return false
        infoCache.remove(id)
        return try {
            manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not bind widget ${info.provider}", e)
            false
        }
    }

    /**
     * Binds a fresh id to [provider] (a flattened component name) when it is installed, needs no
     * setup screen of its own, and the system allows the bind without asking; null otherwise.
     */
    fun restore(provider: String): Int? = restorer().invoke(provider)

    /**
     * Snapshot of eligible installed providers for one backup restore, avoiding repeated IPC per
     * restored widget.
     */
    internal fun restorer(): (String) -> Int? {
        val eligible = installedProviders().filterNot { it.needsSetup }.associateBy { it.provider }
        return { provider ->
            ComponentName.unflattenFromString(provider)
                ?.let(eligible::get)
                ?.let(::bindRestoredProvider)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun bindRestoredProvider(info: AppWidgetProviderInfo): Int? {
        val id =
            try {
                allocateId()
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not allocate widget id for ${info.provider}", e)
                return null
            }
        val bound =
            try {
                manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not restore widget ${info.provider}", e)
                false
            }
        return if (bound) {
            infoCache[id] = info
            id
        } else {
            deleteId(id)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun installedProvidersOrNull(): List<AppWidgetProviderInfo>? {
        var failed = false
        val list =
            try {
                userManager.userProfiles.flatMap { profile ->
                    try {
                        manager.getInstalledProvidersForProfile(profile)
                    } catch (e: RuntimeException) {
                        failed = true
                        Log.w(TAG, "could not read widget providers for profile $profile", e)
                        emptyList()
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not list user profiles for widgets", e)
                return null
            }
        return if (failed && list.isEmpty()) null else list
    }

    internal fun installedProviders(): List<AppWidgetProviderInfo> =
        installedProvidersOrNull().orEmpty()

    /**
     * Starts the setup screen of the widget bound to [id] from [activity], which hears back in its
     * activity result under [requestCode]. Only the host may start it: the system hands the screen
     * a token for the id.
     */
    fun configure(activity: Activity, id: Int, requestCode: Int) =
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)

    /**
     * The view that draws widget [id]; [context] is the activity's, for its theme. Null if the
     * system or provider fails to inflate it, so the cell can show a placeholder instead of
     * crashing the launcher.
     */
    @Suppress("TooGenericExceptionCaught")
    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView? {
        if (context is Activity) viewActivity = context
        return try {
            host.createView(context, id, info)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not create view for widget $id (${info.provider})", e)
            null
        }
    }

    internal fun invalidateProviders() {
        providerCache.invalidate()
    }

    internal fun configurationChanged(config: Configuration) {
        val changed = configuration.diff(config)
        configuration = Configuration(config)
        if (changed and PROVIDER_STALING_CHANGES != 0) providerCache.invalidate()
    }

    override fun providers(): List<WidgetProvider> = providerCache.getOrLoad {
        installedProvidersOrNull()?.let { buildWidgetProviders(it, context) }
    }

    companion object {
        /** Never change: the ids in the database were allocated under it. */
        const val HOST_ID = 0x52574159 // "RWAY"
    }
}

private const val ICON_DP = 48
private const val PREVIEW_DP = 240
private const val TAG = "Runway"
private const val PROVIDER_STALING_CHANGES =
    ActivityInfo.CONFIG_LOCALE or
        ActivityInfo.CONFIG_LAYOUT_DIRECTION or
        ActivityInfo.CONFIG_DENSITY or
        ActivityInfo.CONFIG_UI_MODE

/**
 * Process-wide cache of rasterised widget providers. Invalidations bump an internal generation
 * counter so a load that was already running across a package or configuration change cannot
 * publish stale data.
 */
internal class WidgetProviderCache {
    private val lock = Any()
    private var generation = 0L
    private var cached: List<WidgetProvider>? = null

    fun peek(): List<WidgetProvider>? = synchronized(lock) { cached }

    fun invalidate() {
        synchronized(lock) {
            generation++
            cached = null
        }
    }

    /**
     * Returns the cached providers, or runs [load] outside the lock and caches its result when
     * non-null and no [invalidate] raced with the load.
     */
    fun getOrLoad(load: () -> List<WidgetProvider>?): List<WidgetProvider> {
        val hitOrGen = synchronized(lock) { cached to generation }
        val hit = hitOrGen.first
        if (hit != null) return hit
        val loaded = load()
        if (loaded != null) {
            synchronized(lock) { if (generation == hitOrGen.second) cached = loaded }
        }
        return loaded.orEmpty()
    }
}

internal fun buildWidgetProviders(
    installed: List<AppWidgetProviderInfo>,
    context: Context,
): List<WidgetProvider> {
    val pm = context.packageManager
    val density = context.resources.displayMetrics.density
    val iconPx = (ICON_DP * density).toInt()
    val order = LabelOrder.comparator()
    val appIcons = HashMap<Pair<UserHandle, String>, ImageBitmap?>()
    return installed
        .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        // Some ask to be kept out of pickers: a launcher's own internal widgets, say.
        .filter {
            it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
        }
        .mapNotNull { info ->
            info.toProvider(context, pm, density) { app ->
                appIcons.getOrPut(info.profile to app.packageName) {
                    pm.getApplicationIcon(app).toPicture(iconPx)
                }
            }
        }
        .sortedWith(
            compareBy<WidgetProvider, String>(order) { it.appLabel }.thenBy(order) { it.label }
        )
}

/** Null if the provider's package cannot be read: one broken app must not empty the list. */
@Suppress("TooGenericExceptionCaught")
internal fun AppWidgetProviderInfo.toProvider(
    context: Context,
    pm: PackageManager,
    density: Float,
    appIcon: (ApplicationInfo) -> ImageBitmap? = {
        pm.getApplicationIcon(it).toPicture((ICON_DP * density).toInt())
    },
) =
    try {
        val app = activityInfo.applicationInfo
        val flat = provider.flattenToString()
        WidgetProvider(
            providerName = flat,
            key = "${profile.hashCode()}/$flat",
            label = loadLabel(pm),
            appLabel = pm.getApplicationLabel(app).toString(),
            appIcon = appIcon(app),
            preview = loadPreviewImage(context, 0)?.toPicture((PREVIEW_DP * density).toInt()),
            minWidthDp = minWidth / density,
            minHeightDp = minHeight / density,
            targetCellWidth = targetCellWidth,
            targetCellHeight = targetCellHeight,
            needsSetup = needsSetup,
            info = this,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "could not read widget $provider; left out of the list", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not read widget $provider; left out of the list", e)
        null
    }

/** The drawable as a bitmap no wider or taller than [maxPx], keeping its shape. */
internal fun Drawable.toPicture(maxPx: Int): ImageBitmap? {
    val w = intrinsicWidth
    val h = intrinsicHeight
    if (w <= 0 || h <= 0) return null
    val scale = minOf(1f, maxPx.toFloat() / w, maxPx.toFloat() / h)
    return toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        .asImageBitmap()
}
