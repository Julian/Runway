package com.grayvines.runway.data

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.grayvines.runway.model.Footprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** An app identity as stored: flattened component name plus profile serial. */
data class AppRef(val component: String, val profile: Long)

/** Pages of one container with their items, as the UI consumes them. */
data class ContainerContent(val pages: List<PageContent>)

data class PageContent(val index: Int, val items: List<ItemEntity>)

class WorkspaceRepository(private val db: RunwayDatabase) {
    private val dao = db.workspaceDao()

    fun observe(container: Container): Flow<ContainerContent> =
        combine(dao.observePages(container), dao.observeItems(container)) { pages, items ->
            val byPage = items.groupBy { it.pageIndex }
            ContainerContent(pages.map { PageContent(it.index, byPage[it.index].orEmpty()) })
        }

    /** Adds a page at [index] if it does not exist yet. */
    suspend fun addPage(container: Container, index: Int) = write {
        dao.insertPage(PageEntity(container, index))
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
        displaced.forEach { (otherId, to) -> dao.place(otherId, container, page, to.x, to.y) }
        dao.place(id, container, page, x, y)
    }

    /** An app was uninstalled: its icons go, leaving holes. */
    suspend fun removePackage(packageName: String, profile: Long) = write {
        dao.deleteItemsOfPackage(packageName, profile)
    }

    suspend fun clear() = write {
        dao.deleteAllItems()
        dao.deleteAllPages()
        dao.insertPage(PageEntity(Container.HOME, 0))
        dao.insertPage(PageEntity(Container.DOCK, 0))
    }

    /** Debug helper: fills the dock, then home pages row by row, with [apps] in order. */
    suspend fun autoFill(apps: List<AppRef>, columns: Int, pageRows: Int, dockSlots: Int) = write {
        dao.deleteAllItems()
        dao.deleteAllPages()
        dao.insertPage(PageEntity(Container.DOCK, 0))
        apps.take(dockSlots).forEachIndexed { slot, app ->
            dao.insertItem(app.placement(Container.DOCK, page = 0, x = slot, y = 0))
        }
        val perPage = columns * pageRows
        apps.drop(dockSlots).chunked(perPage).forEachIndexed { page, pageApps ->
            dao.insertPage(PageEntity(Container.HOME, page))
            pageApps.forEachIndexed { i, app ->
                dao.insertItem(
                    app.placement(Container.HOME, page, x = i % columns, y = i / columns)
                )
            }
        }
        dao.insertPage(PageEntity(Container.HOME, 0))
    }

    private fun AppRef.placement(container: Container, page: Int, x: Int, y: Int) =
        ItemEntity(
            kind = ItemKind.APP,
            container = container,
            pageIndex = page,
            x = x,
            y = y,
            component = component,
            profile = profile,
        )

    private suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
}
