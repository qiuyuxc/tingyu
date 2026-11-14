package com.qiuyuxc.tingyu

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryTest {
    @Test
    fun longSongsCountAfterThirtySecondsOnlyOnce() {
        val session = ListeningSession()
        assertFalse(session.sample(true, 0, 240000))
        assertFalse(session.sample(true, 29000, 240000))
        assertTrue(session.sample(true, 30000, 240000))
        assertFalse(session.sample(true, 30000, 240000))
        assertFalse(session.sample(true, 240000, 240000))
    }

    @Test
    fun pausedTimeDoesNotIncreaseListening() {
        val session = ListeningSession()
        session.sample(true, 0, 180000)
        assertFalse(session.sample(false, 10000, 180000))
        assertFalse(session.sample(true, 100000, 180000))
        assertFalse(session.sample(true, 119999, 180000))
        assertTrue(session.sample(false, 120000, 180000))
    }

    @Test
    fun unsuccessfulAndPausedPreparationCannotBecomePlays() {
        val session = ListeningSession()
        assertFalse(session.sample(false, 0, 0))
        assertFalse(session.sample(false, 60000, 0))
        assertFalse(session.sample(false, 120000, 180000))
        assertFalse(session.sample(true, 120000, 180000))
        assertFalse(session.sample(true, 121000, 180000))
    }

    @Test
    fun shortSongsCountHalfTheirDurationIncludingCompletion() {
        val session = ListeningSession()
        session.sample(true, 0, 4000)
        assertFalse(session.sample(true, 1999, 4000))
        assertTrue(session.sample(false, 2000, 4000))
        assertFalse(session.sample(false, 8000, 4000))
    }

    @Test
    fun aNewPlaybackSessionCanCountTheSameSongAgain() {
        val session = ListeningSession()
        session.sample(true, 0, 10000)
        assertTrue(session.sample(true, 5000, 10000))
        session.reset()
        assertFalse(session.sample(true, 6000, 10000))
        assertTrue(session.sample(true, 11000, 10000))
    }

    @Test
    fun repeatedStateNotificationsDoNotProduceExtraListeningTime() {
        val session = ListeningSession()
        repeat(20) { assertFalse(session.sample(true, 1000, 180000)) }
        assertFalse(session.sample(true, 29999, 180000))
        assertTrue(session.sample(true, 31000, 180000))
    }

    @Test
    fun platformMetadataSurvivesStorageWithoutLosingItsSource() {
        val track =
            PlatformTrack(
                MusicPlatform.NETEASE,
                "42",
                "歌曲",
                "艺人",
                "专辑",
                "https://cdn.example.com/cover.jpg",
                180000,
                albumId = "7",
                audioId = "9",
                access = TrackAccess.VIP,
            )
        val snapshot = HeardSong.from(Song(track))
        val restored = HeardSong.from(JSONObject(snapshot.json().toString()))
        assertEquals(snapshot, restored)
        assertEquals(track, restored.song().platformTrack)
        assertNull(restored.song().file)
    }

    @Test
    fun localFilesPreserveTheirMetadataAndIdentity() {
        val local = Song(File("/music/song.flac")).apply {
            title = "本地"
            artist = "艺人"
            album = "专辑"
            durationSec = 120
        }
        val restored = HeardSong.from(JSONObject(HeardSong.from(local).json().toString())).song()
        assertEquals(local.key, restored.key)
        assertEquals(local.title, restored.title)
        assertEquals(local.artist, restored.artist)
        assertEquals(local.album, restored.album)
        assertEquals(local.durationSec, restored.durationSec)
        assertFalse(restored.isPlatform)
        assertNull(restored.platformTrack)
    }
}
