package com.qiuyuxc.tingyu

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.json.JSONObject

/** Local and remote music share the existing Cloud key ring and independent container reader. */
internal object EncryptedMusic {
    fun configuration(context: Context): String = Cloud.keyConfiguration(context)

    fun keys(context: Context): Ncmx.Keys = Cloud.keys(context)

    fun openLocal(context: Context, file: File): EncryptedReader {
        val credentials = availableKeys(context)
        val input = RandomAccessFile(file, "r")
        val source =
            object : Ncmx.Source {
                override fun length(): Long = input.length()

                override fun read(offset: Long, length: Int): ByteArray =
                    synchronized(input) {
                        input.seek(offset)
                        ByteArray(length).also { input.readFully(it) }
                    }
            }
        return EncryptedReader.open(source, credentials, input::close)
    }

    fun remoteSource(
        storage: Storage,
        item: Storage.Item,
        verifySize: Boolean = false,
    ): Ncmx.Source {
        val size = if (verifySize || item.size < 0) storage.sizeOf(item.path) else item.size
        if (size < 0) throw IOException("无法确定云端文件大小")
        return RemoteSource(storage, item.path, size)
    }

    fun enrich(context: Context, song: Song, storage: Storage? = null) {
        try {
            val reader =
                if (song.isCloud) {
                    val backend = storage ?: Cloud.backend(context)
                    val credentials = availableKeys(context)
                    val source = remoteSource(backend, requireNotNull(song.cloudFile))
                    EncryptedReader.open(source, credentials) { (source as? Closeable)?.close() }
                } else openLocal(context, requireNotNull(song.file))
            reader.use { applyMetadata(song, it.info) }
            song.readProblem = null
        } catch (error: Exception) {
            song.readProblem = error.message ?: "加密音乐读取失败"
        }
    }

    fun applyMetadata(song: Song, info: Ncmx.Info) {
        info.title?.takeIf { it.isNotBlank() }?.let { song.title = it }
        info.artist?.takeIf { it.isNotBlank() }?.let { song.artist = it }
        song.lyrics = info.lyrics.orEmpty()
        song.durationSec = info.durationSec
        song.encryptedCover = info.cover
        val raw = info.rawMeta.orEmpty()
        val json =
            raw.indexOf('\n')
                .takeIf { it >= 0 }
                ?.let { runCatching { JSONObject(raw.substring(it + 1)) }.getOrNull() }
        json?.optString("title")?.takeIf { it.isNotBlank() }?.let { song.title = it }
        json?.optString("artist")?.takeIf { it.isNotBlank() }?.let { song.artist = it }
        json?.optString("album")?.takeIf { it.isNotBlank() }?.let { song.album = it }
        song.gainDb = json?.optString("gainDb")?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    data class PreparedSource(val source: MusicDataSource, val info: Ncmx.Info? = null)

    fun playbackSource(context: Context, song: Song): PreparedSource {
        if (!song.isCloud) {
            val reader = openLocal(context, requireNotNull(song.file))
            return PreparedSource(MusicDataSource.encrypted(reader), reader.info)
        }
        if (song.cloudSource != Cloud.sourceId(context)) {
            throw IOException("云端连接已变更，请重新打开云端文件列表")
        }
        val credentials = if (song.isEncrypted) availableKeys(context) else null
        val backend = Cloud.backend(context)
        val source = remoteSource(backend, requireNotNull(song.cloudFile), verifySize = true)
        if (!song.isEncrypted) return PreparedSource(MusicDataSource.remote(source))
        val reader =
            EncryptedReader.open(source, requireNotNull(credentials)) {
                (source as? Closeable)?.close()
            }
        return PreparedSource(MusicDataSource.encrypted(reader), reader.info)
    }

    private fun availableKeys(context: Context): Ncmx.Keys =
        keys(context).also {
            if (it.forKeyId(0).isNullOrEmpty() && it.forMasterKeyId(0).isNullOrEmpty())
                throw IOException("请在“设置 → 音乐与文件 → 加密与密钥 → 高级”导入对应密钥或口令")
        }

    private class RemoteSource(
        private val storage: Storage,
        private val path: String,
        private val size: Long,
    ) : Ncmx.Source, Closeable {
        private val requests = StorageHttp.Requests()

        override fun length(): Long = size

        override fun read(offset: Long, length: Int): ByteArray = requests.run {
            storage.readRange(path, offset, length)
        }

        override fun close() = requests.cancel()
    }
}
