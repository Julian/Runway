package com.grayvines.runway.system.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.grayvines.runway.data.settings.IconShape
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Everything that determines the rendered pixels of an app icon. A change to any field (an app
 * update, a density change, a new [IconShape], or a work-profile serial) produces a distinct cache
 * entry without waiting for the old file to be evicted first.
 */
internal data class IconCacheKey(
    val packageName: String,
    val component: String,
    val profileSerial: Long,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val densityDpi: Int,
    val shape: IconShape,
    val themed: Boolean = false,
)

internal fun IconCacheKey.fileName(): String = "${sanitizePackage(packageName)}@${sha256Hex()}.png"

private fun IconCacheKey.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val payload =
        "$packageName|$component|$profileSerial|$versionCode|" +
            "$lastUpdateTime|$densityDpi|${shape.name}|$themed"
    return digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private val UNSAFE_PACKAGE_CHARS = Regex("[^a-zA-Z0-9_.]")

internal fun sanitizePackage(packageName: String): String =
    UNSAFE_PACKAGE_CHARS.replace(packageName, "_")

/**
 * On-disk cache of rendered app icons, parameterised over the image type [T] so its persistence,
 * invalidation, and pruning logic can be exercised on the JVM without Android's [BitmapFactory].
 */
internal class IconCache<T : Any>(
    private val dir: File,
    private val encode: (T, OutputStream) -> Boolean,
    private val decode: (InputStream) -> T?,
) {
    /** Whether [key] currently has a cached entry on disk. */
    fun contains(key: IconCacheKey): Boolean = File(dir, key.fileName()).isFile

    /**
     * Returns the cached value for [key] if present and readable on disk; otherwise runs [render],
     * writes the result atomically via a sibling temporary file, and returns it.
     */
    fun getOrPut(key: IconCacheKey, render: () -> T): T {
        val file = File(dir, key.fileName())
        readFromDisk(file)?.let {
            return it
        }
        val rendered = render()
        writeToDisk(file, rendered)
        return rendered
    }

    /** Deletes all cached icons belonging to [packageName]. */
    fun invalidatePackage(packageName: String) {
        val prefix = "${sanitizePackage(packageName)}@"
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) {
                file.delete()
            }
        }
    }

    /**
     * Deletes any file in the cache directory that does not belong to one of [activeKeys] (across
     * any [IconShape], so switching shapes in settings hits the cache without re-rendering).
     */
    fun retainOnly(activeKeys: Set<IconCacheKey>) {
        val keep =
            activeKeys.flatMapTo(HashSet(activeKeys.size * IconShape.entries.size)) { key ->
                IconShape.entries.map { shape -> key.copy(shape = shape).fileName() }
            }
        dir.listFiles()?.forEach { file ->
            if (file.name !in keep) {
                file.delete()
            }
        }
    }

    private fun readFromDisk(file: File): T? {
        if (!file.isFile) return null
        val decoded =
            try {
                file.inputStream().buffered().use(decode)
            } catch (_: IOException) {
                null
            }
        if (decoded == null) {
            file.delete()
        }
        return decoded
    }

    private fun writeToDisk(target: File, value: T) {
        try {
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            val wrote =
                try {
                    tmp.outputStream().buffered().use { out -> encode(value, out) }
                } catch (_: IOException) {
                    false
                }
            if (!wrote || !tmp.renameTo(target)) {
                tmp.delete()
            }
        } catch (_: IOException) {
            // Caching is an optimisation; a failed write must never break app loading.
        }
    }

    companion object {
        private const val PNG_QUALITY = 100

        fun bitmapCache(dir: File): IconCache<Bitmap> =
            IconCache(
                dir = dir,
                encode = { bitmap, out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                },
                decode = { input -> BitmapFactory.decodeStream(input) },
            )
    }
}
