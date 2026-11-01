package com.grayvines.runway.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * Badge mutes: installed apps whose notification badges the launcher suppresses everywhere (home
 * screen, dock, folders, and drawer).
 */

fun WorkspaceRepository.observeBadgeMutes(): Flow<Set<AppRef>> =
    dao.observeBadgeMutes().map { muted -> muted.mapTo(mutableSetOf()) { it.ref } }

/** Mutes notification badges for [app]; an app already muted is left as it was. */
suspend fun WorkspaceRepository.muteBadges(app: AppRef) = write {
    dao.insertBadgeMute(BadgeMuteEntity(app.component, app.profile))
}

/** Shows notification badges for [app] again; one that was not muted is left as it was. */
suspend fun WorkspaceRepository.unmuteBadges(app: AppRef) = write {
    dao.deleteBadgeMute(app.component, app.profile)
}
