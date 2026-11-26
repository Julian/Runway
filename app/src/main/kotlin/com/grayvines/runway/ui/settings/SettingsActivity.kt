package com.grayvines.runway.ui.settings

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grayvines.runway.AppGraph
import com.grayvines.runway.LauncherActivity
import com.grayvines.runway.appGraph
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.clear
import com.grayvines.runway.data.hideApp
import com.grayvines.runway.data.observeHiddenApps
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.unhideApp
import com.grayvines.runway.system.search.SearchTarget
import com.grayvines.runway.ui.theme.SettingsTheme
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            if (uri != null) {
                val graph = appGraph
                val resolver = applicationContext.contentResolver
                withFile("could not save the backup") {
                    writeBackupUtf8(resolver, uri, graph.backup.export())
                }
            }
        }
    private val restoreBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val graph = appGraph
                val resolver = applicationContext.contentResolver
                withFile("could not read the backup") { restoreBackup(graph, resolver, uri) }
            }
        }
    private val requestHomeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateHomeRoleState()
        }

    private var hasNotificationAccess by mutableStateOf(false)
    private var searchTargets by mutableStateOf(emptyList<SearchTarget>())
    private var isDefault by mutableStateOf(false)
    private var homeRoleAvailable by mutableStateOf(false)
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = appGraph
        val debugActions = debugActions()
        val hiddenApps = graph.workspace.observeHiddenApps()
        val backupActions = backupActions()
        val onChange: ((Settings) -> Settings) -> Unit = { transform ->
            graph.appScope.launch { graph.settings.update(transform) }
        }
        val openHome: () -> Unit = { startActivity(Intent(this, LauncherActivity::class.java)) }
        val setDefaultHome: () -> Unit = ::requestDefaultHome
        val grantNotificationAccess: () -> Unit = {
            if (!graph.badges.openAccessSettings()) {
                Toast.makeText(this, "Could not open notification settings", Toast.LENGTH_SHORT)
                    .show()
            }
        }
        setContent {
            SettingsTheme {
                val settings by graph.settings.settings.collectAsStateWithLifecycle(Settings())
                val apps by graph.appRepository.apps.collectAsStateWithLifecycle()
                val hidden by hiddenApps.collectAsStateWithLifecycle(emptySet())
                val onOpenHome = remember(isDefault) { openHome.takeUnless { isDefault } }
                val onSetDefaultHome =
                    remember(isDefault, homeRoleAvailable) {
                        setDefaultHome.takeIf { !isDefault && homeRoleAvailable }
                    }
                SettingsScreen(
                    settings = settings,
                    searchTargets = searchTargets,
                    apps = apps,
                    hiddenApps = hidden,
                    onHide = ::setHidden,
                    hasNotificationAccess = hasNotificationAccess,
                    onGrantNotificationAccess = grantNotificationAccess,
                    onOpenHome = onOpenHome,
                    onSetDefaultHome = onSetDefaultHome,
                    onChange = onChange,
                    backupActions = backupActions,
                    debugActions = debugActions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val graph = appGraph
        hasNotificationAccess = graph.badges.hasAccess()
        updateHomeRoleState()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            searchTargets = withContext(Dispatchers.IO) { graph.searchTargets.handlers() }
        }
    }

    private fun updateHomeRoleState() {
        val roleManager = getSystemService(RoleManager::class.java)
        isDefault = roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
        homeRoleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true
    }

    private fun backupActions() =
        BackupActions(
            save = {
                try {
                    saveBackup.launch("runway-${LocalDate.now()}.json")
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no document creator available", e)
                    Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show()
                }
            },
            restore = {
                try {
                    restoreBackup.launch(arrayOf(BACKUP_MIME, "*/*"))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no document picker available", e)
                    Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show()
                }
            },
        )

    private fun requestDefaultHome() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try {
                requestHomeRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "could not open default home prompt", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "not allowed to open default home prompt", e)
            }
        }
    }

    /** Leaves [app] out of the drawer, or shows it there again. */
    private fun setHidden(app: AppRef, hidden: Boolean) {
        val workspace = appGraph.workspace
        appGraph.appScope.launch {
            if (hidden) workspace.hideApp(app) else workspace.unhideApp(app)
        }
    }

    /** The debug page's actions, in a debug build only. */
    private fun debugActions(): DebugActions? {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val graph = appGraph
        return DebugActions(
            fillWithAllApps = {
                graph.appScope.launch {
                    val apps = graph.appRepository.apps.first { it.isNotEmpty() }.map { it.ref }
                    val s = graph.settings.settings.first()
                    graph.workspace.autoFill(
                        apps,
                        s.columns,
                        s.pageRows,
                        s.dockSlots,
                        deleteWidget = graph.widgets::deleteId,
                    )
                }
            },
            clearLayout = {
                graph.appScope.launch {
                    graph.workspace.clear(deleteWidget = graph.widgets::deleteId)
                }
            },
        )
    }

    /**
     * Runs a file operation on the application scope so leaving or rotating settings cannot
     * truncate an export or leave a restore half-applied; what goes wrong is shown, not thrown. A
     * refusal ([IllegalArgumentException]) says why; anything else, the file or the database
     * failing, shows as [failure].
     */
    @Suppress("TooGenericExceptionCaught")
    private fun withFile(failure: String, block: suspend () -> String) {
        val appContext = applicationContext
        appGraph.appScope.launch {
            val message =
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, failure, e)
                    e.message ?: failure
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, failure, e)
                    failure
                }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "Runway"
        const val BACKUP_MIME = "application/json"
    }
}

internal const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
private const val READ_BUFFER_BYTES = 8 * 1024

internal suspend fun restoreBackup(graph: AppGraph, resolver: ContentResolver, uri: Uri): String {
    val text =
        resolver.openInputStream(uri)?.use { it.readBoundedUtf8() }
            ?: throw IOException("nothing to read")
    val installed =
        graph.appRepository.apps.first { it.isNotEmpty() }.mapTo(mutableSetOf()) { it.ref }
    // A profile with no app in the list is paused, not empty: its apps are kept unchecked.
    val quiet = graph.appRepository.profiles() - installed.mapTo(mutableSetOf()) { it.profile }
    val restored = graph.backup.restore(text, installed, quiet)
    return if (restored.skipped == 0) {
        "Restored ${restored.placed} items"
    } else {
        "Restored ${restored.placed} items; ${restored.skipped} apps are not installed"
    }
}

internal fun writeBackupUtf8(resolver: ContentResolver, uri: Uri, text: String): String {
    val stream =
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("nothing to write to")
    stream.use { out ->
        if (out is FileOutputStream) {
            runCatching { out.channel.truncate(0) }
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }
    return "Backup saved"
}

/**
 * Reads UTF-8 text from this stream up to [maxBytes], throwing [IllegalArgumentException] instead
 * of exhausting memory if the user selects an unexpectedly large file.
 */
internal fun InputStream.readBoundedUtf8(maxBytes: Int = MAX_BACKUP_BYTES): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        require(read <= maxBytes - total) { "Backup file is too large" }
        total += read
        out.write(buffer, 0, read)
    }
    return out.toByteArray().decodeToString()
}
