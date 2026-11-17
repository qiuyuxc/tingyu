package com.qiuyuxc.tingyu

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Media3 may close and reopen a read on seek. Player owns the underlying source's lifetime. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BorrowedMediaDataSource(private val source: MediaDataSource) :
    BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val size = source.size
        if (size != C.LENGTH_UNSET.toLong() && dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        uri = dataSpec.uri
        position = dataSpec.position
        remaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else if (size != C.LENGTH_UNSET.toLong()) size - position else C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested =
            if (remaining == C.LENGTH_UNSET.toLong()) length
            else minOf(length.toLong(), remaining).toInt()
        val count = source.readAt(position, buffer, offset, requested)
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (count == 0) throw IOException("音频数据源没有返回数据")
        position += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        remaining = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
