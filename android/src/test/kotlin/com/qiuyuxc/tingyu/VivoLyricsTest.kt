package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class VivoLyricsTest {
    @Test
    fun mergedTranslationsDoNotReplaceTheOriginalOnTheIsland() {
        val source = "[00:02.5]Second\n[00:01.00]First\n[00:01.000]第一句\n[00:02.500]第二句"
        assertEquals("[00:01.000]First\n[00:02.500]Second", VivoLyrics.plainLrc(source))
    }

    @Test
    fun repeatedVerseTimestampsAndWordTimingsBecomePlainLrc() {
        val source = "[00:10][01:20.05]<00:10.00>One <00:10.50>line<00:11.00>"
        assertEquals("[00:10.000]One line\n[01:20.050]One line", VivoLyrics.plainLrc(source))
    }

    @Test
    fun instrumentalGapsAndOffsetArePreserved() {
        val source = "[offset:-200]\r\n[00:00.00]\r\n[00:03.125]开始\r\n[00:09.00]"
        assertEquals("[offset:-200]\n[00:00.000]\n[00:03.125]开始\n[00:09.000]", VivoLyrics.plainLrc(source))
    }

    @Test
    fun malformedAndOversizedLyricsDoNotAdvertisePartialLyrics() {
        assertEquals("", VivoLyrics.plainLrc("[ar:Artist]\nPlain lyrics\n[00:99]Bad\n[00:00]"))
        assertEquals("", VivoLyrics.plainLrc("[00:01]" + "文".repeat(128 * 1024)))
        val repeated = (1..200).joinToString("") { "[$it:00]" } + "文".repeat(1000)
        assertEquals("", VivoLyrics.plainLrc(repeated))
        assertEquals("", VivoLyrics.plainLrc(""))
    }

    @Test
    fun switchingTracksWithIdenticalLyricsUpdatesTheConsumerIdentity() {
        val first = VivoLyrics.track("First", "Artist", "[00:01]Same lyric")
        val next = VivoLyrics.track("Next", "Artist", "[00:01]Same lyric")
        val extras = next.extras()
        assertEquals("vivomusicmix.extra.lrc_change", extras["vivomusicmix.meida.extra.key.action"])
        assertEquals(next.mediaId, extras["vivomusicmix.extra.key.meidia_id"])
        assertNotEquals(first.mediaId, extras["vivomusicmix.extra.key.meidia_id"])
        assertEquals("[00:01.000]Same lyric", extras["vivomusicmix.extra.key.lyric"])
    }

    @Test
    fun aTrackWithoutLyricsClearsThePreviouslyPublishedPayload() {
        var published = VivoLyrics.track("有歌词", "歌手", "[00:01]你好").extras()
        assertFalse(published.isEmpty())
        published = VivoLyrics.track("纯音乐", "歌手", "").extras()
        assertTrue(published.isEmpty())
    }
}
