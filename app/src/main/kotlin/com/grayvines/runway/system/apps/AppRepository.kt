package com.grayvines.runway.system.apps

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Launchable activities across all profiles, kept current via [LauncherApps.Callback]. */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val callback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()

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

    fun launch(entry: AppEntry) {
        launcherApps.startMainActivity(entry.component, entry.user, null, null)
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
}
