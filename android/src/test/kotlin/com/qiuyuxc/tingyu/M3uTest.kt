package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class M3uTest {
    @Test fun localPathsAndUnicodeMetadataSurviveRoundTrip() {
        val original = mutableListOf<M3u.Track?>(
            M3u.Track("/music/艺人/歌曲.flac", "歌曲", "艺人"),
            M3u.Track("/music/solo.mp3", "独奏", ""),
        )
        val result = M3u.parse(M3u.write(original)).filterNotNull()
        assertEquals(original.map { it!!.path }, result.map { it.path })
        assertEquals(original.map { it!!.title }, result.map { it.title })
        assertEquals(original.map { it!!.artist }, result.map { it.artist })
    }
    @Test fun externalPlaylistsAcceptBomCrLfCommentsAndMissingMetadata() {
        val parsed = M3u.parse("\uFEFF#EXTM3U\r\n# comment\r\n#EXTINF:1, - 独奏\r\n/music/a.mp3\r\n/music/b.flac\r\n").filterNotNull()
        assertEquals(listOf("/music/a.mp3", "/music/b.flac"), parsed.map { it.path })
        assertEquals("独奏", parsed[0].title)
        assertEquals("", parsed[0].artist)
        assertEquals("", parsed[1].title)
    }
    @Test fun aFilenameCannotInjectAnAdditionalPlaylistEntry() {
        assertThrows(IllegalArgumentException::class.java) {
            M3u.write(mutableListOf(M3u.Track("/music/a.mp3\n/music/b.mp3", "A", "B")))
        }
    }
}
