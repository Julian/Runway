package com.grayvines.runway.system.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.system.apps.LabelOrder

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
     * slow, for a picker that is opening, not for the main thread.
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
    private val host =
        object : AppWidgetHost(this.context, HOST_ID) {
            fun dropViews() = clearViews()
        }
    private var activeActivity: Activity? = null

    /** Providers start sending updates; hosted views draw them. */
    fun startListening(activity: Activity) {
        activeActivity = activity
        host.startListening()
    }

    fun stopListening(activity: Activity) {
        if (activeActivity === activity) host.stopListening()
    }

    /**
     * Drops the host's cached views when [activity] is still the current launcher activity, so a
     * replaced activity is not kept alive until its widgets are drawn again, while an older
     * instance finishing after its replacement has started leaves the replacement's views alone.
     */
    fun clearViews(activity: Activity) {
        if (activeActivity === activity) {
            activeActivity = null
            host.dropViews()
        }
    }

    override fun allocateId(): Int = host.allocateAppWidgetId()

    override fun deleteId(id: Int) = host.deleteAppWidgetId(id)

    /** What is bound to [id], or null when nothing is (never bound, or its app is gone). */
    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    override fun bind(id: Int, provider: WidgetProvider): Boolean {
        val info = provider.info ?: return false
        return manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
    }

    /**
     * Binds a fresh id to [provider] (a flattened component name) when it is installed, needs no
     * setup screen of its own, and the system allows the bind without asking; null otherwise.
     */
    fun restore(provider: String): Int? {
        val component = ComponentName.unflattenFromString(provider) ?: return null
        val info =
            userManager.userProfiles
                .flatMap { manager.getInstalledProvidersForProfile(it) }
                .firstOrNull { it.provider == component && !it.needsSetup }
        if (info == null) return null
        val id = allocateId()
        return if (manager.bindAppWidgetIdIfAllowed(id, info.profile, component, null)) {
            id
        } else {
            deleteId(id)
            null
        }
    }

    /**
     * Starts the setup screen of the widget bound to [id] from [activity], which hears back in its
     * activity result under [requestCode]. Only the host may start it: the system hands the screen
     * a token for the id.
     */
    fun configure(activity: Activity, id: Int, requestCode: Int) =
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)

    /** The view that draws widget [id]; [context] is the activity's, for its theme. */
    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        if (context is Activity) activeActivity = context
        return host.createView(context, id, info)
    }

    override fun providers(): List<WidgetProvider> {
        val pm = context.packageManager
        val density = context.resources.displayMetrics.density
        val order = LabelOrder.comparator()
        return userManager.userProfiles
            .flatMap { manager.getInstalledProvidersForProfile(it) }
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            // Some ask to be kept out of pickers: a launcher's own internal widgets, say.
            .filter {
                it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
            }
            .mapNotNull { info -> info.toProvider(context, pm, density) }
            .sortedWith(
                compareBy<WidgetProvider, String>(order) { it.appLabel }.thenBy(order) { it.label }
            )
    }

    companion object {
        /** Never change: the ids in the database were allocated under it. */
        const val HOST_ID = 0x52574159 // "RWAY"
    }
}

private const val ICON_DP = 48
private const val PREVIEW_DP = 240
private const val TAG = "Runway"

/** Null if the provider's package cannot be read: one broken app must not empty the list. */
@Suppress("TooGenericExceptionCaught")
internal fun AppWidgetProviderInfo.toProvider(
    context: Context,
    pm: PackageManager,
    density: Float,
) =
    try {
        val app = activityInfo.applicationInfo
        val flat = provider.flattenToString()
        WidgetProvider(
            providerName = flat,
            key = "${profile.hashCode()}/$flat",
            label = loadLabel(pm),
            appLabel = pm.getApplicationLabel(app).toString(),
            appIcon = pm.getApplicationIcon(app).toPicture((ICON_DP * density).toInt()),
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
