package com.grayvines.runway.system.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.net.toUri
import com.grayvines.runway.data.AppRef
import kotlinx.coroutines.CoroutineScope
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

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

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

    /**
     * One refresh at a time, in order: a burst of package callbacks must end on the newest list.
     */
    private val refreshing = Mutex()

    fun refresh() {
        scope.launch {
            refreshing.withLock {
                _apps.value =
                    userManager.userProfiles
                        .flatMap { user ->
                            launcherApps.getActivityList(null, user).map { it.toEntry() }
                        }
                        .sortedBy { it.label.lowercase() }
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
        )

    private companion object {
        const val REMOVAL_BUFFER = 16
        const val TAG = "Runway"
    }
}
