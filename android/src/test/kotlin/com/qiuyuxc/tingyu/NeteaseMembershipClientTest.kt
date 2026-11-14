package com.qiuyuxc.tingyu

import java.io.IOException
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseMembershipClientTest {
    private val profile = PlatformProfile(MusicPlatform.NETEASE, "42", "听友")

    @Test
    fun bothAuthenticatedEndpointsAreReadAndTheHigherSubscriptionIsDisplayed() {
        val paths = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val client =
            PlatformLibraryClient(
                profile,
                "MUSIC_U=test-session",
                LoginTransport { request ->
                    request.validate()
                    assertTrue(request.cookie.contains("MUSIC_U=test-session"))
                    val path = URL(request.url).path
                    paths += path
                    val data =
                        if (path.contains("/client/"))
                            JSONObject()
                                .put("associator", JSONObject().put("expireTime", now + 1000000))
                        else
                            JSONObject()
                                .put("redplus", JSONObject().put("expireTime", now + 500000))
                    LoginResponse(JSONObject().put("code", 200).put("data", data))
                },
            )
        val membership = client.membership()
        assertEquals(
            listOf(
                "/weapi/music-vip-membership/client/vip/info",
                "/weapi/music-vip-membership/front/vip/info",
            ),
            paths,
        )
        assertEquals(MembershipTier.SVIP, membership.badgeTier(now))
        assertEquals(
            now + 1000000,
            membership.subscriptions.single { it.kind == MembershipTier.VIP }.expiresAt,
        )
        assertEquals(
            now + 500000,
            membership.subscriptions.single { it.kind == MembershipTier.SVIP }.expiresAt,
        )
    }

    @Test
    fun oneUnavailableEndpointDoesNotDiscardTheOtherEndpointsSubscription() {
        var requests = 0
        val expiry = System.currentTimeMillis() + 1000000
        val client =
            PlatformLibraryClient(
                profile,
                "MUSIC_U=test-session",
                LoginTransport { request ->
                    requests++
                    if (URL(request.url).path.contains("/client/"))
                        throw IOException("temporary failure")
                    LoginResponse(
                        JSONObject()
                            .put("code", 200)
                            .put(
                                "data",
                                JSONObject().put("redplus", JSONObject().put("expireTime", expiry)),
                            )
                    )
                },
            )
        val membership = client.membership()
        assertEquals(2, requests)
        assertEquals(MembershipTier.SVIP, membership.badgeTier())
        assertNull(membership.subscriptions.single { it.kind == MembershipTier.VIP }.active)
    }

    @Test
    fun twoRejectedEndpointsReportFailureInsteadOfClearingMembership() {
        var requests = 0
        val client =
            PlatformLibraryClient(
                profile,
                "MUSIC_U=test-session",
                LoginTransport {
                    requests++
                    LoginResponse(JSONObject().put("code", 301))
                },
            )
        val error = assertThrows(IllegalArgumentException::class.java) { client.membership() }
        assertEquals(2, requests)
        assertTrue(error.message.orEmpty().contains("登录已失效"))
    }
}
