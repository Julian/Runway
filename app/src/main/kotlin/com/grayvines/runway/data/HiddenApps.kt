package com.grayvines.runway.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * Hidden apps: installed apps the drawer leaves out. Hiding is the drawer's business only; an app's
 * placements on the home screen and in the dock stay where they are.
 */

fun WorkspaceRepository.observeHiddenApps(): Flow<Set<AppRef>> =
    dao.observeHiddenApps().map { hidden -> hidden.mapTo(mutableSetOf()) { it.ref } }

/**
 * Hides [app] from the drawer, taking it out of the drawer folder it was in. A folder this empties
 * stays, as with [removeFromFolder], for [pruneEmptyFolders] to take once its sheet has closed.
 */
suspend fun WorkspaceRepository.hideApp(app: AppRef) = write {
    dao.leaveDrawerFolders(app.component, app.profile)
    dao.insertHiddenApp(HiddenAppEntity(app.component, app.profile))
}

/** Shows [app] in the drawer again; one that was not hidden is left as it was. */
suspend fun WorkspaceRepository.unhideApp(app: AppRef) = write {
    dao.deleteHiddenApp(app.component, app.profile)
}
