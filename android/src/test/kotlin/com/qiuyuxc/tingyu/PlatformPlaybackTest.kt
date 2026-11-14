package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformPlaybackTest {
    private val platform = MusicPlatform.KUGOU
    private val device = "012345678901234567890123"
    private val track =
        PlatformTrack(
            platform,
            "DB3014A58B305E63F94ECBE4AA8C9AE1",
            "时过境迁",
            albumId = "208978587",
            audioId = "967249675",
        )
    private val requests = mutableListOf<LoginRequest>()

    private fun client(
        membership: JSONObject = JSONObject("""{"status":1,"data":{"vip_type":0}}"""),
        reply: (LoginRequest) -> JSONObject,
    ) =
        PlatformLibraryClient(
            PlatformProfile(platform, "42", "听友"),
            """{"edition":"lite","appid":"3116","userid":"42","token":"test-token","device":"device","dfid":"$device"}""",
            LoginTransport { request ->
                request.validate()
                requests += request
                LoginResponse(
                    if (URL(request.url).path == "/v1/get_union_vip") membership else reply(request)
                )
            },
        )

    @Test
    fun trackerVerificationCodeUsesSongAuthorizationAndKeepsTheExistingFingerprint() {
        val source = client { request ->
            JSONObject(
                when (URL(request.url).path) {
                    "/v5/url" ->
                        """{"status":0,"error_code":0,"errcode":20028,"error":"本次请求需要验证"}"""
                    "/v1/user_verify" -> """{"status":1,"data":{"auth":"account-auth"}}"""
                    "/v1/authorization" -> {
                        assertEquals("account-auth", query(request)["authorization"])
                        """{"status":1,"data":{"auth":"song-auth","open_time":123}}"""
                    }
                    "/tracker/v5/url" -> {
                        assertEquals("song-auth", query(request)["auth"])
                        """{"status":1,"url":["https://cdn.example.com/song.ogg"],"quality":"128","extname":"ogg","bitRate":128}"""
                    }
                    else -> error("Unexpected request")
                }
            )
        }
        val stream = source.stream(track)
        assertEquals("https://cdn.example.com/song.ogg", stream.url)
        assertEquals(AudioQuality.STANDARD, stream.quality)
        assertEquals("ogg", stream.format)
        assertEquals(5, requests.size)
        assertEquals(setOf(device), requests.map { query(it)["dfid"] }.toSet())
        requests.forEach {
            assertEquals(device, it.headers["dfid"])
            assertEquals("3116", query(it)["appid"])
            assertEquals(
                PlatformProtocol.kugouSignature(query(it) - "signature"),
                query(it)["signature"],
            )
        }
        assertEquals("1", query(requests[1])["need_ogg"])
        assertEquals("1", query(requests.last())["need_ogg"])
    }

    @Test
    fun higherQualityWithoutAUrlFallsBackToStandardAndReportsTheActualStream() {
        val source = client { request ->
            JSONObject(
                when (URL(request.url).path) {
                    "/v1/user_verify" -> """{"status":1,"data":{"auth":"account-auth"}}"""
                    "/v1/authorization" ->
                        """{"status":1,"data":{"auth":"song-auth","open_time":123}}"""
                    else ->
                        if (query(request)["quality"] == "128")
                            """{"status":1,"url":["https://cdn.example.com/standard.ogg"],"quality":"128","extname":"ogg","bitRate":128}"""
                        else """{"status":0,"error_code":0}"""
                }
            )
        }
        val stream = source.stream(track, AudioQuality.LOSSLESS)
        assertEquals("https://cdn.example.com/standard.ogg", stream.url)
        assertEquals(AudioQuality.STANDARD, stream.quality)
        assertEquals(128000, stream.bitrate)
        assertEquals("ogg", stream.format)
        assertFalse(stream.trial)
        assertTrue(stream.notice!!.contains("无损暂不可用"))
        assertEquals(listOf("flac", "flac", "128"), requests.mapNotNull { query(it)["quality"] })
        assertEquals(setOf(device), requests.map { query(it)["dfid"] }.toSet())
    }

    @Test
    fun authorizedStandardFallbackUsesTheSongTokenWhenDirectPlaybackStillNeedsVerification() {
        val source = client { request ->
            JSONObject(
                when (URL(request.url).path) {
                    "/v5/url" -> """{"status":0,"errcode":20028}"""
                    "/v1/user_verify" -> """{"status":1,"data":{"auth":"account-auth"}}"""
                    "/v1/authorization" ->
                        """{"status":1,"data":{"auth":"song-auth","open_time":456}}"""
                    else ->
                        if (query(request)["quality"] == "128") {
                            assertEquals("song-auth", query(request)["auth"])
                            assertEquals("456", query(request)["open_time"])
                            """{"status":1,"url":["https://cdn.example.com/preview.ogg"],"quality":"128","is_free_part":1}"""
                        } else """{"status":0,"error_code":0}"""
                }
            )
        }
        val stream = source.stream(track, AudioQuality.LOSSLESS)
        assertEquals(AudioQuality.STANDARD, stream.quality)
        assertTrue(stream.trial)
        assertNotNull(stream.notice)
        assertEquals(7, requests.size)
        assertEquals(
            listOf("flac", "flac", "128", "128"),
            requests.mapNotNull { query(it)["quality"] },
        )
    }

    @Test
    fun successfulPreferredQualityDoesNotTriggerAuthorizationOrLowering() {
        val source = client {
            JSONObject(
                """{"status":1,"url":["https://cdn.example.com/a.flac"],"quality":"flac","bitRate":900,"extName":"flac"}"""
            )
        }
        val stream = source.stream(track, AudioQuality.LOSSLESS)
        assertEquals(AudioQuality.LOSSLESS, stream.quality)
        assertNull(stream.notice)
        assertEquals(2, requests.size)
        assertEquals("flac", query(requests.last())["quality"])
    }

    @Test
    fun unresolvedVerificationReportsTheRealCauseWithoutSendingBlankAuthorization() {
        val source = client {
            JSONObject("""{"status":0,"errcode":20028,"error":"本次请求需要验证"}""")
        }
        val error = assertThrows(IllegalArgumentException::class.java) { source.stream(track) }
        assertTrue(error.message!!.contains("安全验证"))
        assertFalse(error.message!!.contains("播放地址"))
        assertFalse(error.message!!.contains("登录已失效"))
        assertEquals(
            listOf("/v1/get_union_vip", "/v5/url", "/v1/user_verify"),
            requests.map { URL(it.url).path },
        )
    }

    @Test
    fun accountRejectionDoesNotRequestOtherQualities() {
        val source = client {
            JSONObject("""{"status":0,"errcode":20018,"message":"账号验证失败"}""")
        }
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                source.stream(track, AudioQuality.LOSSLESS)
            }
        assertTrue(error.message!!.contains("账号验证失败"))
        assertEquals(2, requests.size)
    }

    @Test
    fun errorCodePreventsAnUnsuccessfulReplyWithAUrlFromBeingUsed() {
        val source = client {
            JSONObject(
                """{"status":1,"error_code":20018,"url":["https://cdn.example.com/a.mp3"],"message":"账号不匹配"}"""
            )
        }
        val error = assertThrows(IllegalArgumentException::class.java) { source.stream(track) }
        assertTrue(error.message!!.contains("账号不匹配"))
        assertEquals(2, requests.size)
    }

    private fun query(request: LoginRequest): Map<String, String> =
        URL(request.url).query.split('&').associate { value ->
            val parts = value.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    @Test
    fun zeroCodeFailureStillReportsTheProviderExplanation() {
        val source = client { request ->
            if (URL(request.url).path == "/v5/url")
                JSONObject("""{"status":0,"error_code":0,"error":"版权原因暂不可播放"}""")
            else JSONObject("""{"status":0,"error_code":0}""")
        }
        val error = assertThrows(IllegalArgumentException::class.java) { source.stream(track) }
        assertTrue(error.message!!.contains("版权原因暂不可播放"))
        assertFalse(error.message!!.contains("登录已失效"))
    }
}
