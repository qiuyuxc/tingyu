package com.qiuyuxc.tingyu

/** UTF-8 extended playlists for local audio files. */
object M3u {
    class Track(
        @JvmField var path: String?,
        @JvmField var title: String?,
        @JvmField var artist: String?,
    )

    @JvmStatic fun write(tracks: MutableList<Track?>?): String = buildString {
        append("#EXTM3U\n")
        tracks.orEmpty().filterNotNull().forEach { track ->
            val path = track.path.orEmpty()
            require(path.isNotBlank() && '\n' !in path && '\r' !in path && !path.startsWith('#'))
            append("#EXTINF:-1,")
            append(display(track.artist, track.title).replace('\n', ' ').replace('\r', ' '))
            append('\n').append(path).append('\n')
        }
    }

    @JvmStatic fun display(artist: String?, title: String?): String =
        if (artist.isNullOrBlank()) title.orEmpty() else "$artist - ${title.orEmpty()}"

    @JvmStatic fun parse(text: String?): MutableList<Track?> {
        val result = mutableListOf<Track?>()
        var title = ""
        var artist = ""
        text.orEmpty().removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    val info = line.substringAfter(',', "").removePrefix(" - ")
                    val dash = info.indexOf(" - ")
                    artist = if (dash > 0) info.substring(0, dash).trim() else ""
                    title = if (dash > 0) info.substring(dash + 3).trim() else info.trim()
                }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    result += Track(line, title, artist)
                    title = ""
                    artist = ""
                }
            }
        }
        return result
    }
}
