package com.qiuyuxc.tingyu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformAudioTransportTest {
    @Test
    fun masterStreamKeepsItsQualityFormatGainAndAuthorizationQuery() {
        val stream =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "99",
                JSONObject(
                    """{"data":[{"id":99,"url":"http://m801.music.126.net/n%2Fa.flac?auth=test%2Fvalue","level":"jymaster","type":"flac","br":5000000,"gain":-7.5}]}"""
                ),
            )
        assertEquals("https://m801.music.126.net/n%2Fa.flac?auth=test%2Fvalue", stream.url)
        assertEquals(AudioQuality.MASTER, stream.quality)
        assertEquals("flac", stream.format)
        assertEquals(5000000, stream.bitrate)
        assertEquals(-7.5, stream.gainDb!!, 0.0)
        assertFalse(stream.trial)
    }

    @Test
    fun officialAudioUsesHttpsWithoutChangingSignedPathQueryOrFragment() {
        val url = "http://p405.music.126.net/a%2Fb+clip.flac?auth=X%2FY+Z&time=123#part"
        assertEquals(
            "https://p405.music.126.net/a%2Fb+clip.flac?auth=X%2FY+Z&time=123#part",
            PlatformAudioTransport.url(MusicPlatform.NETEASE, url),
        )
        assertEquals(
            "https://p405.music.126.net/a.flac?t=123",
            PlatformAudioTransport.url(
                MusicPlatform.NETEASE,
                "http://p405.music.126.net:80/a.flac?t=123",
            ),
        )
    }

    @Test
    fun existingTlsAndOtherPlatformsOrLookalikeHostsKeepTheirAddresses() {
        for (url in
            listOf(
                "https://p405.music.126.net/a.flac?auth=1",
                "http://music.126.net.example.com/a.flac",
                "http://cdn.example.com/a.flac",
                "http://music.126.net:8123/a.flac",
                "http://user@music.126.net/a.flac",
            )) assertEquals(url, PlatformAudioTransport.url(MusicPlatform.NETEASE, url))
        val kugou = "http://music.126.net/a.flac"
        assertEquals(kugou, PlatformAudioTransport.url(MusicPlatform.KUGOU, kugou))
    }

    @Test
    fun playbackUsesTheSameClientIdentityAsNeteaseAddressRequests() {
        val headers = PlatformAudioTransport.headers(MusicPlatform.NETEASE)
        assertEquals(PlatformAudioTransport.NETEASE_USER_AGENT, headers["User-Agent"])
        assertTrue(headers.getValue("User-Agent").contains("Chrome/"))
        assertEquals("https://music.163.com/", headers["Referer"])
        assertFalse(headers.containsKey("Cookie"))
    }

    @Test
    fun serverDiagnosticsReturnTheErrorIdentifierWithoutResourceUrlsOrTokens() {
        val xml =
            "<Error><Code>AccessDenied</Code><Resource>https://cdn.example.com/a?secret=private</Resource></Error>"
        assertEquals("AccessDenied", audioServerErrorCode(xml.toByteArray()))
        assertEquals(
            "SignatureDoesNotMatch",
            audioServerErrorCode(
                "{\"code\":\"SignatureDoesNotMatch\",\"token\":\"private\"}".toByteArray()
            ),
        )
        assertEquals(
            "HTTPSRequired",
            audioServerErrorCode("Only HTTPS requests are allowed".toByteArray()),
        )
        assertNull(audioServerErrorCode("https://cdn.example.com/a?secret=private".toByteArray()))
    }
}
