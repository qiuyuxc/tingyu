package com.qiuyuxc.tingyu

import android.os.Bundle

/** Detail screens keep their parent until Back; a bottom navigation action clears the stack. */
internal abstract class MusicRouteView(act: MainActivity) : MiuixView(act) {
    open val returnToPlayer: Boolean = false
    open val showMiniPlayer: Boolean = true

    open fun handleBack(): Boolean = false

    abstract fun saveRoute(): Bundle
}

internal object PlatformState {
    fun track(track: PlatformTrack) =
        Bundle().apply {
            putString("platform", track.platform.id)
            putString("id", track.id)
            putString("title", track.title)
            putString("artist", track.artist)
            putString("album", track.album)
            putString("cover", track.cover)
            putLong("duration", track.durationMs)
            putString("albumId", track.albumId)
            putString("audioId", track.audioId)
            putString("fileId", track.fileId)
            putString("access", track.access.name)
        }

    fun track(state: Bundle): PlatformTrack? {
        val platform = MusicPlatform.fromId(state.getString("platform")) ?: return null
        val id = state.getString("id")?.takeIf { it.isNotBlank() } ?: return null
        return PlatformTrack(
            platform,
            id,
            state.getString("title").orEmpty(),
            state.getString("artist").orEmpty(),
            state.getString("album").orEmpty(),
            state.getString("cover").orEmpty(),
            state.getLong("duration"),
            state.getString("albumId") ?: "0",
            state.getString("audioId") ?: "0",
            state.getString("fileId").orEmpty(),
            runCatching { TrackAccess.valueOf(state.getString("access").orEmpty()) }
                .getOrDefault(TrackAccess.UNKNOWN),
        )
    }

    fun playlist(list: PlatformPlaylist) =
        Bundle().apply {
            putString("platform", list.platform.id)
            putString("id", list.id)
            putString("name", list.name)
            putInt("count", list.count)
            putString("cover", list.cover)
            putBoolean("favorites", list.favorites)
            putBoolean("owned", list.owned)
            putString("listId", list.listId)
            putInt("type", list.type)
        }

    fun playlist(state: Bundle): PlatformPlaylist? {
        val platform = MusicPlatform.fromId(state.getString("platform")) ?: return null
        val id = state.getString("id") ?: return null
        return PlatformPlaylist(
            platform,
            id,
            state.getString("name").orEmpty(),
            state.getInt("count"),
            state.getString("cover").orEmpty(),
            state.getBoolean("favorites"),
            state.getBoolean("owned"),
            state.getString("listId") ?: id,
            state.getInt("type"),
        )
    }
}
