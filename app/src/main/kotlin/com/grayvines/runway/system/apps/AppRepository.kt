package com.grayvines.runway.system.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.grayvines.runway.data.AppRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Launchable activities across all profiles, kept current via [LauncherApps.Callback]. */
class AppRepository(context: Context, private val scope: CoroutineScope) {
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

    fun refresh() {
        scope.launch {
            _apps.value =
                userManager.userProfiles
                    .flatMap { user ->
                        launcherApps.getActivityList(null, user).map { it.toEntry() }
                    }
                    .sortedBy { it.label.lowercase() }
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
