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

    @Query("DELETE FROM items") suspend fun deleteAllItems()

    @Query("DELETE FROM pages") suspend fun deleteAllPages()
}
