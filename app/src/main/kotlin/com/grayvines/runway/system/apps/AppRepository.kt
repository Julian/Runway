package com.grayvines.runway.system.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.grayvines.runway.data.AppRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Icons are drawn at most about this big; rasterising bigger is wasted memory and time. Read
     * per refresh: the density can change under a running launcher.
     */
    private val iconPx: Int
        get() = (ICON_DP * context.resources.displayMetrics.density).toInt()

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

    private val _refreshed = MutableSharedFlow<List<AppEntry>>(extraBufferCapacity = 1)

    /**
     * Every list a refresh produced, including one identical to the last: [apps] only reports
     * changes, and reconciling the layout must happen on every refresh regardless.
     */
    val refreshed: SharedFlow<List<AppEntry>> = _refreshed

    private val _removed = MutableSharedFlow<AppRef>(extraBufferCapacity = REMOVAL_BUFFER)

    /** Package name (as [AppRef.component]) and profile of each uninstalled app. */
    val removed: SharedFlow<AppRef> = _removed

    private val callback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                _removed.tryEmit(AppRef(packageName, userManager.getSerialNumberForUser(user)))
                refresh()
            }

            override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()

            override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()

            override fun onPackagesAvailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) = refresh()

            override fun onPackagesUnavailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean,
            ) = refresh()
        }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    /** The device configuration is now [config]; the list is rebuilt if that made it stale. */
    fun configurationChanged(config: Configuration) {
        val changed = configuration.diff(config)
        configuration = Configuration(config)
        if (changed and STALING_CHANGES != 0) refresh()
    }

    fun refresh() {
        scope.launch {
            refreshing.withLock {
                // Rasterising every icon is the slow part of a refresh: spread it over the cores.
                val fresh = coroutineScope {
                    userManager.userProfiles
                        .flatMap { user -> launcherApps.getActivityList(null, user) }
                        .map { info -> async { info.toEntry() } }
                        .awaitAll()
                }
                    .sortedBy { it.label.lowercase() }
                _apps.value = fresh
                _refreshed.tryEmit(fresh)
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

    /** The system's settings page for the app. */
    fun showAppInfo(entry: AppEntry) {
        launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
    }

    /**
     * The system's uninstall confirmation; the layout updates through [removed] if it goes ahead.
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

    private fun LauncherActivityInfo.toEntry() =
        AppEntry(
            component = componentName,
            user = user,
            profileSerial =
                userManager.getSerialNumberForUser(user), // persistable, unlike UserHandle
            label = label.toString(),
            icon = getIcon(0), // device density
            bitmap = getIcon(0).toBitmap(iconPx, iconPx).asImageBitmap(),
        )

    private companion object {
        const val REMOVAL_BUFFER = 16
        const val STALING_CHANGES =
            ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_LAYOUT_DIRECTION or
                ActivityInfo.CONFIG_DENSITY

        /** The largest an icon gets on screen (dp): a lifted icon on a wide, few-column grid. */
        const val ICON_DP = 72
        const val TAG = "Runway"
    }
}
