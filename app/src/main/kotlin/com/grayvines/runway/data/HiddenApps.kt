package com.grayvines.runway.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Hidden apps: installed apps the drawer leaves out. Hiding is the drawer's business only; an app's
 * placements on the home screen and in the dock stay where they are.
 */

fun WorkspaceRepository.observeHiddenApps(): Flow<Set<AppRef>> =
    dao.observeHiddenApps()
        .map { hidden -> hidden.mapTo(mutableSetOf()) { it.ref } }
        .distinctUntilChanged()

/**
 * Hides [app] from the drawer, taking it out of the drawer folder it was in. A drawer folder this
 * empties is removed right away along with any trailing empty pages left behind, since hiding runs
 * from Settings with no folder sheet open.
 */
suspend fun WorkspaceRepository.hideApp(app: AppRef) = write {
    val affectedFolders = dao.drawerFoldersContaining(app.component, app.profile)
    dao.leaveDrawerFolders(app.component, app.profile)
    dao.insertHiddenApp(HiddenAppEntity(app.component, app.profile))
    if (affectedFolders.isNotEmpty() && dao.deleteEmptyFoldersIn(affectedFolders) > 0) {
        dao.dropTrailingEmptyPages()
    }
}

/** Shows [app] in the drawer again; one that was not hidden is left as it was. */
suspend fun WorkspaceRepository.unhideApp(app: AppRef) = write {
    dao.deleteHiddenApp(app.component, app.profile)
}
