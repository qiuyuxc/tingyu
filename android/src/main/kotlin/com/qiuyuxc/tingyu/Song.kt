package com.qiuyuxc.tingyu

import java.io.File
import java.util.Locale

/** Local files and platform tracks share one playback queue. */
class Song {
    @JvmField var file: File? = null
    @JvmField var title = ""
    @JvmField var artist = ""
    @JvmField var album = ""
    @JvmField var size = 0L
    @JvmField var musicId = 0L
    @JvmField var lyrics = ""
    @JvmField var durationSec = 0
    internal var platformTrack: PlatformTrack? = null
        set(value) {
            field = value
            if (value != null) {
                title = value.title
                artist = value.artist
                album = value.album
                durationSec = (value.durationMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (value.platform == MusicPlatform.NETEASE) musicId = value.id.toLongOrNull() ?: 0L
            }
        }
    internal var platformArtwork: ByteArray? = null
    val isPlatform: Boolean get() = platformTrack != null
    val key: String get() = platformTrack?.key ?: file?.absolutePath.orEmpty()

    internal constructor(track: PlatformTrack) { platformTrack = track }

    constructor(audio: File?) {
        val source = requireNotNull(audio)
        file = source
        size = source.length()
        val base = source.nameWithoutExtension
        val dash = base.indexOf(" - ")
        if (dash > 0) {
            artist = base.substring(0, dash).trim()
            title = base.substring(dash + 3).trim()
        } else title = base.trim()
    }

    fun subtitle(): String = when {
        album.isNotBlank() -> "${artist.ifBlank { "未知艺人" }} · $album"
        artist.isNotBlank() -> artist
        else -> "未知艺人"
    }
    fun sizeText(): String = String.format(Locale.US, "%.1f MB", size / 1048576.0)
    fun durationText(): String = if (durationSec <= 0) ""
        else String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60)
}
