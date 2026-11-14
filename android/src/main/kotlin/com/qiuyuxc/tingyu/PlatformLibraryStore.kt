package com.qiuyuxc.tingyu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/** Public library metadata is scoped to both platform and user; it contains no login tokens. */
internal object PlatformLibraryStore {
    private val libraries = ConcurrentHashMap<String, PlatformLibrary>()
    var revision by mutableIntStateOf(0)
        private set

    private fun key(profile: PlatformProfile) = "${profile.platform.id}:${profile.userId}"

    fun get(profile: PlatformProfile): PlatformLibrary? = libraries[key(profile)]

    fun publish(profile: PlatformProfile, library: PlatformLibrary) {
        libraries[key(profile)] = library
        revision++
    }

    fun publishCatalog(profile: PlatformProfile, playlists: List<PlatformPlaylist>) {
        val previous = get(profile)
        publish(
            profile,
            PlatformLibrary(
                playlists,
                previous?.favorites.orEmpty(),
                favoritesReady = previous?.favoritesReady == true,
            ),
        )
    }

    fun clear(platform: MusicPlatform) {
        libraries.keys.removeAll { it.startsWith("${platform.id}:") }
        revision++
    }
}
