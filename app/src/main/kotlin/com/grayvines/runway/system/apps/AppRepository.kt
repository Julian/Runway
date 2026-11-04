package com.grayvines.runway.system.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.settings.IconShape
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Launchable activities across all profiles, kept current via [LauncherApps.Callback]. */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val iconCache = IconCache.bitmapCache(File(this.context.cacheDir, "icons"))

    /**
     * Null until [setIconShape] delivers the persisted shape from settings, so cold start does not
     * render every icon once under [IconShape.SYSTEM] before the user's chosen shape arrives.
     */
    @Volatile private var iconShape: IconShape? = null

    /** Last rendered entry and cache key per app, guarded by [refreshing]. */
    private var previousByRef: Map<AppRef, Pair<AppEntry, IconCacheKey>> = emptyMap()

    /**
     * Icons are drawn at most about this big; rasterising bigger is wasted memory and time. Read
     * per refresh: the density can change under a running launcher.
     */
    private val iconPx: Int
        get() = (ICON_DP * context.resources.displayMetrics.density).toInt()

    private val shortcutIconPx: Int
        get() = (SHORTCUT_ICON_DP * context.resources.displayMetrics.density).toInt()

    /**
     * The configuration the list was last built under. Labels and their order follow the locale,
     * icons the density; a change to either is rebuilt for, other changes are not.
     */
    private var configuration = Configuration(context.resources.configuration)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    /**
     * One refresh at a time, in order: a burst of package callbacks must end on the newest list.
     * Declared before `init`, which starts the first refresh: a property below it would still be
     * null if that refresh ran before the constructor finished.
     */
    private val refreshing = Mutex()

    private val _refreshed =
        MutableSharedFlow<List<AppEntry>>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Every list a refresh produced, including one identical to the last: [apps] only reports
     * changes, and reconciling the layout must happen on every refresh regardless. Each list is the
     * whole truth, so a collector that falls behind a burst of refreshes is given the newest and
     * skips the rest; nothing is ever lost by that.
     */
    val refreshed: SharedFlow<List<AppEntry>> = _refreshed

    private val callback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) =
                refresh(packageName)

            override fun onPackageAdded(packageName: String, user: UserHandle) =
                refresh(packageName)

            override fun onPackageChanged(packageName: String, user: UserHandle) =
                refresh(packageName)

            override fun onPackagesAvailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) = refresh(*packageNames)

            override fun onPackagesUnavailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) = if (replacing) refresh(*packageNames) else refresh()
        }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    /**
     * The serials of every profile on the device, whether or not it reports any apps right now: a
     * paused work profile is in here and not in [apps].
     */
    fun profiles(): Set<Long> =
        userManager.userProfiles.mapTo(mutableSetOf()) { userManager.getSerialNumberForUser(it) }

    /** The device configuration is now [config]; the list is rebuilt if that made it stale. */
    fun configurationChanged(config: Configuration) {
        val changed = configuration.diff(config)
        configuration = Configuration(config)
        if (changed and STALING_CHANGES != 0) refresh()
    }

    /** Masks every app icon to [shape], rebuilding the list when the shape changes. */
    fun setIconShape(shape: IconShape) {
        if (iconShape != shape) {
            iconShape = shape
            refresh()
        }
    }

    fun refresh(vararg invalidatedPackages: String) {
        scope.launch {
            refreshing.withLock {
                invalidatedPackages.forEach(iconCache::invalidatePackage)
                iconShape?.let { shape ->
                    val previous = previousByRef
                    val densityDpi = context.resources.displayMetrics.densityDpi
                    // Rasterising every icon is the slow part of a refresh: spread it over the
                    // cores.
                    val loaded = coroutineScope {
                        userManager.userProfiles
                            .flatMap { user -> launcherApps.getActivityList(null, user) }
                            .map { info ->
                                async { info.toEntryOrNull(previous, shape, densityDpi) }
                            }
                            .awaitAll()
                    }
                        .filterNotNull()
                    if (loaded.isNotEmpty()) {
                        iconCache.retainOnly(loaded.mapTo(HashSet(loaded.size)) { it.second })
                    }
                    previousByRef = loaded.associateBy { it.first.ref }
                    val fresh = loaded.map { it.first }.sortedWith(LabelOrder.entryComparator())
                    _apps.value = fresh
                    _refreshed.tryEmit(fresh)
                }
            }
        }
    }

    /** Launches [entry]; if it can't be (uninstalled or suspended since), refreshes the list. */
    fun launch(entry: AppEntry) {
        try {
            launcherApps.startMainActivity(entry.component, entry.user, null, null)
        } catch (e: ActivityNotFoundException) {
            failedLaunch(entry, e)
        } catch (e: SecurityException) {
            failedLaunch(entry, e)
        }
    }

    /** Static and dynamic shortcuts for [entry], when this launcher is the default home app. */
    fun shortcuts(entry: AppEntry): List<AppShortcut> =
        launcherApps.queryShortcuts(
            entry,
            context.resources.displayMetrics.densityDpi,
            shortcutIconPx,
        )

    /** Launches [shortcutId] of [entry]; if it can't be, logs and refreshes the list. */
    fun startShortcut(entry: AppEntry, shortcutId: String) {
        try {
            launcherApps.startShortcut(
                entry.component.packageName,
                shortcutId,
                null,
                null,
                entry.user,
            )
        } catch (e: ActivityNotFoundException) {
            failedLaunch(entry, e)
        } catch (e: SecurityException) {
            failedLaunch(entry, e)
        } catch (e: IllegalStateException) {
            failedLaunch(entry, e)
        }
    }

    /** The system's settings page for the app. */
    fun showAppInfo(entry: AppEntry) {
        launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
    }

    /**
     * The system's uninstall confirmation; if it goes ahead, the package callback refreshes the
     * list, and the layout is reconciled against it.
     */
    fun uninstall(entry: AppEntry) {
        val intent =
            Intent(Intent.ACTION_DELETE, "package:${entry.component.packageName}".toUri())
                .putExtra(Intent.EXTRA_USER, entry.user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no uninstaller for ${entry.component}", e)
        }
    }

    private fun failedLaunch(entry: AppEntry, e: Exception) {
        Log.w(TAG, "could not launch ${entry.component}", e)
        refresh()
    }

    /**
     * The entry for this activity, or null if the package cannot be read (its resources broken, or
     * gone since the list was asked for): one such app must not keep the whole list, and with it
     * the launcher, from ever loading.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a broken package throws, it is left out
    private fun LauncherActivityInfo.toEntryOrNull(
        previous: Map<AppRef, Pair<AppEntry, IconCacheKey>>,
        shape: IconShape,
        densityDpi: Int,
    ): Pair<AppEntry, IconCacheKey>? =
        try {
            val appContext = context
            val profileSerial =
                userManager.getSerialNumberForUser(user) // persistable, unlike UserHandle
            val ref = AppRef(componentName.flattenToString(), profileSerial)
            val currentLabel = label.toString()
            val (versionCode, lastUpdateTime) = packageMetadata(appContext.packageManager)
            val cacheKey =
                IconCacheKey(
                    packageName = componentName.packageName,
                    component = componentName.flattenToString(),
                    profileSerial = profileSerial,
                    versionCode = versionCode,
                    lastUpdateTime = lastUpdateTime,
                    densityDpi = densityDpi,
                    shape = shape,
                )
            val prev = previous[ref]
            val unchanged =
                prev != null && prev.second == cacheKey && prev.first.label == currentLabel
            if (unchanged && iconCache.contains(cacheKey)) {
                prev
            } else {
                val size = iconPx
                val androidBitmap =
                    iconCache.getOrPut(cacheKey) { renderIcon(appContext, size, shape) }
                val prevEntry = prev?.first
                val samePixels =
                    prevEntry != null && prevEntry.bitmap.asAndroidBitmap().sameAs(androidBitmap)
                val bitmap = if (samePixels) prevEntry.bitmap else androidBitmap.asImageBitmap()
                val icon =
                    if (samePixels) {
                        prevEntry.icon
                    } else {
                        bitmap.asAndroidBitmap().toDrawable(appContext.resources)
                    }
                val entry =
                    AppEntry(
                        component = componentName,
                        user = user,
                        profileSerial = profileSerial,
                        label = currentLabel,
                        icon = icon,
                        bitmap = bitmap,
                    )
                entry to cacheKey
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read $componentName; left out of the list", e)
            null
        }

    private companion object {
        const val STALING_CHANGES =
            ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_LAYOUT_DIRECTION or
                ActivityInfo.CONFIG_DENSITY

        /** The largest an icon gets on screen (dp): a lifted icon on a wide, few-column grid. */
        const val ICON_DP = 72
        /** Shortcut icons in the long-press menu are drawn at 20 dp. */
        const val SHORTCUT_ICON_DP = 24
        const val TAG = "Runway"
    }
}

internal fun LauncherActivityInfo.renderIcon(
    context: Context,
    size: Int,
    shape: IconShape,
): Bitmap =
    IconRenderer.render(
        drawable = getIcon(0),
        sizePx = size,
        shape = shape,
        badge =
            if (user != Process.myUserHandle()) {
                { shaped ->
                    context.packageManager
                        .getUserBadgedIcon(shaped.toDrawable(context.resources), user)
                        .toBitmap(size, size)
                }
            } else {
                null
            },
    )

internal fun LauncherActivityInfo.packageMetadata(pm: PackageManager): Pair<Long, Long> =
    try {
        val pkg = pm.getPackageInfo(componentName.packageName, 0)
        pkg.longVersionCode to pkg.lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        // Work-profile-only packages may not be visible to the primary user's PackageManager.
        val apkModified = applicationInfo.sourceDir?.let { File(it).lastModified() } ?: 0L
        0L to maxOf(apkModified, firstInstallTime)
    }
