package com.qiuyuxc.tingyu

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class CoverCacheTest {
    private class Queued : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun requestsCoalesceAndDeliverOnlyOnTheCallbackExecutor() {
        val workers = Queued()
        val callbacks = Queued()
        val cache = CoverCache<String>(workers, callbacks, { it.length }, 6)
        val received = mutableListOf<String?>()
        var loads = 0
        cache.load(
            "cover",
            {
                loads++
                "one"
            },
            { received.add(it) },
        )
        cache.load("cover", { error("duplicate decode") }, { received.add(it) })
        assertEquals(1, workers.tasks.size)
        assertEquals(0, loads)
        workers.drain()
        assertTrue(received.isEmpty())
        callbacks.drain()
        assertEquals(listOf("one", "one"), received)
        cache.load("cover", { error("warm cache decoded again") }, { received.add(it) })
        assertEquals(1, loads)
        assertTrue(workers.tasks.isEmpty())
    }

    @Test
    fun rebindingDropsAlreadyCompletedObsoleteRequests() {
        val workers = Queued()
        val callbacks = Queued()
        val cache = CoverCache<String>(workers, callbacks, { it.length }, 6)
        val received = mutableListOf<String?>()
        cache.load("cover", { "old" }, { received.add(it) })
        workers.drain()
        cache.cancelPending()
        cache.load("cover", { "new" }, { received.add(it) })
        workers.drain()
        callbacks.drain()
        assertEquals(listOf("new"), received)
        cache.load(
            "cover",
            { error("obsolete value replaced the new cover") },
            { received.add(it) },
        )
        assertEquals(listOf("new", "new"), received)
    }

    @Test
    fun evictionUsesWeightAndFailedLoadsCanRetry() {
        val workers = Queued()
        val callbacks = Queued()
        val cache = CoverCache<String>(workers, callbacks, { it.length }, 4)
        cache.load("a", { "aaa" }, {})
        workers.drain()
        callbacks.drain()
        cache.load("b", { "bbb" }, {})
        workers.drain()
        callbacks.drain()
        var loads = 0
        cache.load(
            "a",
            {
                loads++
                "aaa"
            },
            {},
        )
        workers.drain()
        callbacks.drain()
        assertEquals(1, loads)
        cache.load("failed", { throw IllegalStateException("decode failed") }, {})
        workers.drain()
        callbacks.drain()
        val received = mutableListOf<String?>()
        cache.load("failed", { "ok" }, { received.add(it) })
        workers.drain()
        callbacks.drain()
        assertEquals(listOf("ok"), received)
    }
}
