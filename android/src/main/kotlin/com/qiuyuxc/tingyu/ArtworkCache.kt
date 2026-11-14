package com.qiuyuxc.tingyu

import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Original image bytes survive list recycling and process restarts, bounded in memory and on disk.
 */
internal class ArtworkCache(
    private val directory: File,
    private val fetch: (String) -> ByteArray?,
    private val memoryLimit: Int = 12 * 1024 * 1024,
    private val diskLimit: Long = 96L * 1024 * 1024,
) {
    private val memory = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    private var memoryBytes = 0L
    private val locks = Array(32) { Any() }
    private val diskLock = Any()

    fun get(url: String): ByteArray? {
        val key = key(url)
        return synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            synchronized(memory) { memory[key] }
                ?.let {
                    return@synchronized it
                }
            val file = File(directory, "$key.img")
            val cached = runCatching {
                if (file.isFile && file.length() in 1..MAX_IMAGE.toLong()) file.readBytes()
                else null
            }
                .getOrNull()
            if (cached != null) {
                file.setLastModified(System.currentTimeMillis())
                remember(key, cached)
                return@synchronized cached
            }
            val bytes =
                fetch(url)?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE }
                    ?: return@synchronized null
            remember(key, bytes)
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, "$key-${System.nanoTime()}.part")
                try {
                    temporary.outputStream().use { it.write(bytes) }
                    check(temporary.renameTo(file))
                } finally {
                    temporary.delete()
                }
                trimDisk()
            }
            bytes
        }
    }

    private fun remember(key: String, bytes: ByteArray) =
        synchronized(memory) {
            memory.remove(key)?.let { memoryBytes -= it.size }
            memory[key] = bytes
            memoryBytes += bytes.size
            while (memoryBytes > memoryLimit || memory.size > 256) {
                val first = memory.entries.iterator().next()
                memoryBytes -= first.value.size
                memory.remove(first.key)
            }
        }

    private fun trimDisk() =
        synchronized(diskLock) {
            val files =
                directory
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".img") }
                    .sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= diskLimit) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }

    fun clear() {
        synchronized(memory) {
            memory.clear()
            memoryBytes = 0
        }
        synchronized(diskLock) { directory.listFiles().orEmpty().forEach { it.delete() } }
    }

    fun diskBytes(): Long =
        directory.listFiles().orEmpty().filter { it.name.endsWith(".img") }.sumOf { it.length() }

    private fun key(url: String) =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val MAX_IMAGE = 16 * 1024 * 1024
    }
}
