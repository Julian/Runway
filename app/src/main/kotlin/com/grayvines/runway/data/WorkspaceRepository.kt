package com.grayvines.runway.data

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.model.Placed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

/** An app identity as stored: flattened component name plus profile serial. */
@Serializable
data class AppRef(val component: String, val profile: Long) {
    /** What a placement of this app is a placement of; see [Placed.identity]. */
    val identity: String
        get() = "app:$profile/$component"
}

/** What a placement of the folder [folderId] is a placement of; see [Placed.identity]. */
fun folderIdentity(folderId: Long) = "folder:$folderId"

/** Pages of one container with their items, as the UI consumes them. */
data class ContainerContent(val pages: List<PageContent>)

data class PageContent(val index: Int, val items: List<ItemEntity>)

class WorkspaceRepository(private val db: RunwayDatabase) {
    internal val dao = db.workspaceDao()

    fun observe(container: Container): Flow<ContainerContent> =
        combine(dao.observePages(container), dao.observeItems(container)) { pages, items ->
            val byPage = items.groupBy { it.pageIndex }
            ContainerContent(pages.map { PageContent(it.index, byPage[it.index].orEmpty()) })
        }

    /** Adds a page at [index] if it does not exist yet. */
    suspend fun addPage(container: Container, index: Int) = write {
        dao.insertPage(PageEntity(container, index))
    }

    /**
     * Drops the empty pages after the last used one of the home screen and of the dock, in one
     * transaction; the first page of each always stays.
     */
    suspend fun pruneEmptyPages() = write { dao.dropTrailingEmptyPages() }

    /** Guarantees the first home and dock page exist. */
    suspend fun ensureInitialised() = write {
        dao.insertPage(PageEntity(Container.HOME, 0))
        dao.insertPage(PageEntity(Container.DOCK, 0))
    }

    /**
     * Moves one item and, in the same transaction, the items it displaces on the target page. The
     * plan was made from a picture of the layout that may have changed meanwhile (the page pruned,
     * a neighbour moved off it): then nothing moves, and this is false.
     */
    suspend fun moveItem(
        id: Long,
        container: Container,
        page: Int,
        x: Int,
        y: Int,
        displaced: Map<Long, Footprint>,
    ): Boolean = write {
        val pageExists = dao.pages(container).any { it.index == page }
        val neighboursThere =
            displaced.keys.all { otherId ->
                dao.item(otherId)?.let { it.container == container && it.pageIndex == page } == true
            }
        if (pageExists && neighboursThere && dao.item(id) != null) {
            // Every cell has one item at a time, and the moves can chain through each other's
            // old cells, so all movers leave their cells before any lands.
            dao.park(displaced.keys.toList() + id)
            displaced.forEach { (otherId, to) -> dao.place(otherId, container, page, to.x, to.y) }
            dao.place(id, container, page, x, y)
            true
        } else {
            false
        }
    }

    /** A new placement of [app], as dragging it out of the drawer makes. */
    suspend fun addApp(app: AppRef, container: Container, page: Int, x: Int, y: Int) = write {
        dao.insertItem(
            ItemEntity(
                kind = ItemKind.APP,
                container = container,
                pageIndex = page,
                x = x,
                y = y,
                component = app.component,
                profile = app.profile,
            )
        )
    }

    /**
     * Takes one placement off its page; a page left empty at the end goes with it, and so does a
     * folder that this was the last placement of.
     */
    suspend fun removeItem(id: Long) = write {
        dao.deleteItem(id)
        dao.deleteUnplacedFolders()
        dao.dropTrailingEmptyPages()
    }

    /**
     * Drops app placements whose app is not among [installed], within the profiles [installed]
     * covers. A profile with no app in the list is left alone: it is off (quiet mode), not empty,
     * and its icons come back with it. Catches uninstalls that happened while the launcher was not
     * running.
     */
    suspend fun retainApps(installed: Set<AppRef>) {
        val profiles = installed.mapTo(mutableSetOf()) { it.profile }
        val stale =
            dao.itemsOfKind(ItemKind.APP).filter { item ->
                val component = item.component
                val profile = item.profile
                component != null &&
                    profile != null &&
                    profile in profiles &&
                    AppRef(component, profile) !in installed
            }
        val gone = dao.folderApps().filter { it.profile in profiles && it.ref !in installed }
        if (stale.isEmpty() && gone.isEmpty()) return
        write {
            dao.deleteItems(stale.map { it.id })
            gone.forEach { dao.deleteFolderApp(it.component, it.profile) }
            dao.deleteEmptyFolders()
            dao.dropTrailingEmptyPages()
        }
    }

    /**
     * An app was uninstalled: its icons go, leaving holes, and it leaves every folder it was in.
     */
    suspend fun removePackage(packageName: String, profile: Long) = write {
        dao.deleteItemsOfPackage(packageName, profile)
        dao.deleteFolderAppsOfPackage(packageName, profile)
        dao.deleteEmptyFolders()
    }

    internal suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
}

/** Within a transaction: the empty pages after the last used one go, on home and in the dock. */
internal suspend fun WorkspaceDao.dropTrailingEmptyPages() {
    for (container in listOf(Container.HOME, Container.DOCK)) {
        val pages = pages(container)
        val keep = LayoutEngine.pageCountAfterPrune(pages.size, usedPages(container).toSet())
        pages.drop(keep).forEach { deletePage(container, it.index) }
    }
}
