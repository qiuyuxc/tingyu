package com.qiuyuxc.tingyu

import java.net.URL

internal data class PlatformTrack(
    val platform: MusicPlatform,
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val albumId: String = "0",
    val audioId: String = "0",
    val fileId: String = "",
    val access: TrackAccess = TrackAccess.UNKNOWN,
    val qualities: List<QualityAccess> = emptyList(),
) {
    val key: String
        get() = "platform:${platform.id}:$id"
}

internal data class PlatformPlaylist(
    val platform: MusicPlatform,
    val id: String,
    val name: String,
    val count: Int,
    val cover: String = "",
    val favorites: Boolean = false,
    val owned: Boolean = false,
    val listId: String = id,
    val type: Int = 0,
)

internal data class PlatformPage<T>(val items: List<T>, val more: Boolean)

internal data class PlatformLibrary(
    val playlists: List<PlatformPlaylist>,
    val favorites: Map<String, PlatformTrack>,
    val notices: List<String> = emptyList(),
    val favoritesReady: Boolean = true,
) {
    val favoritePlaylist
        get() = playlists.firstOrNull { it.favorites }
}

internal data class PlatformStream(
    val url: String,
    val trial: Boolean = false,
    val quality: AudioQuality? = null,
    val bitrate: Int = 0,
    val format: String = "",
    val notice: String? = null,
    val defaultSuggestion: AudioQuality? = null,
    val gainDb: Double? = null,
) {
    init {
        val address = URL(url)
        require(
            address.protocol in setOf("https", "http") &&
                address.userInfo == null &&
                address.port in setOf(-1, 80, 443) &&
                address.host.isNotBlank()
        ) {
            "平台未返回有效播放地址"
        }
    }
}

internal data class QualitySuggestion(
    val songKey: String,
    val platform: MusicPlatform,
    val userId: String,
    val requested: AudioQuality,
    val suggested: AudioQuality,
)

/** The concept edition issues a different token from the standard Kugou application. */
internal object KugouLite {
    const val APP_ID = "3116"
    const val CLIENT_VERSION = "11440"
    const val EDITION = "lite"
}
