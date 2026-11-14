package com.qiuyuxc.tingyu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackGainTest {
    @Test
    fun theProvidedMasterGainIsConvertedFromDecibelsWithoutChangingQuality() {
        val stream =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "190709",
                JSONObject(
                    """{"data":[{"id":190709,"url":"https://cdn.example.com/master.flac","level":"jymaster","br":5119269,"type":"flac","gain":-9.484199523925781}]}"""
                ),
            )
        assertEquals(AudioQuality.MASTER, stream.quality)
        assertEquals(-9.484199523925781, stream.gainDb!!, 0.000001)
        val gain = PlaybackGain()
        gain.setTrackGain(stream.gainDb)
        assertEquals(0.3355753f, gain.volume, 0.00001f)
    }

    @Test
    fun regainingAudioFocusDoesNotRemoveTheSongsHeadroom() {
        val gain = PlaybackGain()
        gain.setTrackGain(-6.0)
        val full = gain.volume
        gain.setDucked(true)
        assertEquals(full * 0.3f, gain.volume, 0.000001f)
        gain.setDucked(false)
        assertEquals(full, gain.volume, 0f)
    }

    @Test
    fun changingTracksResetsThePreviousGainWhileKeepingFocusDucking() {
        val gain = PlaybackGain()
        gain.setTrackGain(-9.0)
        gain.setDucked(true)
        gain.setTrackGain(null)
        assertEquals(0.3f, gain.volume, 0f)
        gain.reset()
        assertEquals(1f, gain.volume, 0f)
    }

    @Test
    fun missingInvalidOrPositiveMetadataCannotAmplifyTheSignal() {
        val gain = PlaybackGain()
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 12.0, 0.0)) {
            gain.setTrackGain(value)
            assertEquals(1f, gain.volume, 0f)
        }
        gain.setTrackGain(-1000.0)
        assertEquals(0.001f, gain.volume, 0.000001f)
    }

    @Test
    fun absentGainStaysUnknownAndOtherPlatformsDoNotBorrowItsUnits() {
        val missing =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "42",
                JSONObject("""{"data":[{"id":42,"url":"https://cdn.example.com/song.flac"}]}"""),
            )
        assertNull(missing.gainDb)
        val kugou =
            PlatformJson.stream(
                MusicPlatform.KUGOU,
                "hash",
                JSONObject("""{"url":"https://cdn.example.com/song.flac","gain":-9.5}"""),
            )
        assertNull(kugou.gainDb)
    }
}
