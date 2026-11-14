package com.qiuyuxc.tingyu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformAudioTest {
    @Test
    fun missingRightsDoNotBecomeVipOrFreeLabels() {
        assertEquals(TrackAccess.UNKNOWN, PlatformAudioJson.neteaseAccess(JSONObject()))
        assertEquals(TrackAccess.UNKNOWN, PlatformAudioJson.kugouAccess(JSONObject()))
        assertEquals(TrackAccess.VIP, PlatformAudioJson.neteaseAccess(JSONObject("{\"fee\":1}")))
        assertEquals(TrackAccess.PAID, PlatformAudioJson.neteaseAccess(JSONObject("{\"fee\":4}")))
        assertEquals(TrackAccess.FREE, PlatformAudioJson.neteaseAccess(JSONObject("{\"fee\":0}")))
        assertEquals(
            TrackAccess.UNAVAILABLE,
            PlatformAudioJson.neteaseAccess(JSONObject("{\"fee\":1}"), JSONObject("{\"st\":-1}")),
        )
    }

    @Test
    fun conceptSearchCapitalizedFieldsPreservePaidAndVipFlags() {
        assertEquals(
            TrackAccess.PAID,
            PlatformAudioJson.kugouAccess(JSONObject("{\"Privilege\":10,\"PayType\":3}")),
        )
        assertEquals(
            TrackAccess.VIP,
            PlatformAudioJson.kugouAccess(JSONObject("{\"Privilege\":10,\"PayType\":1}")),
        )
    }

    @Test
    fun neteaseSubscriptionKeepsItsActualLevelAndExpiry() {
        val json =
            JSONObject(
                "{\"data\":{\"redVipLevel\":4,\"associator\":{\"vipCode\":100,\"expireTime\":1795000000000}}}"
            )
        val active = PlatformAudioJson.neteaseMembership(json, 1794000000000)
        assertTrue(active.active!!)
        assertEquals(4, active.level)
        assertEquals(1795000000000L, active.expiresAt)
        val expired = PlatformAudioJson.neteaseMembership(json, 1796000000000)
        assertFalse(expired.active!!)
    }

    @Test
    fun conceptEightDigitDatesAndUnixSecondsAreBothSupported() {
        val date =
            PlatformAudioJson.kugouMembership(
                JSONObject(
                    "{\"data\":{\"vip_type\":6,\"vip_end_time\":\"20261113\",\"vip_level\":3}}"
                ),
                0,
            )
        assertTrue(date.active!!)
        assertEquals("超级 VIP", date.tier)
        assertEquals(3, date.level)
        assertTrue(date.expiryText(0).startsWith("2026-11-13"))
        val seconds =
            PlatformAudioJson.kugouMembership(
                JSONObject("{\"data\":{\"vip_type\":1,\"vip_end_time\":1795000000}}"),
                0,
            )
        assertEquals(1795000000000L, seconds.expiresAt)
    }

    @Test
    fun incompleteMembershipResponsesRemainUnknown() {
        val unknown = PlatformAudioJson.kugouMembership(JSONObject("{\"status\":1,\"data\":{}}"))
        assertNull(unknown.active)
        assertNull(unknown.expiresAt)
        assertNull(unknown.level)
        val ordinary = PlatformAudioJson.kugouMembership(JSONObject("{\"data\":{\"vip_type\":0}}"))
        assertFalse(ordinary.active!!)
        val malformed =
            PlatformAudioJson.kugouMembership(
                JSONObject("{\"data\":{\"vip_type\":1,\"vip_end_time\":\"2026-99-99\"}}")
            )
        assertNull(malformed.expiresAt)
    }

    @Test
    fun streamQualityComesFromTheResponseRatherThanThePreference() {
        val stream =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "42",
                JSONObject(
                    "{\"data\":[{\"id\":42,\"url\":\"https://cdn.example.com/a.flac\",\"level\":\"lossless\",\"br\":784234,\"type\":\"flac\"}]}"
                ),
            )
        assertEquals(AudioQuality.LOSSLESS, stream.quality)
        assertEquals(784234, stream.bitrate)
        assertEquals("flac", stream.format)
        val concept =
            PlatformJson.stream(
                MusicPlatform.KUGOU,
                "hash",
                JSONObject(
                    "{\"url\":[\"https://cdn.example.com/a.mp3\"],\"bitRate\":128,\"is_free_part\":1}"
                ),
            )
        assertTrue(concept.trial)
        assertEquals(128000, concept.bitrate)
        assertNull(concept.quality)
    }

    @Test
    fun emptyMembershipWrappersCannotTurnAnUnknownAccountIntoANonMember() {
        for (data in
            listOf(
                """{"busi_vip":{}}""",
                """{"busi_vip":[],"vip_type":"unknown"}""",
                """{"union_vip":{},"status":1}""",
            )) {
            val membership =
                PlatformAudioJson.kugouMembership(JSONObject().put("data", JSONObject(data)))
            assertNull(membership.active)
            val track = PlatformTrack(MusicPlatform.KUGOU, "A".repeat(32), "歌曲")
            assertEquals(
                listOf(AudioQuality.KUGOU_TAPE, AudioQuality.HIRES, AudioQuality.STANDARD),
                KugouQualityFallback.candidates(track, AudioQuality.KUGOU_TAPE, membership),
            )
        }
    }

    @Test
    fun neteaseSvipWinsOverALongerVipSubscriptionAndKeepsBothDates() {
        val now = 1794000000000L
        val membership =
            PlatformAudioJson.neteaseMembership(
                JSONObject(
                    """{"data":{"redVipLevel":6,"associator":{"vipCode":100,"expireTime":1799000000000},"redplus":{"vipCode":200,"expireTime":1795000000000}}}"""
                ),
                now,
            )
        assertEquals("黑胶 SVIP", membership.tier)
        assertEquals(MembershipTier.SVIP, membership.badgeTier(now))
        assertEquals(6, membership.level)
        assertEquals(1795000000000L, membership.expiresAt)
        assertEquals(
            1799000000000L,
            membership.subscriptions.single { it.kind == MembershipTier.VIP }.expiresAt,
        )
        assertEquals(
            1795000000000L,
            membership.subscriptions.single { it.kind == MembershipTier.SVIP }.expiresAt,
        )
    }

    @Test
    fun cachedSvipFallsBackToAnUnexpiredVipAtItsOwnExpiry() {
        val membership =
            PlatformAudioJson.neteaseMembership(
                JSONObject(
                    """{"data":{"associator":{"expireTime":1799000000000},"redplus":{"expireTime":1795000000000}}}"""
                ),
                1794000000000L,
            )
        val display = membership.displayAt(1795000000000L)
        assertTrue(membership.activeAt(1795000000000L)!!)
        assertEquals("黑胶 VIP", display.tier)
        assertEquals(MembershipTier.VIP, membership.badgeTier(1795000000000L))
        assertEquals(1799000000000L, display.expiresAt)
        assertTrue(
            display.subscriptions
                .single { it.kind == MembershipTier.SVIP }
                .expiryText(1795000000000L)
                .endsWith("已到期")
        )
    }

    @Test
    fun bothNeteaseEndpointResponsesContributeTheirMembershipFields() {
        val client =
            JSONObject(
                """{"data":{"redVipLevel":5,"associator":{"vipCode":100,"expireTime":1799000000000},"redplus":null}}"""
            )
        val front =
            JSONObject(
                """{"data":{"redVipLevel":5,"redplus":{"vipCode":200,"expireTime":1795000000}}}"""
            )
        val membership = PlatformAudioJson.neteaseMembership(listOf(client, front), 1794000000000L)
        assertEquals(MembershipTier.SVIP, membership.badgeTier(1794000000000L))
        assertEquals(1795000000000L, membership.expiresAt)
        assertEquals(2, membership.subscriptions.size)
        assertEquals(5, membership.level)
    }

    @Test
    fun membershipCacheRoundTripPreservesBothSubscriptionsAndUnknownFields() {
        val membership =
            PlatformAudioJson.neteaseMembership(
                JSONObject(
                    """{"data":{"associator":{"expireTime":1799000000000},"redplus":{"expireTime":1795000000000}}}"""
                ),
                1794000000000L,
            )
        assertEquals(
            membership,
            PlatformMembership.fromJson(JSONObject(membership.toJson().toString())),
        )
        val unknown =
            PlatformAudioJson.neteaseMembership(
                JSONObject("""{"data":{"associator":{},"redplus":null}}""")
            )
        assertNull(unknown.active)
        assertNull(unknown.badgeTier())
        assertEquals(2, unknown.subscriptions.size)
        assertTrue(unknown.subscriptions.all { it.expiresAt == null && it.active == null })
        assertEquals(unknown, PlatformMembership.fromJson(JSONObject(unknown.toJson().toString())))
    }

    @Test
    fun aPartialRefreshKeepsKnownSvipButAConfirmedExpiryReplacesIt() {
        val now = System.currentTimeMillis()
        val previous =
            PlatformMembership.fromSubscriptions(
                listOf(
                    MembershipSubscription(MembershipTier.VIP, true, now + 100000),
                    MembershipSubscription(MembershipTier.SVIP, true, now + 50000),
                )
            )
        val partial =
            PlatformMembership.fromSubscriptions(
                listOf(
                    MembershipSubscription(MembershipTier.VIP, true, now + 100000),
                    MembershipSubscription(MembershipTier.SVIP, null),
                )
            )
        assertEquals(MembershipTier.SVIP, partial.retainKnownSubscriptions(previous).badgeTier(now))
        val expired =
            PlatformMembership.fromSubscriptions(
                listOf(
                    MembershipSubscription(MembershipTier.VIP, true, now + 100000),
                    MembershipSubscription(MembershipTier.SVIP, false, now - 1),
                )
            )
        assertEquals(MembershipTier.VIP, expired.retainKnownSubscriptions(previous).badgeTier(now))
        assertEquals(
            now - 1,
            expired
                .retainKnownSubscriptions(previous)
                .subscriptions
                .single { it.kind == MembershipTier.SVIP }
                .expiresAt,
        )
    }

    @Test
    fun olderMembershipCachesStillLoadWithoutInventingAnotherSubscription() {
        val legacy =
            PlatformMembership.fromJson(
                JSONObject(
                    """{"tier":"黑胶 VIP","active":true,"expiresAt":1799000000000,"level":4,"checkedAt":1794000000000}"""
                )
            )
        assertEquals("黑胶 VIP", legacy.tier)
        assertTrue(legacy.subscriptions.isEmpty())
        assertEquals(MembershipTier.VIP, legacy.badgeTier(1794000000000L))
    }
}
