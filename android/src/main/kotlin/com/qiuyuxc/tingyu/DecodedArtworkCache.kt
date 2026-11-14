package com.qiuyuxc.tingyu

import java.util.LinkedHashMap

/** Large player artwork has its own budget, so it cannot evict a scrolled list's thumbnails. */
internal class DecodedArtworkCache<Value : Any>(
    private val weigh: (Value) -> Int,
    thumbnailLimit: Int = 24 * 1024 * 1024,
    playerLimit: Int = 12 * 1024 * 1024,
) {
    private data class Key(val source: String, val pixels: Int)

    private inner class Pool(val limit: Int) {
        val values = LinkedHashMap<Key, Value>(32, 0.75f, true)
        var weight = 0L
    }

    private val thumbnails = Pool(thumbnailLimit)
    private val player = Pool(playerLimit)
    private val locks = Array(32) { Any() }
    private var generation = 0L

    @Synchronized
    fun cached(source: String, pixels: Int): Value? = pool(pixels).values[Key(source, size(pixels))]

    /** A cold decode is coalesced even when a list, queue and player request it together. */
    fun get(source: String, pixels: Int, loader: (Int) -> Value?): Value? {
        val key = Key(source, size(pixels))
        return synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            cached(source, pixels)?.let {
                return@synchronized it
            }
            val requestGeneration = synchronized(this) { generation }
            val value = loader(key.pixels) ?: return@synchronized null
            synchronized(this) {
                if (requestGeneration != generation) return@synchronized
                val pool = pool(pixels)
                pool.values.remove(key)?.let { pool.weight -= weigh(it).coerceAtLeast(1) }
                pool.values[key] = value
                pool.weight += weigh(value).coerceAtLeast(1)
                while (pool.weight > pool.limit || pool.values.size > 512) {
                    val first = pool.values.entries.iterator().next()
                    pool.weight -= weigh(first.value).coerceAtLeast(1)
                    pool.values.remove(first.key)
                }
            }
            value
        }
    }

    @Synchronized
    fun clear() {
        generation++
        for (pool in listOf(thumbnails, player)) {
            pool.values.clear()
            pool.weight = 0L
        }
    }

    private fun pool(pixels: Int) = if (size(pixels) <= 384) thumbnails else player

    companion object {
        fun size(pixels: Int): Int {
            require(pixels > 0)
            return when {
                pixels <= 192 -> 192
                pixels <= 384 -> 384
                pixels <= 768 -> 768
                else -> 1024
            }
        }
    }
}
