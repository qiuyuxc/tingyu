package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal object ArtworkBitmaps {
    val cache = DecodedArtworkCache<Bitmap>({ it.allocationByteCount })
}

/** One decoded-image store for lists, the mini player, recent songs and the playback queue. */
internal object SongArtwork {
    private val handler = Handler(Looper.getMainLooper())
    private val workers =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task, "song-artwork").apply { isDaemon = true }
        }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()

    fun key(song: Song, pixels: Int): String =
        if (song.isPlatform) PlatformArtwork.keyForTrack(requireNotNull(song.platformTrack), pixels)
        else "${source(song)}:${DecodedArtworkCache.size(pixels)}"

    private fun source(song: Song) =
        "song:${song.key}:${song.size}:${song.file?.lastModified() ?: 0}:${java.util.Arrays.hashCode(song.encryptedCover)}"

    fun cached(song: Song, pixels: Int): Bitmap? =
        if (song.isPlatform)
            PlatformArtwork.cachedForTrack(requireNotNull(song.platformTrack), pixels)
        else ArtworkBitmaps.cache.cached(source(song), pixels)

    fun get(context: Context, song: Song, pixels: Int): Bitmap? {
        if (song.isPlatform) return PlatformArtwork.forSong(context, song, pixels)
        return ArtworkBitmaps.cache.get(source(song), pixels) { target ->
            if (song.isEncrypted) song.encryptedCover?.let { PlatformArtwork.decode(it, target) }
            else LocalMetadata.thumbnail(song.file, target)
        }
    }

    /** Warm images bind synchronously, avoiding a placeholder frame when a row is recreated. */
    fun load(context: Context, song: Song, pixels: Int, callback: (Bitmap?) -> Unit) {
        cached(song, pixels)?.let {
            callback(it)
            return
        }
        val key = key(song, pixels)
        synchronized(pending) {
            pending[key]?.let {
                it += callback
                return
            }
            pending[key] = mutableListOf(callback)
        }
        val app = context.applicationContext
        workers.execute {
            val bitmap = runCatching { get(app, song, pixels) }.getOrNull()
            handler.post {
                val callbacks = synchronized(pending) { pending.remove(key).orEmpty() }
                callbacks.forEach { it(bitmap) }
            }
        }
    }

    suspend fun await(context: Context, song: Song, pixels: Int): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            load(context, song, pixels) { bitmap ->
                if (continuation.isActive) continuation.resume(bitmap)
            }
        }
}

@Composable
internal fun rememberSongArtwork(song: Song, pixels: Int): State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    val key = SongArtwork.key(song, pixels)
    val state = remember(key) { mutableStateOf(SongArtwork.cached(song, pixels)) }
    LaunchedEffect(key, song.platformArtwork != null) {
        if (state.value == null) state.value = SongArtwork.await(context, song, pixels)
    }
    return state
}
