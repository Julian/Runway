package com.grayvines.runway.data

import androidx.room3.deferredTransaction
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.model.Placed
import com.grayvines.runway.model.overlaps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

/** An app identity as stored: flattened component name plus profile serial. */
@Serializable
data class AppRef(val component: String, val profile: Long) : java.io.Serializable {
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
        combine(dao.observePages(container), dao.observeItems(container)) { _, _ ->
                read {
                    val pages = dao.pages(container)
                    val byPage =
                        dao.items().filter { it.container == container }.groupBy { it.pageIndex }
                    ContainerContent(
                        pages.map { PageContent(it.index, byPage[it.index].orEmpty()) }
                    )
                }
            }
            .distinctUntilChanged()

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
    ): Boolean = write { relocate(id, container, page, x, y, displaced) }

    /** Inside a write: [moveItem]'s move, false and nothing moved when it cannot be made. */
    internal suspend fun relocate(
        id: Long,
        container: Container,
        page: Int,
        x: Int,
        y: Int,
        displaced: Map<Long, Footprint>,
    ): Boolean {
        val item = dao.item(id) ?: return false
        if (item.kind == ItemKind.WIDGET && container != Container.HOME) return false
        val target = Footprint(x, y, item.spanX, item.spanY)
        if (!makeRoom(container, page, displaced, mover = id, target = target)) return false
        dao.place(id, container, page, x, y)
        return true
    }

    /**
     * Inside a write: moves the [displaced] neighbours on [page] to their new cells, first taking
     * them (and [mover], if it is a placement) off their cells, since the moves can chain through
     * each other's old cells. False, and nothing moved, if the page is gone, a neighbour has left
     * it, or [target] or a displaced neighbour would land on negative coordinates or overlap
     * anything left on the page: the plan was made from a picture of the layout that has changed
     * since.
     */
    internal suspend fun makeRoom(
        container: Container,
        page: Int,
        displaced: Map<Long, Footprint>,
        mover: Long? = null,
        target: Footprint? = null,
    ): Boolean {
        val pageExists = dao.pages(container).any { it.index == page }
        if (!pageExists || mover in displaced) return false
        val onPage =
            dao.items().filter {
                it.container == container && it.pageIndex == page && it.id != mover
            }
        val byId = onPage.associateBy { it.id }
        if (!displaced.keys.all { it in byId }) return false
        val stayed = onPage.filter { it.id !in displaced }.mapNotNull { it.footprint() }
        val landing =
            displaced.map { (otherId, to) ->
                val other = byId.getValue(otherId)
                Footprint(to.x, to.y, other.spanX, other.spanY)
            } + listOfNotNull(target)
        if (!landing.fitsAmong(stayed, container)) return false
        dao.park(displaced.keys.toList() + listOfNotNull(mover))
        displaced.forEach { (otherId, to) -> dao.place(otherId, container, page, to.x, to.y) }
        return true
    }

    /**
     * A new placement of [app], as dragging it out of the drawer makes, after moving aside the
     * neighbours it [displaced]; false, and nothing changed, if they could not be moved.
     */
    suspend fun addApp(
        app: AppRef,
        container: Container,
        page: Int,
        x: Int,
        y: Int,
        displaced: Map<Long, Footprint> = emptyMap(),
    ): Boolean = write {
        if (!makeRoom(container, page, displaced, target = Footprint(x, y))) {
            false
        } else {
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
            true
        }
    }

    /**
     * Takes one placement off its page and returns what it was; a page left empty at the end goes
     * with it, and so does a folder that this was the last placement of.
     */
    suspend fun removeItem(id: Long): ItemEntity? = write {
        val removed = dao.item(id)
        dao.deleteItem(id)
        dao.deleteUnplacedFolders()
        dao.dropTrailingEmptyPages()
        removed
    }

    /**
     * Forgets apps not among [installed], within the profiles [installed] covers: their placements,
     * their places in folders, that they were hidden, and that their badges were muted. A profile
     * with no app in the list is left alone: it is off (quiet mode), not empty, and its icons come
     * back with it. Catches uninstalls that happened while the launcher was not running.
     */
    suspend fun retainApps(installed: Set<AppRef>) = write {
        // Read and deleted in the one transaction: a fold landing between the two could turn a
        // stale app row into a folder row of the same id, which must not then be deleted.
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
        if (stale.isNotEmpty() || gone.isNotEmpty()) {
            dao.deleteItems(stale.map { it.id })
            gone.forEach { dao.deleteFolderApp(it.component, it.profile) }
            dao.deleteEmptyFolders()
            dao.dropTrailingEmptyPages()
        }
        dao.hiddenApps()
            .filter { it.profile in profiles && it.ref !in installed }
            .forEach { dao.deleteHiddenApp(it.component, it.profile) }
        dao.badgeMutes()
            .filter { it.profile in profiles && it.ref !in installed }
            .forEach { dao.deleteBadgeMute(it.component, it.profile) }
    }

    internal suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }

    /** Several reads as one snapshot: a write landing between them cannot show through. */
    internal suspend fun <T> read(block: suspend () -> T): T =
        db.useReaderConnection { transactor ->
            transactor.deferredTransaction { block() }
        }

    /**
     * Every box has non-negative cells, a positive span, and overlaps neither [existing] nor
     * another; dock placements must also sit in the single dock row (`y == 0 && height == 1`).
     */
    private fun List<Footprint>.fitsAmong(
        existing: List<Footprint>,
        container: Container,
    ): Boolean {
        val occupied = existing.toMutableList()
        for (box in this) {
            val negative = box.x < 0 || box.y < 0 || box.width < 1 || box.height < 1
            val offDock = container == Container.DOCK && (box.y != 0 || box.height != 1)
            if (negative || offDock || occupied.any { it.overlaps(box) }) return false
            occupied += box
        }
        return true
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

internal fun ItemEntity.footprint(): Footprint? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    return Footprint(cellX, cellY, spanX, spanY)
}
