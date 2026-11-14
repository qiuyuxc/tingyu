package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouQualityFallbackTest {
    private val requests = mutableListOf<LoginRequest>()
    private val track = PlatformTrack(MusicPlatform.KUGOU, "A".repeat(32), "歌曲", audioId = "42")
    private val preferred = AudioQuality.KUGOU_TAPE

    private fun client(
        member: Boolean? = true,
        reply: (quality: String, authorized: Boolean) -> JSONObject,
    ) =
        PlatformLibraryClient(
            PlatformProfile(MusicPlatform.KUGOU, "42", "听友"),
            """{"edition":"lite","appid":"3116","userid":"42","token":"fixture","device":"fixture"}""",
            LoginTransport { request ->
                request.validate()
                requests += request
                val response =
                    when (URL(request.url).path) {
                        "/v1/get_union_vip" ->
                            JSONObject()
                                .put("status", 1)
                                .put(
                                    "data",
                                    JSONObject().apply {
                                        if (member != null) put("vip_type", if (member) 1 else 0)
                                    },
                                )
                        "/v1/user_verify" ->
                            JSONObject("""{"status":1,"data":{"auth":"account"}}""")
                        "/v1/authorization" ->
                            JSONObject("""{"status":1,"data":{"auth":"song","open_time":123}}""")
                        "/v5/url",
                        "/tracker/v5/url" ->
                            reply(
                                query(request).getValue("quality"),
                                URL(request.url).path.startsWith("/tracker"),
                            )
                        else -> error("Unexpected endpoint")
                    }
                LoginResponse(response)
            },
        )

    private fun missing() = JSONObject("""{"status":0,"error_code":0}""")

    private fun stream(quality: String, trial: Boolean = false) =
        JSONObject()
            .put("status", 1)
            .put("url", "https://cdn.example.com/$quality.flac")
            .put("quality", quality)
            .put("is_free_part", if (trial) 1 else 0)

    private fun query(request: LoginRequest): Map<String, String> =
        URL(request.url).query.split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }

    private fun qualities() = requests.mapNotNull { query(it)["quality"] }

    @Test
    fun aMemberTriesHiResBeforeStandardAndGetsTheDefaultSuggestion() {
        val source = client { quality, _ -> if (quality == "high") stream("high") else missing() }
        val result = source.stream(track, preferred)
        assertEquals("/v1/get_union_vip", URL(requests.first().url).path)
        assertEquals(listOf("viper_tape", "viper_tape", "high"), qualities())
        assertEquals(AudioQuality.HIRES, result.quality)
        assertEquals(AudioQuality.HIRES, result.defaultSuggestion)
        assertTrue(result.notice!!.contains("Hi-Res"))
        assertEquals(1, requests.map { query(it)["dfid"] }.distinct().size)
        assertTrue(requests.all { query(it)["dfid"]!!.length == 24 })
    }

    @Test
    fun nonMembersGoToStandardWithoutRequestingMemberHiRes() {
        val source =
            client(member = false) { quality, _ ->
                if (quality == "128") stream("128") else missing()
            }
        val result = source.stream(track, preferred)
        assertEquals(listOf("viper_tape", "viper_tape", "128"), qualities())
        assertEquals(AudioQuality.STANDARD, result.quality)
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun unknownMembershipCanResolveHiResButCannotClaimMembershipOrSuggestADefault() {
        val source =
            client(member = null) { quality, _ ->
                if (quality == "high") stream("high") else missing()
            }
        val result = source.stream(track, preferred)
        assertEquals(AudioQuality.HIRES, result.quality)
        assertNull(source.resolvedMembership!!.active)
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun expiredMembershipIsNotTreatedAsAnActiveSubscription() {
        val source = client { quality, _ -> if (quality == "128") stream("128") else missing() }
        val expired = PlatformMembership("VIP", true, expiresAt = System.currentTimeMillis() - 1000)
        val result = source.stream(track, preferred, expired)
        assertFalse("high" in qualities())
        assertEquals(AudioQuality.STANDARD, result.quality)
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun anExplicitlyUnavailableHiResResourceIsSkippedEvenForMembers() {
        val source = client { quality, _ -> if (quality == "128") stream("128") else missing() }
        val unavailable =
            track.copy(
                qualities = listOf(QualityAccess(AudioQuality.HIRES, TrackAccess.UNAVAILABLE))
            )
        assertEquals(AudioQuality.STANDARD, source.stream(unavailable, preferred).quality)
        assertFalse("high" in qualities())
    }

    @Test
    fun anExplicitlyFreeHiResResourceCanStillBeTriedByANonMember() {
        val source =
            client(member = false) { quality, _ ->
                if (quality == "high") stream("high") else missing()
            }
        val free =
            track.copy(qualities = listOf(QualityAccess(AudioQuality.HIRES, TrackAccess.FREE)))
        val result = source.stream(free, preferred)
        assertEquals(AudioQuality.HIRES, result.quality)
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun unavailableHiResFallsBackToStandardWithoutRepeatingItAsACandidate() {
        val source = client { quality, _ -> if (quality == "128") stream("128") else missing() }
        val result = source.stream(track, AudioQuality.HIRES)
        assertEquals(listOf("high", "high", "128"), qualities())
        assertEquals(AudioQuality.STANDARD, result.quality)
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun aSilentStandardDowngradeStillAllowsAMemberToGetHiRes() {
        val source = client { quality, _ -> stream(if (quality == "high") "high" else "128") }
        val result = source.stream(track, preferred)
        assertEquals(AudioQuality.HIRES, result.quality)
        assertEquals(AudioQuality.HIRES, result.defaultSuggestion)
        assertFalse("128" in qualities())
    }

    @Test
    fun theAlreadyResolvedStandardUrlIsKeptWhenHiResCannotPlay() {
        val source = client { quality, _ ->
            check(quality != "128") { "An available standard URL should be reused" }
            if (quality == "viper_tape") stream("128") else missing()
        }
        val result = source.stream(track, preferred)
        assertEquals(AudioQuality.STANDARD, result.quality)
        assertTrue("high" in qualities())
        assertNull(result.defaultSuggestion)
    }

    @Test
    fun aFullHiResSongIsPreferredToATrialOfTheRequestedQuality() {
        val source = client { quality, _ -> stream(quality, trial = quality != "high") }
        val result = source.stream(track, preferred)
        assertEquals(AudioQuality.HIRES, result.quality)
        assertFalse(result.trial)
    }

    @Test
    fun aHiResRequestWithoutAnActualQualityFieldDoesNotTriggerTheDefaultPrompt() {
        val source = client { quality, _ ->
            if (quality == "high") stream("high").apply { remove("quality") } else missing()
        }
        val result = source.stream(track, preferred)
        assertNull(result.quality)
        assertNull(result.defaultSuggestion)
        assertTrue(result.notice!!.contains("待平台确认"))
    }

    @Test
    fun membershipChecksAreReusedUntilTheCachedResultIsStale() {
        val source = client { quality, _ -> stream(quality) }
        source.stream(track, preferred)
        source.stream(track, preferred, source.resolvedMembership)
        assertEquals(1, requests.count { URL(it.url).path == "/v1/get_union_vip" })
        val stale =
            PlatformMembership(
                "普通账号",
                false,
                checkedAt = System.currentTimeMillis() - 16 * 60 * 1000,
            )
        source.stream(track, preferred, stale)
        assertEquals(2, requests.count { URL(it.url).path == "/v1/get_union_vip" })
        assertEquals(true, source.resolvedMembership!!.activeAt())
    }

    @Test
    fun anAccountRejectionDuringHiResAuthorizationCannotFallThroughToAnOldUrl() {
        val source = client { quality, authorized ->
            when {
                quality == "viper_tape" && !authorized -> stream("128")
                quality == "high" && authorized ->
                    JSONObject("""{"status":0,"errcode":20018,"message":"账号验证失败"}""")
                else -> missing()
            }
        }
        val failure =
            assertThrows(IllegalArgumentException::class.java) { source.stream(track, preferred) }
        assertTrue(failure.message!!.contains("账号验证失败"))
        assertFalse("128" in qualities())
    }
}
