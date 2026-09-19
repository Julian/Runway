package com.grayvines.runway.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM pages WHERE container = :container ORDER BY page_index")
    fun observePages(container: Container): Flow<List<PageEntity>>

    @Query("SELECT * FROM items WHERE container = :container")
    fun observeItems(container: Container): Flow<List<ItemEntity>>

    @Query("SELECT * FROM pages WHERE container = :container ORDER BY page_index")
    suspend fun pages(container: Container): List<PageEntity>

    @Query("SELECT DISTINCT page_index FROM items WHERE container = :container")
    suspend fun usedPages(container: Container): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPage(page: PageEntity)

    @Query("DELETE FROM pages WHERE container = :container AND page_index = :index")
    suspend fun deletePage(container: Container, index: Int)

    @Insert suspend fun insertItem(item: ItemEntity): Long

    @Update suspend fun updateItem(item: ItemEntity)

    @Query(
        "UPDATE items SET container = :container, page_index = :page, x = :x, y = :y WHERE id = :id"
    )
    suspend fun place(id: Long, container: Container, page: Int, x: Int, y: Int)

    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItem(id: Long)

    @Query("SELECT * FROM items WHERE kind = :kind")
    suspend fun itemsOfKind(kind: ItemKind): List<ItemEntity>

    @Query("DELETE FROM items WHERE id IN (:ids)") suspend fun deleteItems(ids: List<Long>)

    /** Lifts items off their cells so they can be re-placed without colliding on the way. */
    @Query("UPDATE items SET page_index = NULL, x = NULL, y = NULL WHERE id IN (:ids)")
    suspend fun park(ids: List<Long>)

    @Query("SELECT * FROM items WHERE id = :id") suspend fun item(id: Long): ItemEntity?

    @Insert suspend fun insertFolder(folder: FolderEntity): Long

    /** An app already in the folder stays where it was. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderApp(app: FolderAppEntity)

    /**
     * Where an app joining the folder goes: after the last hand-placed one, or nowhere in
     * particular (null) while no hand has ordered this folder.
     */
    @Query("SELECT MAX(position) + 1 FROM folder_apps WHERE folder_id = :folderId")
    suspend fun nextFolderPosition(folderId: Long): Int?

    @Query(
        "UPDATE folder_apps SET position = :position " +
            "WHERE folder_id = :folderId AND component = :component AND profile = :profile"
    )
    suspend fun setFolderPosition(folderId: Long, component: String, profile: Long, position: Int)

    @Query("SELECT * FROM folders") fun observeFolders(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("DELETE FROM folder_apps WHERE component = :component AND profile = :profile")
    suspend fun deleteFolderApp(component: String, profile: Long)

    @Query(
        "DELETE FROM folder_apps WHERE folder_id = :folderId " +
            "AND component = :component AND profile = :profile"
    )
    suspend fun removeFromFolder(folderId: Long, component: String, profile: Long)

    /** An app is in at most one drawer folder: this takes it out of whichever it is in. */
    @Query(
        "DELETE FROM folder_apps WHERE component = :component AND profile = :profile " +
            "AND folder_id IN (SELECT folder_id FROM items WHERE container = 'DRAWER')"
    )
    suspend fun leaveDrawerFolders(component: String, profile: Long)

    /** Whether the folder has a placement in [container]: in the drawer, say. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM items WHERE folder_id = :folderId AND container = :container)"
    )
    suspend fun isPlacedIn(folderId: Long, container: Container): Boolean

    /** The folder, its apps and (by cascade) every placement of it. */
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: Long)

    /** A folder with nothing in it is gone, and (by cascade) so is every placement of it. */
    @Query("DELETE FROM folders WHERE id NOT IN (SELECT DISTINCT folder_id FROM folder_apps)")
    suspend fun deleteEmptyFolders()

    /** A folder nothing places any more is gone, with its apps. */
    @Query(
        "DELETE FROM folders WHERE id NOT IN (SELECT folder_id FROM items WHERE folder_id IS NOT NULL)"
    )
    suspend fun deleteUnplacedFolders()

    @Query("DELETE FROM folders") suspend fun deleteAllFolders()

    /** Hand-placed apps in their order, then the rest; see [FolderAppEntity.position]. */
    @Query("SELECT * FROM folder_apps $FOLDER_APP_ORDER")
    fun observeFolderApps(): Flow<List<FolderAppEntity>>

    @Query("SELECT * FROM folder_apps WHERE folder_id = :folderId $FOLDER_APP_ORDER")
    suspend fun folderApps(folderId: Long): List<FolderAppEntity>

    @Query("SELECT * FROM folder_apps $FOLDER_APP_ORDER")
    suspend fun folderApps(): List<FolderAppEntity>

    @Query("SELECT * FROM items") suspend fun items(): List<ItemEntity>

    @Query("SELECT * FROM pages ORDER BY container, page_index")
    suspend fun allPages(): List<PageEntity>

    @Query("SELECT * FROM folders") suspend fun folders(): List<FolderEntity>

    @Query("DELETE FROM items") suspend fun deleteAllItems()

    @Query("DELETE FROM items WHERE kind != :kind")
    suspend fun deleteItemsExceptKind(kind: ItemKind)

    @Query("DELETE FROM pages") suspend fun deleteAllPages()

    @Query("SELECT * FROM hidden_apps") fun observeHiddenApps(): Flow<List<HiddenAppEntity>>

    @Query("SELECT * FROM hidden_apps") suspend fun hiddenApps(): List<HiddenAppEntity>

    /** An app already hidden stays hidden. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHiddenApp(app: HiddenAppEntity)

    @Query("DELETE FROM hidden_apps WHERE component = :component AND profile = :profile")
    suspend fun deleteHiddenApp(component: String, profile: Long)

    @Query("DELETE FROM hidden_apps") suspend fun deleteAllHiddenApps()
}

/**
 * The order a folder holds its apps in; the rest, which no hand placed, sort so a file is stable.
 */
private const val FOLDER_APP_ORDER = "ORDER BY position IS NULL, position, component, profile"
