package com.grayvines.runway.data

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.LayoutEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** An app identity as stored: flattened component name plus profile serial. */
data class AppRef(val component: String, val profile: Long)

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

    /** Drops empty pages after the last used one; the first page always stays. */
    suspend fun pruneTrailingEmptyPages(container: Container) = write {
        val pages = dao.pages(container)
        val keep = LayoutEngine.pageCountAfterPrune(pages.size, dao.usedPages(container).toSet())
        pages.drop(keep).forEach { dao.deletePage(container, it.index) }
    }

    /** Guarantees the first home and dock page exist. */
    suspend fun ensureInitialised() = write {
        dao.insertPage(PageEntity(Container.HOME, 0))
        dao.insertPage(PageEntity(Container.DOCK, 0))
    }

    /** Moves one item and, in the same transaction, the items it displaces on the target page. */
    suspend fun moveItem(
        id: Long,
        container: Container,
        page: Int,
        x: Int,
        y: Int,
        displaced: Map<Long, Footprint>,
    ) = write {
        // Every cell has one item at a time, and the moves can chain through each other's old
        // cells, so all movers leave their cells before any lands.
        dao.park(displaced.keys.toList() + id)
        displaced.forEach { (otherId, to) -> dao.place(otherId, container, page, to.x, to.y) }
        dao.place(id, container, page, x, y)
    }

    /** Takes one placement off its page; a page left empty at the end goes with it. */
    suspend fun removeItem(id: Long) {
        write { dao.deleteItem(id) }
        pruneTrailingEmptyPages(Container.HOME)
        pruneTrailingEmptyPages(Container.DOCK)
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
        if (stale.isEmpty()) return
        write { dao.deleteItems(stale.map { it.id }) }
        pruneTrailingEmptyPages(Container.HOME)
        pruneTrailingEmptyPages(Container.DOCK)
    }

    /** An app was uninstalled: its icons go, leaving holes. */
    suspend fun removePackage(packageName: String, profile: Long) = write {
        dao.deleteItemsOfPackage(packageName, profile)
    }

    internal suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
}
