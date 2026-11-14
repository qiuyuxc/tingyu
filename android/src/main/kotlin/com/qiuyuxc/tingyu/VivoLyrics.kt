package com.qiuyuxc.tingyu

import java.util.Locale

/** OriginOS consumes whole LRC via framework MediaSession extras and follows PlaybackState. */
internal object VivoLyrics {
    const val BIND_ACTION = "com.vivo.musicwidgetmix.support.service"
    const val SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    // Transport, track information, artwork, lyrics and seeking. No unimplemented list bits.
    const val CAPABILITIES = 31L
    const val ACTION_KEY = "vivomusicmix.meida.extra.key.action"
    const val MEDIA_ID_KEY = "vivomusicmix.extra.key.meidia_id"
    const val LYRIC_KEY = "vivomusicmix.extra.key.lyric"
    const val LRC_CHANGE = "vivomusicmix.extra.lrc_change"
    const val REFRESH_MS = 25_000L

    data class Track(val mediaId: String, val lrc: String) {
        fun extras(): Map<String, String> = if (lrc.isEmpty()) emptyMap() else mapOf(
            ACTION_KEY to LRC_CHANGE,
            MEDIA_ID_KEY to mediaId,
            LYRIC_KEY to lrc,
        )
    }

    fun track(title: String, artist: String, source: String): Track =
        Track("$title|$artist", plainLrc(source))

    private val timestamp = Regex("\\[(\\d{1,6}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTimestamp = Regex("<\\d{1,6}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val offsetTag = Regex("\\[offset:[+-]?\\d{1,9}]", RegexOption.IGNORE_CASE)

    /** Keep the first (original) line per timestamp; the island otherwise selects translations. */
    fun plainLrc(source: String): String {
        // Leave room in the Binder transaction for metadata. Do not send a partial lyric file.
        if (source.length > 128 * 1024) return ""
        val lines = sortedMapOf<Long, String>()
        var offset = ""
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (offsetTag.matches(line)) offset = line
            val times = ArrayList<Long>()
            var end = 0
            while (true) {
                val match = timestamp.find(line, end)?.takeIf { it.range.first == end } ?: break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toInt()
                if (seconds >= 60) break
                val millis = match.groupValues[3].padEnd(3, '0').toLong()
                times.add(minutes * 60_000 + seconds * 1000 + millis)
                end = match.range.last + 1
            }
            val text = wordTimestamp.replace(line.substring(end), "").trim()
            times.forEach { time -> if (!lines.containsKey(time)) lines[time] = text }
        }
        if (lines.values.none { it.isNotBlank() }) return ""
        return buildString {
            if (offset.isNotEmpty()) append(offset).append('\n')
            lines.forEach { (time, text) ->
                if (length + text.length + 20 > 128 * 1024) return ""
                if (isNotEmpty() && last() != '\n') append('\n')
                append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", time / 60_000, time / 1000 % 60, time % 1000))
                append(text)
            }
        }
    }
}
