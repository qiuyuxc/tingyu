package com.qiuyuxc.tingyu

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File

/** Read existing audio metadata and sidecar lyrics without altering the source file. */
internal object LocalMetadata {
    private const val MAX_LYRICS_BYTES = 2 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 8 * 1024 * 1024
    private val extensions = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav")
    fun isAudio(file: File): Boolean = file.extension.lowercase() in extensions

    fun lyricsOf(file: File): String {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
        if (sidecar.isFile && sidecar.length() <= MAX_LYRICS_BYTES) {
            runCatching { sidecar.readText().removePrefix("\uFEFF") }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            if (file.extension.equals("m4a", true)) Tags.readMp4Text(file, "©lyr").orEmpty()
            else {
                val head = ByteArray(minOf(file.length(), MAX_HEADER_BYTES.toLong()).toInt())
                java.io.DataInputStream(file.inputStream()).use { it.readFully(head) }
                Tags.readLyrics(head).orEmpty()
            }
        }.getOrDefault("")
    }

    fun enrich(song: Song) {
        val file = song.file ?: return
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { song.title = it }
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { song.artist = it }
            song.album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            song.durationSec = (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                .div(1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Exception) {
            if (file.extension.equals("flac", true)) runCatching { Tags.readVorbis(file) }.getOrNull()?.let {
                it["TITLE"]?.takeIf(String::isNotBlank)?.let { value -> song.title = value }
                it["ARTIST"]?.takeIf(String::isNotBlank)?.let { value -> song.artist = value }
                song.album = it["ALBUM"].orEmpty()
            }
        } finally { runCatching { reader.release() } }
        song.lyrics = lyricsOf(file)
    }

    fun thumbnail(file: File?, pixels: Int): Bitmap? {
        val source = file ?: return null
        runCatching { Tags.thumbnail(source, pixels) }.getOrNull()?.let { return it }
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(source.absolutePath)
            reader.embeddedPicture?.let { PlatformArtwork.decode(it, pixels) }
        } catch (_: Exception) { null }
        finally { runCatching { reader.release() } }
    }
}
