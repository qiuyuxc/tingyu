package com.qiuyuxc.tingyu

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import org.json.JSONArray

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
    internal var cloudFile: Storage.Item? = null
    internal var cloudSource = ""
    internal var encryptedCover: ByteArray? = null
    internal var gainDb: Double? = null
    internal var readProblem: String? = null
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
    val isPlatform: Boolean
        get() = platformTrack != null

    val isCloud: Boolean
        get() = cloudFile != null

    val isEncrypted: Boolean
        get() = Ncmx.isEncryptedName(cloudFile?.name ?: file?.name)

    val key: String
        get() =
            platformTrack?.key
                ?: if (isCloud) {
                    "cloud:" +
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                JSONArray()
                                    .put(cloudSource)
                                    .put(requireNotNull(cloudFile).path)
                                    .toString()
                                    .toByteArray(StandardCharsets.UTF_8)
                            )
                } else file?.absolutePath.orEmpty()

    internal val storedPath: String
        get() = if (isCloud) key else file?.absolutePath.orEmpty()

    internal constructor(track: PlatformTrack) {
        platformTrack = track
    }

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

    fun subtitle(): String =
        when {
            album.isNotBlank() -> "${artist.ifBlank { "未知艺人" }} · $album"
            artist.isNotBlank() -> artist
            else -> "未知艺人"
        }

    fun sizeText(): String = String.format(Locale.US, "%.1f MB", size / 1048576.0)

    fun durationText(): String =
        if (durationSec <= 0) ""
        else String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60)

    companion object {
        internal fun cloud(item: Storage.Item, source: String): Song =
            Song(File(item.name)).apply {
                file = null
                cloudFile = item
                cloudSource = source
                size = item.size
            }

        internal fun fromStoredPath(path: String): Song {
            if (!path.startsWith("cloud:")) return Song(File(path))
            val saved =
                JSONArray(
                    String(
                        Base64.getUrlDecoder().decode(path.removePrefix("cloud:")),
                        StandardCharsets.UTF_8,
                    )
                )
            val remote = saved.getString(1)
            return cloud(Storage.Item(remote, File(remote).name, -1), saved.getString(0))
        }
    }
}
