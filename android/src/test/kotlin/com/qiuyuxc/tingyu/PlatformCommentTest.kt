package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformCommentTest {
    private val netease = PlatformTrack(MusicPlatform.NETEASE, "42", "歌曲")
    private val kugou = PlatformTrack(MusicPlatform.KUGOU, "A".repeat(32), "歌曲", audioId = "9")
    private val session =
        """{"edition":"lite","appid":"3116","userid":"42","token":"test-token","device":"device","mid":"123"}"""
    private val comment = PlatformComment("7", "听友", "好听", resourceId = "99")

    @Test
    fun neteasePublishingAndReplyingUseTheSongThreadAndCurrentAccount() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=test-session; __csrf=csrf",
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    assertTrue(request.cookie.contains("MUSIC_U=test-session"))
                    LoginResponse(
                        JSONObject(
                            """{"code":200,"comment":{"commentId":8,"content":"已提交","user":{"userId":42,"nickname":"听友"}}}"""
                        )
                    )
                },
            )
        val published = client.postComment(netease, "  评论\n与 \"引号\"  ")
        client.postComment(netease, "回复", comment)
        assertTrue(requests[0].url.endsWith("/resource/comments/add"))
        assertTrue(requests[1].url.endsWith("/resource/comments/reply"))
        val add = eapiBody(requests[0])
        assertEquals("R_SO_4_42", add.getString("threadId"))
        assertEquals("评论\n与 \"引号\"", add.getString("content"))
        assertFalse(add.has("commentId"))
        assertEquals("7", eapiBody(requests[1]).getString("commentId"))
        assertEquals("8", published!!.id)
        assertEquals("42", published.authorId)
    }

    @Test
    fun neteaseLikeAndUnlikeBothRequireConfirmedSuccess() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=test-session",
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    LoginResponse(
                        JSONObject(
                            if (requests.size <= 2) """{"code":200}""" else """{"code":301}"""
                        )
                    )
                },
            )
        client.likeComment(netease, comment, true)
        client.likeComment(netease, comment, false)
        assertTrue(requests[0].url.endsWith("/weapi/v1/comment/like"))
        assertTrue(requests[1].url.endsWith("/weapi/v1/comment/unlike"))
        assertTrue(requests.all { it.cookie == "MUSIC_U=test-session" })
        assertThrows(IllegalArgumentException::class.java) {
            client.likeComment(netease, comment, true)
        }
    }

    @Test
    fun loggedOutAccountsCannotSendCommentsOrLikesToEitherPlatform() {
        for (track in listOf(netease, kugou)) {
            var requests = 0
            val client =
                PlatformDiscoveryClient(
                    track.platform,
                    transport =
                        LoginTransport {
                            requests++
                            error("An unauthenticated mutation must not be sent")
                        },
                )
            assertThrows(IllegalArgumentException::class.java) { client.postComment(track, "评论") }
            assertThrows(IllegalArgumentException::class.java) {
                client.likeComment(track, comment, true)
            }
            assertEquals(0, requests)
        }
    }

    @Test
    fun invalidDraftsAndMissingReplyIdsAreRejectedBeforeSending() {
        var requests = 0
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=test-session",
                LoginTransport {
                    requests++
                    error("Invalid content must not be sent")
                },
            )
        for (draft in listOf(" ", "字".repeat(501))) assertThrows(
            IllegalArgumentException::class.java
        ) {
            client.postComment(netease, draft)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.postComment(netease, "回复", comment.copy(id = ""))
        }
        assertEquals(0, requests)
    }

    @Test
    fun failedPublishingKeepsKnownCountsAndSuccessfulPublishingInvalidatesThem() {
        val track = netease.copy(id = "comment-count-test")
        var accepted = false
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=test-session",
                LoginTransport {
                    LoginResponse(
                        JSONObject(if (accepted) """{"code":200}""" else """{"code":500}""")
                    )
                },
            )
        DiscoveryStore.publishCommentCount(track, 9)
        assertThrows(IllegalArgumentException::class.java) { client.postComment(track, "评论") }
        assertEquals(9L, DiscoveryStore.commentCount(track))
        accepted = true
        client.postComment(track, "评论")
        assertNull(DiscoveryStore.commentCount(track))
    }

    @Test
    fun conceptPublishingCanResolveAnEmptyCommentPoolAndReplyToAnExistingComment() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                session,
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    if (URL(request.url).path == "/mcomment/v1/cmtlist")
                        LoginResponse(
                            JSONObject(
                                """{"status":1,"data":{"childrenid":99,"list":[],"count":0}}"""
                            )
                        )
                    else {
                        val values = query(request)
                        assertEquals("42", values["kugouid"])
                        assertEquals("test-token", values["clienttoken"])
                        assertEquals("3116", values["appid"])
                        assertEquals("99", values["childrenid"])
                        assertEquals("m.comment.service.kugou.com", request.headers["x-router"])
                        LoginResponse(JSONObject("""{"status":1,"error_code":0}"""))
                    }
                },
            )
        client.postComment(kugou, "第一条评论")
        client.postComment(kugou, "回复内容", comment)
        assertEquals(3, requests.size)
        assertEquals("commentsv3/add", query(requests[1])["r"])
        val body = JSONObject(requests[1].body!!).getJSONObject("data")
        assertEquals("9", body.getString("album_audio_id"))
        assertEquals("第一条评论", body.getString("content"))
        assertEquals("commentsv2/reply", query(requests[2])["r"])
        assertEquals("7", query(requests[2])["tid"])
        assertEquals("回复内容//@听友:好听", query(requests[2])["content"])
        assertNotNull(requests[2].body)
    }

    @Test
    fun conceptLikesAndUnlikesUseSeparateRoutesAndTheActualReturnedState() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                session,
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    assertNull(request.body)
                    assertTrue(request.cookie.isEmpty())
                    val values = query(request)
                    assertEquals("7", values["object"])
                    assertEquals("99", values["childrenid"])
                    assertEquals("9", values["mixsongid"])
                    assertEquals("42", values["kugouid"])
                    assertEquals("test-token", values["clienttoken"])
                    LoginResponse(
                        JSONObject(
                            if (requests.size == 1) """{"status":1,"islike":1,"likenum":10}"""
                            else """{"status":1,"islike":0,"likenum":9}"""
                        )
                    )
                },
            )
        assertEquals(CommentLikeResult(true, 10), client.likeComment(kugou, comment, true))
        assertEquals(CommentLikeResult(false, 9), client.likeComment(kugou, comment, false))
        assertTrue(requests[0].url.contains("/like.service/like/handlelikeV3?"))
        assertTrue(requests[1].url.contains("/like.service/like/unlike?"))
        assertFalse(client.likeComment(kugou, comment, true).liked)
    }

    @Test
    fun conceptMutationsRejectMissingAcknowledgmentsAndAllErrorCodeSpellings() {
        for (response in
            listOf(
                """{"error_code":0}""",
                """{"status":0,"err_code":0}""",
                """{"status":1,"err_code":20028}""",
                """{"status":1,"error_code":0,"errcode":20028}""",
            )) {
            val client =
                PlatformDiscoveryClient(
                    MusicPlatform.KUGOU,
                    session,
                    LoginTransport {
                        LoginResponse(JSONObject(response))
                    },
                )
            assertThrows(IllegalArgumentException::class.java) {
                client.likeComment(kugou, comment, true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.postComment(kugou, "回复", comment)
            }
        }
    }

    @Test
    fun conceptCommentSigningMatchesFixedWireVectors() {
        val account = JSONObject(session)
        val now = 1700000000000L
        val like = PlatformProtocol.kugouCommentLike(account, "7", "99", "9", true, now)
        assertEquals("1700000000", query(like)["timestamp"])
        assertEquals("11440", query(like)["clientver"])
        assertEquals("cb59d924e034a0cf49dd79c9b0310d07", query(like)["assign"])
        assertEquals("bad0ee207bf429bd91402b77fc8f7a5b", query(like)["key"])
        val body = """{"data":{"content":"评论","album_audio_id":"9","images":[]}}"""
        val add =
            PlatformProtocol.kugouComment(
                "commentsv3/add",
                account,
                mapOf("childrenid" to "99"),
                body,
                now,
            )
        val reply =
            PlatformProtocol.kugouComment(
                "commentsv2/reply",
                account,
                mapOf("childrenid" to "99"),
                "",
                now,
            )
        assertEquals("e7ef8e087c9ef10264db3e3f800967f4", query(add)["key"])
        assertEquals("1e147fdee80c20bcf6e0a1d8681c84b9", query(reply)["key"])
        assertEquals(body, add.body)
    }

    @Test
    fun commentParsingKeepsKnownLikedStatesSeparateFromUnknownStates() {
        assertEquals(
            true,
            PlatformDiscoveryJson.neteaseComment(JSONObject("""{"liked":true}""")).liked,
        )
        assertNull(PlatformDiscoveryJson.neteaseComment(JSONObject()).liked)
        val liked =
            PlatformDiscoveryJson.kugouComment(
                JSONObject(
                    """{"id":7,"like":{"status":1,"count":9},"special_child_id":99,"user_id":42}"""
                )
            )
        assertEquals(true, liked.liked)
        assertEquals(9L, liked.likes)
        assertEquals("99", liked.resourceId)
        assertEquals("42", liked.authorId)
        assertEquals(
            false,
            PlatformDiscoveryJson.kugouComment(JSONObject("""{"is_like":0}""")).liked,
        )
        assertEquals(
            true,
            PlatformDiscoveryJson.kugouComment(JSONObject("""{"like":{"haslike":true}}""")).liked,
        )
        assertEquals(
            false,
            PlatformDiscoveryJson.kugouComment(JSONObject("""{"like":{"haslike":0}}""")).liked,
        )
        assertNull(PlatformDiscoveryJson.kugouComment(JSONObject()).liked)
        assertNull(
            PlatformDiscoveryJson.kugouComment(
                    JSONObject("""{"is_like":null,"like":{"status":"unknown"}}""")
                )
                .liked
        )
    }

    private fun query(request: LoginRequest): Map<String, String> =
        URL(request.url).query.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to
                URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }

    private fun eapiBody(request: LoginRequest): JSONObject {
        val encrypted =
            request.body!!
                .removePrefix("params=")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val plain =
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                String(doFinal(encrypted), Charsets.UTF_8)
            }
        return JSONObject(plain.split("-36cd479b6b5-")[1])
    }
}
