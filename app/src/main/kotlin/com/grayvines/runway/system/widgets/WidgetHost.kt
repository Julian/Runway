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
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    val appIcon: ImageBitmap?,
    val preview: ImageBitmap?,
    val minWidthDp: Float,
    val minHeightDp: Float,
) {
    val key: String
        get() = "${info.profile.hashCode()}/${info.provider.flattenToString()}"

    /** The widget insists on its configuration activity before it can be shown. */
    val needsSetup: Boolean
        get() =
            info.configure != null &&
                info.widgetFeatures and
                    AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
}

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
 * The one app widget host of the process, under a fixed host id so the ids it allocates survive
 * restarts and reboots: a stored id is bound and drawable again on the next start with no work of
 * ours. Listening is tied to the launcher activity's visibility.
 */
class WidgetHost(context: Context) {
    private val context = context.applicationContext
    private val manager: AppWidgetManager = AppWidgetManager.getInstance(this.context)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val host = AppWidgetHost(this.context, HOST_ID)

    /** Providers start sending updates; hosted views draw them. */
    fun startListening() = host.startListening()

    fun stopListening() = host.stopListening()

    /** A fresh id to bind a provider to; deleted again if the binding never happens. */
    fun allocateId(): Int = host.allocateAppWidgetId()

    /** Forgets an id: the provider is told, and the id can be handed out again. */
    fun deleteId(id: Int) = host.deleteAppWidgetId(id)

    /** What is bound to [id], or null when nothing is (never bound, or its app is gone). */
    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /**
     * Binds [provider] to [id] without asking, which the system allows once the user has granted
     * this launcher the right to bind widgets; false means the system's bind prompt is needed.
     */
    fun bind(id: Int, provider: ComponentName, user: UserHandle): Boolean =
        manager.bindAppWidgetIdIfAllowed(id, user, provider, null)

    /**
     * Starts the setup screen of the widget bound to [id] from [activity], which hears back in its
     * activity result under [requestCode]. Only the host may start it: the system hands the screen
     * a token for the id.
     */
    fun configure(activity: Activity, id: Int, requestCode: Int) =
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)

    /** The view that draws widget [id]; [context] is the activity's, for its theme. */
    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, id, info)

    /**
     * Every home-screen widget installed, across profiles, by app and then by name, with pictures:
     * slow, for a picker that is opening, not for the main thread.
     */
    fun providers(): List<WidgetProvider> {
        val pm = context.packageManager
        val density = context.resources.displayMetrics.density
        val order = LabelOrder.comparator()
        return userManager.userProfiles
            .flatMap { manager.getInstalledProvidersForProfile(it) }
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .mapNotNull { info -> info.toProvider(pm, density) }
            .sortedWith(
                compareBy<WidgetProvider, String>(order) { it.appLabel }.thenBy(order) { it.label }
            )
    }

    /** Null if the provider's package cannot be read: one broken app must not empty the list. */
    @Suppress("TooGenericExceptionCaught")
    private fun AppWidgetProviderInfo.toProvider(pm: PackageManager, density: Float) =
        try {
            val app = pm.getApplicationInfo(provider.packageName, 0)
            WidgetProvider(
                info = this,
                label = loadLabel(pm),
                appLabel = pm.getApplicationLabel(app).toString(),
                appIcon = pm.getApplicationIcon(app).toPicture((ICON_DP * density).toInt()),
                preview = loadPreviewImage(context, 0)?.toPicture((PREVIEW_DP * density).toInt()),
                minWidthDp = minWidth / density,
                minHeightDp = minHeight / density,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read widget $provider; left out of the list", e)
            null
        }

    /** The drawable as a bitmap no wider or taller than [maxPx], keeping its shape. */
    private fun Drawable.toPicture(maxPx: Int): ImageBitmap? {
        val w = intrinsicWidth
        val h = intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val scale = minOf(1f, maxPx.toFloat() / w, maxPx.toFloat() / h)
        return toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            .asImageBitmap()
    }

    companion object {
        /** Never change: the ids in the database were allocated under it. */
        const val HOST_ID = 0x52574159 // "RWAY"
        private const val ICON_DP = 48
        private const val PREVIEW_DP = 240
        private const val TAG = "Runway"
    }
}
