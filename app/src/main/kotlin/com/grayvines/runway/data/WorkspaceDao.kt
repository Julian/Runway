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

    // An exact prefix match: LIKE would treat the underscores in package names as wildcards.
    @Query(
        "DELETE FROM items WHERE profile = :profile " +
            "AND substr(component, 1, length(:packageName) + 1) = :packageName || '/'"
    )
    suspend fun deleteItemsOfPackage(packageName: String, profile: Long)

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

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM folder_apps WHERE folder_id = :folderId")
    suspend fun nextFolderPosition(folderId: Long): Int

    @Query("SELECT * FROM folders") fun observeFolders(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query(
        "DELETE FROM folder_apps WHERE profile = :profile " +
            "AND substr(component, 1, length(:packageName) + 1) = :packageName || '/'"
    )
    suspend fun deleteFolderAppsOfPackage(packageName: String, profile: Long)

    @Query("DELETE FROM folder_apps WHERE component = :component AND profile = :profile")
    suspend fun deleteFolderApp(component: String, profile: Long)

    /** A folder with nothing in it is gone, and (by cascade) so is every placement of it. */
    @Query("DELETE FROM folders WHERE id NOT IN (SELECT DISTINCT folder_id FROM folder_apps)")
    suspend fun deleteEmptyFolders()

    /** A folder nothing places any more is gone, with its apps. */
    @Query(
        "DELETE FROM folders WHERE id NOT IN (SELECT folder_id FROM items WHERE folder_id IS NOT NULL)"
    )
    suspend fun deleteUnplacedFolders()

    @Query("DELETE FROM folders") suspend fun deleteAllFolders()

    @Query("SELECT * FROM folder_apps ORDER BY position")
    fun observeFolderApps(): Flow<List<FolderAppEntity>>

    @Query("SELECT * FROM folder_apps") suspend fun folderApps(): List<FolderAppEntity>

    @Query("SELECT * FROM items") suspend fun items(): List<ItemEntity>

    @Query("SELECT * FROM pages ORDER BY container, page_index")
    suspend fun allPages(): List<PageEntity>

    @Query("SELECT * FROM folders") suspend fun folders(): List<FolderEntity>

    @Query("DELETE FROM items") suspend fun deleteAllItems()

    @Query("DELETE FROM pages") suspend fun deleteAllPages()
}
