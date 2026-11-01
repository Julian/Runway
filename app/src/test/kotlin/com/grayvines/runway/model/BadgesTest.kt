package com.grayvines.runway.model

import com.grayvines.runway.data.AppRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BadgesTest {
    @Test
    fun `group summaries, ongoing notifications and media sessions do not count`() {
        val notifications =
            listOf(
                ActiveNotification("a", profile = 0, number = 5, isGroupSummary = true),
                ActiveNotification("b", profile = 0, number = 3, isOngoing = true),
                ActiveNotification("c", profile = 0, number = 2, isMedia = true),
            )
        assertEquals(emptyMap<PackageKey, Int>(), badgeCounts(notifications))
    }

    @Test
    fun `each qualifying notification adds at least one, or its number, per package and profile`() {
        val notifications =
            listOf(
                ActiveNotification("chat", profile = 0, number = 0),
                ActiveNotification("chat", profile = 0, number = -2),
                ActiveNotification("chat", profile = 0, number = 4),
                ActiveNotification("chat", profile = 0, number = 10, isGroupSummary = true),
                ActiveNotification("chat", profile = 10, number = 2),
                ActiveNotification("mail", profile = 0, number = 1),
            )
        assertEquals(
            mapOf(
                PackageKey("chat", 0) to 6,
                PackageKey("chat", 10) to 2,
                PackageKey("mail", 0) to 1,
            ),
            badgeCounts(notifications),
        )
    }

    @Test
    fun `an app takes its package's count in its profile unless muted`() {
        val chat = AppRef("chat/.Main", 0)
        val chatWork = AppRef("chat/.Main", 10)
        val other = AppRef("other/.Main", 0)
        val counts = mapOf(PackageKey("chat", 0) to 5, PackageKey("chat", 10) to 2)

        assertEquals(5, badgeCountForApp(chat, counts))
        assertEquals(2, badgeCountForApp(chatWork, counts))
        assertEquals(0, badgeCountForApp(other, counts))
        assertEquals(0, badgeCountForApp(chat, counts, muted = setOf(chat)))
        assertEquals(2, badgeCountForApp(chatWork, counts, muted = setOf(chat)))
    }

    @Test
    fun `a folder's count is the sum of its unmuted apps' counts`() {
        val a = AppRef("a/.Main", 0)
        val b = AppRef("b/.Main", 0)
        val c = AppRef("c/.Main", 10)
        val counts =
            mapOf(
                PackageKey("a", 0) to 3,
                PackageKey("b", 0) to 4,
                PackageKey("c", 10) to 2,
            )

        assertEquals(9, badgeCountForFolder(listOf(a, b, c), counts))
        assertEquals(5, badgeCountForFolder(listOf(a, b, c), counts, muted = setOf(b)))
        assertEquals(0, badgeCountForFolder(listOf(a, b, c), counts, muted = setOf(a, b, c)))
    }

    @Test
    fun `formatBadgeCount caps above 99 and is empty when not positive`() {
        assertEquals("", formatBadgeCount(-1))
        assertEquals("", formatBadgeCount(0))
        assertEquals("1", formatBadgeCount(1))
        assertEquals("99", formatBadgeCount(99))
        assertEquals("99+", formatBadgeCount(100))
        assertEquals("99+", formatBadgeCount(500))
    }
}
