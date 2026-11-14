package com.qiuyuxc.tingyu

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DecodedArtworkCacheTest {
    private fun cache() =
        DecodedArtworkCache<ByteArray>({ it.size }, thumbnailLimit = 12, playerLimit = 8)

    @Test
    fun changingLargePlayerCoversCannotEvictListAndQueueThumbnails() {
        val cache = cache()
        val list = cache.get("album-a", 160) { ByteArray(6) }
        val queue = cache.get("album-b", 138) { ByteArray(6) }
        repeat(8) { cache.get("player-$it", 720) { ByteArray(8) } }
        assertSame(list, cache.cached("album-a", 160))
        assertSame(queue, cache.cached("album-b", 138))
        assertNull(cache.cached("player-0", 720))
        assertNotNull(cache.cached("player-7", 720))
    }

    @Test
    fun listAndQueueRequestsInTheSameSizeBucketReuseTheExactBitmap() {
        val cache = cache()
        val bitmap =
            cache.get("album", 160) { size ->
                assertEquals(192, size)
                ByteArray(4)
            }
        assertSame(bitmap, cache.get("album", 138) { error("Warm artwork was decoded again") })
        assertSame(bitmap, cache.cached("album", 192))
        assertNull(cache.cached("album", 320))
    }

    @Test
    fun memoryBudgetEvictsTheLeastRecentlyUsedThumbnail() {
        val cache = cache()
        val first = cache.get("one", 160) { ByteArray(6) }
        cache.get("two", 160) { ByteArray(6) }
        assertSame(first, cache.cached("one", 160))
        cache.get("three", 160) { ByteArray(6) }
        assertSame(first, cache.cached("one", 160))
        assertNull(cache.cached("two", 160))
        assertNotNull(cache.cached("three", 160))
    }

    @Test
    fun failedArtworkCanBeRetriedAndExplicitClearInvalidatesWarmImages() {
        val cache = cache()
        assertNull(cache.get("album", 160) { null })
        val loaded = cache.get("album", 160) { ByteArray(4) }
        assertNotNull(loaded)
        cache.clear()
        assertNull(cache.cached("album", 160))
        assertNotSame(loaded, cache.get("album", 160) { ByteArray(4) })
    }

    @Test
    fun concurrentRequestsDecodeOnceAndDeliverTheSameImage() {
        val cache = cache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                (1..4).map {
                    pool.submit<ByteArray?> {
                        cache.get("album", 160) {
                            calls.incrementAndGet()
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                            ByteArray(4)
                        }
                    }
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            release.countDown()
            val images = tasks.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertTrue(images.all { it === images.first() })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun clearDuringADecodeCannotRepopulateTheCacheWithItsOldResult() {
        val cache = cache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task =
                pool.submit<ByteArray?> {
                    cache.get("album", 160) {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        ByteArray(4)
                    }
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            cache.clear()
            release.countDown()
            assertNotNull(task.get(3, TimeUnit.SECONDS))
            assertNull(cache.cached("album", 160))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
