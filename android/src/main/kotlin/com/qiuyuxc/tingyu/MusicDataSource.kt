package com.qiuyuxc.tingyu

import android.media.MediaDataSource
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Player owns the source; Media3's temporary reads may close and reopen while seeking. */
internal class MusicDataSource
private constructor(
    private val length: Long,
    private val read: (Long, ByteArray, Int, Int) -> Int,
    private val release: () -> Unit,
) : MediaDataSource() {
    private val closed = AtomicBoolean()

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (closed.get()) throw IOException("音乐文件已关闭")
        return read(position, buffer ?: throw IOException("缺少音频缓冲区"), offset, size)
    }

    override fun getSize(): Long = length

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release()
    }

    companion object {
        fun encrypted(reader: EncryptedReader): MusicDataSource =
            MusicDataSource(reader.size, reader::readAt, reader::close)

        fun remote(source: Ncmx.Source): MusicDataSource =
            MusicDataSource(
                source.length(),
                { position, buffer, offset, length ->
                    if (position < 0 || offset < 0 || length < 0 || offset > buffer.size - length) {
                        throw IOException("音频读取范围无效")
                    }
                    val total = source.length()
                    when {
                        length == 0 -> 0
                        position >= total -> -1
                        else -> {
                            val count = minOf(length.toLong(), total - position).toInt()
                            val bytes = source.read(position, count)
                            if (bytes == null || bytes.size != count) throw IOException("音频数据不完整")
                            bytes.copyInto(buffer, offset)
                            count
                        }
                    }
                },
                { (source as? Closeable)?.close() },
            )
    }
}
