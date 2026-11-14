package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal object PlatformArtwork {
    @Volatile private var cache: ArtworkCache? = null
    private var cacheDirectory: File? = null
    private val coverSources = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        val directory = File(context.cacheDir, "platform-artwork")
        synchronized(this) {
            if (cacheDirectory != directory) {
                cacheDirectory = directory
                cache = ArtworkCache(directory, ::download)
                ArtworkBitmaps.cache.clear()
            }
        }
    }

    /** Artwork requests carry no account cookies or tokens. */
    fun bytes(value: String): ByteArray? {
        val safe = PlatformLoginClient.safeAvatar(value)
        if (safe.isBlank()) return null
        val storage = cache
        return if (storage != null) storage.get(safe) else download(safe)
    }

    private fun source(value: String) = "url:${PlatformLoginClient.safeAvatar(value)}"

    fun cachedThumbnail(value: String, pixels: Int = 160): Bitmap? =
        ArtworkBitmaps.cache.cached(source(value), pixels)

    private fun download(safe: String): ByteArray? {
        val connection = URL(safe).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = false
            if (connection.responseCode in 500..599) throw IOException("封面服务暂时不可用")
            if (connection.responseCode != 200) return null
            val result = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > ArtworkCache.MAX_IMAGE) return null
                    result.write(buffer, 0, count)
                }
            }
            return result.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun thumbnail(value: String, pixels: Int = 160): Bitmap? {
        if (PlatformLoginClient.safeAvatar(value).isBlank()) return null
        return ArtworkBitmaps.cache.get(source(value), pixels) { target ->
            bytes(value)?.let { decode(it, target) }
        }
    }

    fun forSong(context: Context, song: Song, pixels: Int): Bitmap? {
        val track = song.platformTrack ?: return null
        cachedForTrack(track, pixels)?.let {
            return it
        }
        var cover = track.cover.ifBlank { coverSources[track.key].orEmpty() }
        if (cover.isBlank() && song.platformArtwork == null) {
            val detail =
                PlatformDiscoveryClient.forPlatform(context, track.platform)
                    .details(listOf(track))
                    .firstOrNull()
            if (detail != null) song.platformTrack = detail
            cover = detail?.cover.orEmpty()
        }
        if (cover.isNotBlank()) coverSources[track.key] = cover
        val source = if (cover.isBlank()) track.key else source(cover)
        return ArtworkBitmaps.cache.get(source, pixels) { target ->
            (song.platformArtwork ?: bytes(cover))?.let { decode(it, target) }
        }
    }

    private fun sourceForTrack(track: PlatformTrack): String {
        val cover = track.cover.ifBlank { coverSources[track.key].orEmpty() }
        return if (cover.isBlank()) track.key else source(cover)
    }

    fun keyForTrack(track: PlatformTrack, pixels: Int): String =
        "${sourceForTrack(track)}:${DecodedArtworkCache.size(pixels)}"

    fun cachedForTrack(track: PlatformTrack, pixels: Int = 160): Bitmap? =
        ArtworkBitmaps.cache.cached(sourceForTrack(track), pixels)

    fun clear() {
        cache?.clear()
        ArtworkBitmaps.cache.clear()
    }

    fun diskBytes(): Long = cache?.diskBytes() ?: 0L

    fun decode(bytes: ByteArray, pixels: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > pixels * 2) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= pixels) return decoded
        val resized =
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width.toLong() * pixels / largest).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * pixels / largest).toInt().coerceAtLeast(1),
                true,
            )
        if (resized !== decoded) decoded.recycle()
        return resized
    }
}
