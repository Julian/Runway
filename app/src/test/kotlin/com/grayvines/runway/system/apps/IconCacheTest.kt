package com.grayvines.runway.system.apps

import com.grayvines.runway.data.settings.IconShape
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IconCacheTest {
    @TempDir lateinit var tempDir: File

    private fun stringCache(dir: File = tempDir): IconCache<String> =
        IconCache(
            dir = dir,
            encode = { value, out ->
                out.write(value.toByteArray(Charsets.UTF_8))
                true
            },
            decode = { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                text.takeIf { it.startsWith("ICON:") }
            },
        )

    private fun key(
        pkg: String = "com.example.app",
        component: String = "$pkg/.MainActivity",
        profileSerial: Long = 0L,
        versionCode: Long = 1L,
        lastUpdateTime: Long = 1_000L,
        densityDpi: Int = 420,
        shape: IconShape = IconShape.SYSTEM,
        themed: Boolean = false,
    ) =
        IconCacheKey(
            packageName = pkg,
            component = component,
            profileSerial = profileSerial,
            versionCode = versionCode,
            lastUpdateTime = lastUpdateTime,
            densityDpi = densityDpi,
            shape = shape,
            themed = themed,
        )

    @Test
    fun `renders once on miss and reads from disk on subsequent hits`() {
        val cache = stringCache()
        val k = key()
        var renders = 0

        val first = cache.getOrPut(k) { "ICON:${++renders}" }
        val second = cache.getOrPut(k) { "ICON:${++renders}" }

        assertEquals("ICON:1", first)
        assertEquals("ICON:1", second)
        assertEquals(1, renders)
    }

    @Test
    fun `distinct keys for shape, version, density, or profile do not collide`() {
        val cache = stringCache()
        val base = key()
        val variants =
            listOf(
                base,
                base.copy(shape = IconShape.SQUIRCLE),
                base.copy(versionCode = 2L),
                base.copy(lastUpdateTime = 2_000L),
                base.copy(densityDpi = 480),
                base.copy(profileSerial = 10L),
                base.copy(themed = true),
            )

        variants.forEachIndexed { idx, variant ->
            assertEquals("ICON:$idx", cache.getOrPut(variant) { "ICON:$idx" })
        }
        variants.forEachIndexed { idx, variant ->
            assertEquals(
                "ICON:$idx",
                cache.getOrPut(variant) { error("unexpected miss for $variant") },
            )
        }
    }

    @Test
    fun `invalidatePackage removes only that package and spares prefix neighbors`() {
        val cache = stringCache()
        val targetA = key(pkg = "com.example.foo", shape = IconShape.CIRCLE)
        val targetB = key(pkg = "com.example.foo", shape = IconShape.SQUIRCLE)
        val neighborDot = key(pkg = "com.example.foo.bar")
        val neighborUnderscore = key(pkg = "com.example.foo_bar")

        cache.getOrPut(targetA) { "ICON:A" }
        cache.getOrPut(targetB) { "ICON:B" }
        cache.getOrPut(neighborDot) { "ICON:DOT" }
        cache.getOrPut(neighborUnderscore) { "ICON:UNDERSCORE" }

        cache.invalidatePackage("com.example.foo")

        assertFalse(File(tempDir, targetA.fileName()).exists())
        assertFalse(File(tempDir, targetB.fileName()).exists())
        assertTrue(File(tempDir, neighborDot.fileName()).exists())
        assertTrue(File(tempDir, neighborUnderscore.fileName()).exists())
    }

    @Test
    fun `retainOnly prunes uninstalled or stale entries while keeping other shapes of active keys`() {
        val cache = stringCache()
        val keepSystem = key(pkg = "com.example.keep", shape = IconShape.SYSTEM)
        val keepSquircle = keepSystem.copy(shape = IconShape.SQUIRCLE)
        val staleVersion = keepSystem.copy(versionCode = 99L)
        val dropKey = key(pkg = "com.example.drop")

        cache.getOrPut(keepSystem) { "ICON:keep-system" }
        cache.getOrPut(keepSquircle) { "ICON:keep-squircle" }
        cache.getOrPut(staleVersion) { "ICON:stale-version" }
        cache.getOrPut(dropKey) { "ICON:drop" }

        cache.retainOnly(setOf(keepSystem))

        assertTrue(cache.contains(keepSystem))
        assertTrue(cache.contains(keepSquircle))
        assertFalse(cache.contains(staleVersion))
        assertFalse(cache.contains(dropKey))
    }

    @Test
    fun `corrupt file on disk is discarded and re-rendered`() {
        val cache = stringCache()
        val k = key()
        val file = File(tempDir, k.fileName())
        file.writeText("CORRUPT")

        val value = cache.getOrPut(k) { "ICON:fresh" }
        assertEquals("ICON:fresh", value)
        assertEquals("ICON:fresh", file.readText())
    }
}
