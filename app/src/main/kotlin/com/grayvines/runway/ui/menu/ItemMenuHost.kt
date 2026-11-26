package com.grayvines.runway.ui.menu

import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.createDrawerFolder
import com.grayvines.runway.data.deleteFolder
import com.grayvines.runway.data.muteBadges
import com.grayvines.runway.data.unmuteBadges
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.AppShortcut
import com.grayvines.runway.ui.attempt
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.asItem
import com.grayvines.runway.ui.writing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The item menu's state and what its actions do; one long press opens it, anything else closes it.
 */
class ItemMenuHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether a menu may open now: not over a live drag, where a second finger would put it. */
    private val mayOpen: () -> Boolean = { true },
    /** Whether notification badges are currently muted for this app. */
    private val isBadgesMuted: (AppRef) -> Boolean = { false },
    /** Closes any open drawer or folder behind a shortcut launched from it. */
    private val onShortcutLaunched: () -> Unit = {},
) {
    private val _state = MutableStateFlow<ItemMenuState?>(null)
    private var shortcutsJob: Job? = null

    /** The menu a long press opened, until it is dismissed, acted on, or turned into a drag. */
    val state: StateFlow<ItemMenuState?> = _state

    val isOpen: Boolean
        get() = _state.value != null

    /** The menu's actions, each closing it. */
    val actions =
        ItemMenuActions(
            appInfo = { withItem { it.app?.let(graph.appRepository::showAppInfo) } },
            uninstall = { withItem { it.app?.let(graph.appRepository::uninstall) } },
            remove = { withItem { item -> write("remove the item") { removeItem(item.id) } } },
            newFolder = {
                withItem { item ->
                    item.app?.let { app -> write("make a folder") { createDrawerFolder(app.ref) } }
                }
            },
            deleteFolder = {
                withItem { item ->
                    item.folderId?.let { id -> write("delete the folder") { deleteFolder(id) } }
                }
            },
            toggleBadges = {
                val menu = _state.value
                dismiss()
                menu?.item?.app?.ref?.let { ref ->
                    write("toggle badges") {
                        if (menu.badgesMuted) unmuteBadges(ref) else muteBadges(ref)
                    }
                }
            },
            startShortcut = { shortcut ->
                withItem { item ->
                    item.app?.let { app ->
                        graph.appRepository.startShortcut(app, shortcut.id)
                        onShortcutLaunched()
                    }
                }
            },
        )

    fun hold(item: HomeItem, container: Container, page: Int, cell: Bounds) {
        if (!mayOpen()) return
        val muted = item.app?.ref?.let(isBadgesMuted) ?: false
        val opened = ItemMenuState(item, container, page, cell, badgesMuted = muted)
        openMenu(opened, item.app)
    }

    /** A long press on [app] in the open folder [folderId], in [cell]. */
    fun holdInFolder(app: AppEntry, folderId: Long, cell: Bounds) {
        if (!mayOpen()) return
        val opened =
            ItemMenuState(
                app.asItem(),
                Container.DRAWER,
                0,
                cell,
                folderId,
                badgesMuted = isBadgesMuted(app.ref),
            )
        openMenu(opened, app)
    }

    fun dismiss() {
        shortcutsJob?.cancel()
        shortcutsJob = null
        _state.value = null
    }

    /**
     * Opens [opened] immediately without blocking the main thread on Binder shortcut IPC, then
     * fills in [app]'s shortcuts from [Dispatchers.IO] if the same menu is still open.
     */
    private fun openMenu(opened: ItemMenuState, app: AppEntry?) {
        shortcutsJob?.cancel()
        shortcutsJob = null
        _state.value = opened
        if (app == null) return
        shortcutsJob = scope.launch {
            var shortcuts = emptyList<AppShortcut>()
            attempt("load app shortcuts") {
                shortcuts = withContext(Dispatchers.IO) { graph.appRepository.shortcuts(app) }
            }
            if (shortcuts.isNotEmpty() && _state.value === opened) {
                _state.value = opened.copy(shortcuts = shortcuts)
            }
        }
    }

    /** A layout write off the main thread; a failure is logged, and the launcher stays up. */
    private fun write(what: String, block: suspend WorkspaceRepository.() -> Unit) {
        scope.writing(what) { graph.workspace.block() }
    }

    private inline fun withItem(block: (HomeItem) -> Unit) {
        val menu = _state.value ?: return
        dismiss()
        block(menu.item)
    }
}
