package com.grayvines.runway.ui.widgets

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.RunwayDatabase
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.widgets.WidgetProvider
import com.grayvines.runway.system.widgets.WidgetProviderCache
import com.grayvines.runway.system.widgets.Widgets
import com.grayvines.runway.system.widgets.parseWidgetRemap
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.WorkspaceLookup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetPickerHostTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val workspace = WorkspaceRepository(db)

    private var gridSize = GridSize(4, 4)
    private var homeOccupied = emptyList<Placed>()
    private val lookup =
        object : WorkspaceLookup {
            override val grid: GridSize
                get() = gridSize

            override val dockSlots: Int = 4

            override fun homeItems(page: Int): List<Placed> = homeOccupied

            override fun dockItems(page: Int): List<Placed> = emptyList()
        }

    private var nextId = 100
    private var allowImmediateBind = true
    private val allocated = mutableListOf<Int>()
    private val deleted = mutableListOf<Int>()
    private val widgets =
        object : Widgets {
            override fun allocateId(): Int = (++nextId).also(allocated::add)

            override fun deleteId(id: Int) {
                deleted += id
            }

            override fun bind(id: Int, provider: WidgetProvider): Boolean = allowImmediateBind

            override fun providers(): List<WidgetProvider> = listOf(provider())
        }

    @AfterEach fun tearDown() = db.close()

    @Test
    fun `open loads providers when allowed and dismiss clears them`() = runTest {
        var allowed = false
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = widgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
                mayOpen = { allowed },
            )

        host.open()
        assertFalse(host.isOpen)

        allowed = true
        host.open()
        assertTrue(host.isOpen)
        assertEquals(listOf(provider()), host.providers.first { it != null })

        host.dismiss()
        assertFalse(host.isOpen)
        assertNull(host.providers.value)
    }

    @Test
    fun `pick allocates and binds an id, stores the widget on the shown page, and notifies onPlaced`() =
        runTest {
            workspace.ensureInitialised()
            val placed = mutableListOf<Long>()
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = widgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                    onPlaced = placed::add,
                )

            // A 3x1 target span does not fit in a 2x1 grid, so pick falls back to the 1x1 minimum.
            gridSize = GridSize(2, 1)
            host.pick(provider(targetW = 3, targetH = 1), cellWidthDp = 60f, cellHeightDp = 60f)
            val snapshot =
                workspace.observeSnapshot().first { it.home.pages.single().items.isNotEmpty() }
            val item = snapshot.home.pages.single().items.single()
            assertEquals(101, item.appWidgetId)
            assertEquals(Footprint(0, 0, 1, 1), item.footprint)
            assertEquals(listOf(item.id), placed)
            assertEquals(emptyList<Int>(), deleted)
        }

    @Test
    fun `pick emits a notice and allocates no id when the page has no room`() = runTest {
        workspace.ensureInitialised()
        gridSize = GridSize(1, 1)
        homeOccupied = listOf(Placed(1L, Footprint(0, 0)))
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = widgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
            )

        host.pick(provider(), cellWidthDp = 60f, cellHeightDp = 60f)
        assertEquals("No room on this page", host.notices.first())
        assertEquals(emptyList<Int>(), allocated)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `place asks the bind prompt when immediate bind is refused, and deletes the id if denied`() =
        runTest {
            workspace.ensureInitialised()
            allowImmediateBind = false
            var askedBind = 0
            val chosen = provider()
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = widgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )
            val notices = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                host.notices.collect(notices::add)
            }
            host.prompts =
                object : WidgetPrompts {
                    override suspend fun requestBind(id: Int, provider: WidgetProvider): Boolean {
                        askedBind++
                        assertSame(chosen, provider)
                        return false
                    }

                    override suspend fun configure(id: Int): Boolean = true
                }

            val move =
                PendingMove(
                    itemId = 0,
                    container = Container.HOME,
                    page = 0,
                    x = 0,
                    y = 0,
                    displaced = emptyMap(),
                    spanX = 2,
                    spanY = 1,
                    newWidget = chosen,
                )
            assertFalse(host.place(move))
            assertEquals(1, askedBind)
            assertEquals(listOf(101), allocated)
            assertEquals(listOf(101), deleted)
            assertEquals(listOf("Runway was not allowed to add widgets"), notices)
        }

    @Test
    fun `place succeeds after bind prompt and setup screen both succeed`() = runTest {
        workspace.ensureInitialised()
        allowImmediateBind = false
        val placed = mutableListOf<Long>()
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = widgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
                onPlaced = placed::add,
            )
        var configuredId = -1
        host.prompts =
            object : WidgetPrompts {
                override suspend fun requestBind(id: Int, provider: WidgetProvider): Boolean = true

                override suspend fun configure(id: Int): Boolean {
                    configuredId = id
                    return true
                }
            }

        val move =
            PendingMove(
                itemId = 0,
                container = Container.HOME,
                page = 0,
                x = 1,
                y = 2,
                displaced = emptyMap(),
                spanX = 2,
                spanY = 1,
                newWidget = provider(needsSetup = true),
            )
        assertTrue(host.place(move))
        assertEquals(101, configuredId)
        assertEquals(1, placed.size)
        assertEquals(emptyList<Int>(), deleted)
    }

    @Test
    fun `place deletes the id without a notice when the widget's setup screen is cancelled`() =
        runTest {
            workspace.ensureInitialised()
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = widgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )
            host.prompts =
                object : WidgetPrompts {
                    override suspend fun requestBind(id: Int, provider: WidgetProvider): Boolean =
                        true

                    override suspend fun configure(id: Int): Boolean = false
                }

            val move =
                PendingMove(
                    itemId = 0,
                    container = Container.HOME,
                    page = 0,
                    x = 0,
                    y = 0,
                    displaced = emptyMap(),
                    newWidget = provider(needsSetup = true),
                )
            assertFalse(host.place(move))
            assertEquals(listOf(101), allocated)
            assertEquals(listOf(101), deleted)
        }

    @Test
    fun `place deletes the allocated id when the layout rejects the placement`() = runTest {
        workspace.ensureInitialised()
        val placed = mutableListOf<Long>()
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = widgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
                onPlaced = placed::add,
            )

        // Page 9 does not exist, so addWidget returns null and the allocated id goes back.
        val move =
            PendingMove(
                itemId = 0,
                container = Container.HOME,
                page = 9,
                x = 0,
                y = 0,
                displaced = emptyMap(),
                newWidget = provider(),
            )
        assertFalse(host.place(move))
        assertEquals(listOf(101), allocated)
        assertEquals(listOf(101), deleted)
        assertEquals(emptyList<Long>(), placed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dismissing before providers finish loading does not repopulate providers`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowWidgets =
            object : Widgets by widgets {
                override fun providers(): List<WidgetProvider> {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return listOf(provider())
                }
            }
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = slowWidgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
            )

        host.open()
        runCurrent()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        host.dismiss()
        release.countDown()
        advanceUntilIdle()

        assertFalse(host.isOpen)
        assertNull(host.providers.value)
    }

    @Test
    fun `open finishes with an empty list instead of spinning forever when provider loading fails`() =
        runTest {
            val failingWidgets =
                object : Widgets by widgets {
                    override fun providers(): List<WidgetProvider> = error("binder dead")
                }
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = failingWidgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )

            host.open()
            assertEquals(emptyList<WidgetProvider>(), host.providers.first { it != null })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling while suspended in a bind prompt releases the allocated widget id`() = runTest {
        workspace.ensureInitialised()
        allowImmediateBind = false
        val promptStarted = CompletableDeferred<Unit>()
        val host =
            WidgetPickerHost(
                workspace = workspace,
                widgets = widgets,
                scope = backgroundScope,
                lookup = lookup,
                shownPage = { 0 },
            )
        host.prompts =
            object : WidgetPrompts {
                override suspend fun requestBind(id: Int, provider: WidgetProvider): Boolean {
                    promptStarted.complete(Unit)
                    CompletableDeferred<Boolean>().await()
                    return false
                }

                override suspend fun configure(id: Int): Boolean = true
            }

        val move =
            PendingMove(
                itemId = 0,
                container = Container.HOME,
                page = 0,
                x = 0,
                y = 0,
                displaced = emptyMap(),
                newWidget = provider(),
            )
        val job = launch { host.place(move) }
        promptStarted.await()
        job.cancel()
        advanceUntilIdle()

        assertEquals(listOf(101), allocated)
        assertEquals(listOf(101), deleted)
    }

    @Test
    fun `place returns false without throwing when allocateId or a prompt throws RuntimeException`() =
        runTest {
            workspace.ensureInitialised()
            val move =
                PendingMove(
                    itemId = 0,
                    container = Container.HOME,
                    page = 0,
                    x = 0,
                    y = 0,
                    displaced = emptyMap(),
                    newWidget = provider(),
                )

            val failingAllocHost =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets =
                        object : Widgets by widgets {
                            override fun allocateId(): Int = error("host dead")
                        },
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )
            assertFalse(failingAllocHost.place(move))

            allowImmediateBind = false
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = widgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )
            host.prompts =
                object : WidgetPrompts {
                    override suspend fun requestBind(id: Int, provider: WidgetProvider): Boolean =
                        error("prompt crashed")

                    override suspend fun configure(id: Int): Boolean = true
                }
            assertFalse(host.place(move))
            assertEquals(listOf(101), allocated)
            assertEquals(listOf(101), deleted)
        }

    @Test
    fun `open uses cachedProviders immediately without reloading, and dismiss still clears flow`() =
        runTest {
            var loads = 0
            val cached = listOf(provider())
            val cachedWidgets =
                object : Widgets by widgets {
                    override val cachedProviders: List<WidgetProvider> = cached

                    override fun providers(): List<WidgetProvider> {
                        loads++
                        return cached
                    }
                }
            val host =
                WidgetPickerHost(
                    workspace = workspace,
                    widgets = cachedWidgets,
                    scope = backgroundScope,
                    lookup = lookup,
                    shownPage = { 0 },
                )

            host.open()
            assertEquals(cached, host.providers.value)
            assertEquals(0, loads)

            host.dismiss()
            assertFalse(host.isOpen)
            assertNull(host.providers.value)
        }

    @Test
    fun `WidgetProviderCache caches loads, ignores null failures, and rejects stale concurrent loads`() {
        val cache = WidgetProviderCache()
        assertNull(cache.peek())

        // Transient failure returns empty and does not poison the cache.
        assertEquals(emptyList<WidgetProvider>(), cache.getOrLoad { null })
        assertNull(cache.peek())

        // First successful load populates the cache; second call hits cache without reloading.
        var loads = 0
        val first = listOf(provider(targetW = 2))
        assertEquals(
            first,
            cache.getOrLoad {
                loads++
                first
            },
        )
        assertEquals(first, cache.peek())
        assertEquals(
            first,
            cache.getOrLoad {
                loads++
                emptyList()
            },
        )
        assertEquals(1, loads)

        // Invalidation clears cache; an invalidation mid-load prevents the stale load from caching.
        cache.invalidate()
        assertNull(cache.peek())
        val stale = listOf(provider(targetW = 3))
        assertEquals(
            stale,
            cache.getOrLoad {
                loads++
                cache.invalidate()
                stale
            },
        )
        assertNull(cache.peek())
        assertEquals(2, loads)

        // Subsequent load after the racing invalidation caches cleanly.
        val fresh = listOf(provider(targetW = 4))
        assertEquals(
            fresh,
            cache.getOrLoad {
                loads++
                fresh
            },
        )
        assertEquals(fresh, cache.peek())
        assertEquals(3, loads)
    }

    @Test
    fun `parseWidgetRemap drops invalid new ids and returns orphan or superseded valid new ids`() {
        val (remapped, extraUnused) =
            parseWidgetRemap(
                oldIds = intArrayOf(0, -1, 10, 10, 20),
                newIds = intArrayOf(50, 60, 100, 101, 0),
            )
        assertEquals(mapOf(10 to 101), remapped)
        assertEquals(setOf(50, 60, 100), extraUnused)
    }

    private fun provider(targetW: Int = 1, targetH: Int = 1, needsSetup: Boolean = false) =
        WidgetProvider(
            providerName = "pkg/.Widget",
            targetCellWidth = targetW,
            targetCellHeight = targetH,
            needsSetup = needsSetup,
        )
}
