package com.qiuyuxc.tingyu

import java.io.Closeable
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated random reads, with a bounded cache of recently decrypted chunks. */
internal class EncryptedReader
private constructor(
    private val source: Ncmx.Source,
    val info: Ncmx.Info,
    private val closeSource: () -> Unit,
) : Closeable {
    private val chunks = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    private val cacheLock = Any()
    private var cacheBytes = 0L
    private val closed = AtomicBoolean()
    val size: Long
        get() = requireNotNull(info.header).plainSize

    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) throw IOException("音乐文件已关闭")
        if (position < 0 || offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IOException("音频读取范围无效")
        }
        if (length == 0) return 0
        if (position >= size) return -1
        val header = requireNotNull(info.header)
        val count = minOf(length.toLong(), size - position).toInt()
        val first = position / header.chunkSize()
        val last = (position + count - 1) / header.chunkSize()
        try {
            // Authenticate every requested chunk before changing the caller's buffer.
            val verified =
                (first..last).map { index ->
                    synchronized(cacheLock) { chunks[index] }
                        ?: requireNotNull(Ncmx.readChunk(source, header, info.key, index)).also {
                            chunk ->
                            synchronized(cacheLock) {
                                if (!closed.get()) {
                                    chunks.put(index, chunk)?.let { cacheBytes -= it.size }
                                    cacheBytes += chunk.size
                                    val oldest = chunks.entries.iterator()
                                    while (cacheBytes > CACHE_BYTES && oldest.hasNext()) {
                                        cacheBytes -= oldest.next().value.size
                                        oldest.remove()
                                    }
                                }
                            }
                        }
                }
            if (closed.get()) throw IOException("音乐文件已关闭")
            var written = 0
            verified.forEachIndexed { index, chunk ->
                val start = (first + index) * header.chunkSize()
                val from = maxOf(0L, position - start).toInt()
                val until = minOf(chunk.size.toLong(), position + count - start).toInt()
                chunk.copyInto(buffer, offset + written, from, until)
                written += until - from
            }
            return written
        } catch (error: Ncmx.NcmxException) {
            throw IOException(error.message, error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closeSource()
        } finally {
            synchronized(cacheLock) {
                chunks.clear()
                cacheBytes = 0
            }
        }
    }

    companion object {
        private const val CACHE_BYTES = 8L * 1024 * 1024

        fun open(
            source: Ncmx.Source,
            keys: Ncmx.Keys,
            closeSource: () -> Unit = {},
        ): EncryptedReader {
            try {
                return EncryptedReader(
                    source,
                    requireNotNull(Ncmx.inspect(source, keys)),
                    closeSource,
                )
            } catch (error: Exception) {
                runCatching(closeSource)
                throw error
            }
        }
    }
}
