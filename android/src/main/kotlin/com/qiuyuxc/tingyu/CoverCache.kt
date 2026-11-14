package com.qiuyuxc.tingyu

import java.util.LinkedHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.ToIntFunction

/** A byte-bounded LRU; obsolete requests cannot deliver into a newly bound view. */
internal class CoverCache<Value : Any>(
    private val workers: Executor,
    private val callbacks: Executor,
    private val weigh: ToIntFunction<Value>,
    private val maxWeight: Int,
) {
    private val values = LinkedHashMap<String, Value?>(32, 0.75f, true)
    private val pending = HashMap<String, MutableList<Consumer<Value?>>>()
    private var weight = 0L

    constructor(
        callbacks: Executor,
        weigh: ToIntFunction<Value>,
        maxWeight: Int,
    ) : this(WORKERS, callbacks, weigh, maxWeight)

    fun load(key: String, loader: Callable<Value?>, callback: Consumer<Value?>) {
        val listeners: MutableList<Consumer<Value?>>
        var cached: Value? = null
        var hit = false
        synchronized(this) {
            if (values.containsKey(key)) {
                cached = values[key]
                hit = true
                listeners = mutableListOf()
            } else {
                pending[key]?.let {
                    it.add(callback)
                    return
                }
                listeners = mutableListOf(callback)
                pending[key] = listeners
            }
        }
        if (hit) {
            callback.accept(cached)
            return
        }
        workers.execute {
            synchronized(this) { if (pending[key] !== listeners) return@execute }
            val outcome: Pair<Value?, Boolean> =
                try {
                    loader.call() to true
                } catch (_: Exception) {
                    null to false
                }
            callbacks.execute { complete(key, listeners, outcome.first, outcome.second) }
        }
    }

    private fun complete(
        key: String,
        listeners: MutableList<Consumer<Value?>>,
        value: Value?,
        cacheResult: Boolean,
    ) {
        synchronized(this) {
            if (pending[key] !== listeners) return
            pending.remove(key)
            if (cacheResult && value != null) {
                values[key] = value
                weight += cost(value)
            }
            while (weight > maxWeight || values.size > 200) {
                val eldest = values.entries.iterator().next()
                weight -= cost(eldest.value)
                values.remove(eldest.key)
            }
        }
        listeners.forEach { it.accept(value) }
    }

    @Synchronized
    fun cancelPending() {
        pending.clear()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        values.clear()
        weight = 0L
    }

    private fun cost(value: Value?): Int =
        if (value == null) 1 else maxOf(1, weigh.applyAsInt(value))

    companion object {
        private val threadId = AtomicInteger()
        private val WORKERS: Executor =
            Executors.newFixedThreadPool(2) { task ->
                Thread(task, "cover-${threadId.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
    }
}
