package com.qiuyuxc.tingyu

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun scrollingEvictionAndANewProcessReuseTheDownloadedOriginal() {
        val directory = temporary.newFolder()
        var requests = 0
        val cache =
            ArtworkCache(
                directory,
                { url ->
                    requests++
                    url.toByteArray()
                },
                memoryLimit = 5,
            )
        val first = cache.get("cover-one")!!
        cache.get("cover-two")
        assertArrayEquals(first, cache.get("cover-one"))
        val restarted =
            ArtworkCache(directory, { error("Cached cover must not be downloaded again") })
        assertArrayEquals(first, restarted.get("cover-one"))
        assertEquals(2, requests)
    }

    @Test
    fun simultaneousListAndPlayerRequestsDownloadOnlyOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var requests = 0
        val cache =
            ArtworkCache(
                temporary.newFolder(),
                {
                    requests++
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    byteArrayOf(1, 2, 3)
                },
            )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val list = workers.submit<ByteArray?> { cache.get("same-cover") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val player = workers.submit<ByteArray?> { cache.get("same-cover") }
            release.countDown()
            assertArrayEquals(list.get(5, TimeUnit.SECONDS), player.get(5, TimeUnit.SECONDS))
            assertEquals(1, requests)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun aTransientMissIsRetriedAndDoesNotBecomeAPermanentBlank() {
        var requests = 0
        val directory = temporary.newFolder()
        val cache = ArtworkCache(directory, { if (++requests == 1) null else byteArrayOf(9) })
        assertNull(cache.get("retry-cover"))
        assertArrayEquals(byteArrayOf(9), cache.get("retry-cover"))
        assertEquals(2, requests)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun quotaAndExplicitClearingBoundThePersistentCache() {
        val directory = temporary.newFolder()
        val cache = ArtworkCache(directory, { ByteArray(6) { 1 } }, memoryLimit = 0, diskLimit = 8)
        cache.get("one")
        cache.get("two")
        cache.get("three")
        assertTrue(cache.diskBytes() <= 8)
        cache.clear()
        assertEquals(0L, cache.diskBytes())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun oversizedResponsesNeverEnterTheCache() {
        val directory = temporary.newFolder()
        val cache = ArtworkCache(directory, { ByteArray(ArtworkCache.MAX_IMAGE + 1) })
        assertNull(cache.get("too-large"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
