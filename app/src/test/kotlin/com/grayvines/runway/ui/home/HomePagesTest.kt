package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.PageContent
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomePagesTest {
    private fun app(id: Long, x: Int, y: Int) =
        ItemEntity(
            id,
            ItemKind.APP,
            Container.HOME,
            0,
            x,
            y,
            component = "pkg$id/.Main",
            profile = 0,
        )

    @Test
    fun `an item whose app is unavailable is not drawn but still occupies its cell`() {
        val content = ContainerContent(listOf(PageContent(0, listOf(app(1, 0, 0), app(2, 1, 0)))))
        val pages = content.toHomePages(apps = emptyMap())
        assertEquals(emptyList<HomeItem>(), pages.single().items)
        assertEquals(
            listOf(Placed(1, Footprint(0, 0)), Placed(2, Footprint(1, 0))),
            pages.single().occupied,
        )
    }

    @Test
    fun `an item without a cell is neither drawn nor an occupant`() {
        val loose = ItemEntity(3, ItemKind.FOLDER, Container.DRAWER)
        val pages = ContainerContent(listOf(PageContent(0, listOf(loose)))).toHomePages(emptyMap())
        assertEquals(emptyList<Placed>(), pages.single().occupied)
    }
}
