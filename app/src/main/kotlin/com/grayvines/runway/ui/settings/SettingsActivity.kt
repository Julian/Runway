package com.grayvines.runway.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.grayvines.runway.LauncherActivity
import com.grayvines.runway.appGraph
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.clear
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.theme.SettingsTheme
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            if (uri != null) {
                withFile("could not save the backup") { write(uri, appGraph.backup.export()) }
            }
        }
    private val restoreBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                withFile("could not read the backup") { restore(uri) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = appGraph
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val searchTargets = graph.searchTargets.handlers()
        val isHome =
            packageManager
                .resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
                ?.activityInfo
                ?.packageName == packageName
        setContent {
            SettingsTheme {
                val settings by graph.settings.settings.collectAsStateWithLifecycle(Settings())
                SettingsScreen(
                    settings = settings,
                    searchTargets = searchTargets,
                    onOpenHome =
                        if (isHome) {
                            null
                        } else {
                            { startActivity(Intent(this, LauncherActivity::class.java)) }
                        },
                    onChange = { transform ->
                        graph.appScope.launch { graph.settings.update(transform) }
                    },
                    backupActions =
                        BackupActions(
                            save = { saveBackup.launch("runway-${LocalDate.now()}.json") },
                            restore = { restoreBackup.launch(arrayOf(BACKUP_MIME, "*/*")) },
                        ),
                    debugActions =
                        if (debuggable) {
                            DebugActions(
                                fillWithAllApps = {
                                    graph.appScope.launch {
                                        val apps = graph.appRepository.apps.first().map { it.ref }
                                        val s = graph.settings.settings.first()
                                        graph.workspace.autoFill(
                                            apps,
                                            s.columns,
                                            s.pageRows,
                                            s.dockSlots,
                                        )
                                    }
                                },
                                clearLayout = { graph.appScope.launch { graph.workspace.clear() } },
                            )
                        } else {
                            null
                        },
                )
            }
        }
    }

    /**
     * Runs a file operation off the main thread; what goes wrong is shown, not thrown. A refusal
     * ([IllegalArgumentException]) says why; anything else, the file or the database failing, shows
     * as [failure], and the settings screen stays up either way.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun withFile(failure: String, block: suspend () -> String) {
        lifecycleScope.launch {
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
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun write(uri: android.net.Uri, text: String): String {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IOException("nothing to write to")
        return "Backup saved"
    }

    private suspend fun restore(uri: android.net.Uri): String {
        val text =
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw IOException("nothing to read")
        val installed =
            appGraph.appRepository.apps.first { it.isNotEmpty() }.mapTo(mutableSetOf()) { it.ref }
        // A profile with no app in the list is paused, not empty: its apps are kept unchecked.
        val quiet =
            appGraph.appRepository.profiles() - installed.mapTo(mutableSetOf()) { it.profile }
        val restored = appGraph.backup.restore(text, installed, quiet)
        val notes =
            listOfNotNull(
                "${restored.widgetsKept} widgets kept".takeIf { restored.widgetsKept > 0 },
                "${restored.skipped} apps are not installed".takeIf { restored.skipped > 0 },
            )
        return (listOf("Restored ${restored.placed} items") + notes).joinToString("; ")
    }

    private companion object {
        const val TAG = "Runway"
        const val BACKUP_MIME = "application/json"
    }
}
