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
import com.grayvines.runway.system.widgets.Widgets
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.WorkspaceLookup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private fun provider(targetW: Int = 1, targetH: Int = 1, needsSetup: Boolean = false) =
        WidgetProvider(
            providerName = "pkg/.Widget",
            targetCellWidth = targetW,
            targetCellHeight = targetH,
            needsSetup = needsSetup,
        )
}
