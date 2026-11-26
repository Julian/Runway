package com.grayvines.runway.system.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.grayvines.runway.data.settings.IconShape
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat

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
) {
    /** Computed once per key so `contains`, `getOrPut`, and `retainOnly` do not re-hash it. */
    val cachedFileName: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            "${sanitizePackage(packageName)}@${sha256Hex()}.png"
        }
}

internal fun IconCacheKey.fileName(): String = cachedFileName

private val HEX: HexFormat = HexFormat.of()

internal fun IconCacheKey.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val payload =
        "$packageName|$component|$profileSerial|$versionCode|" +
            "$lastUpdateTime|$densityDpi|${shape.name}|$themed"
    return HEX.formatHex(digest.digest(payload.toByteArray(Charsets.UTF_8)))
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
    private var lastRetainedKeys: Set<IconCacheKey>? = null
    private var lastKeepNames: Set<String> = emptySet()

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
    fun invalidatePackage(packageName: String) = invalidatePackages(listOf(packageName))

    /**
     * Deletes all cached icons belonging to any package in [packageNames] in one directory scan.
     */
    fun invalidatePackages(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        val prefixes = packageNames.mapTo(HashSet(packageNames.size)) { "${sanitizePackage(it)}@" }
        dir.listFiles()?.forEach { file ->
            val name = file.name
            val at = name.indexOf('@')
            if (at > 0 && name.substring(0, at + 1) in prefixes) {
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
            if (activeKeys == lastRetainedKeys) {
                lastKeepNames
            } else {
                activeKeys
                    .flatMapTo(HashSet(activeKeys.size * IconShape.entries.size)) { key ->
                        IconShape.entries.map { shape ->
                            if (shape == key.shape) {
                                key.fileName()
                            } else {
                                key.copy(shape = shape).fileName()
                            }
                        }
                    }
                    .also {
                        lastRetainedKeys = HashSet(activeKeys)
                        lastKeepNames = it
                    }
            }
        dir.listFiles()?.forEach { file ->
            if (file.name !in keep) {
                file.delete()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readFromDisk(file: File): T? {
        if (!file.isFile) return null
        val decoded =
            try {
                file.inputStream().buffered().use(decode)
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
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
            val tmp = File.createTempFile("icon-", ".tmp", dir)
            var renamed = false
            try {
                val wrote =
                    try {
                        tmp.outputStream().buffered().use { out -> encode(value, out) }
                    } catch (_: IOException) {
                        false
                    }
                renamed = wrote && tmp.renameTo(target)
            } finally {
                if (!renamed) tmp.delete()
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
