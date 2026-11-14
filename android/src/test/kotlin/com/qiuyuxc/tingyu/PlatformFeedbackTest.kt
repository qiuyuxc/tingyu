package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformFeedbackTest {
    private val profile = PlatformProfile(MusicPlatform.KUGOU, "42", "听友")
    private val session =
        "{\"edition\":\"lite\",\"appid\":\"3116\",\"userid\":\"42\",\"token\":\"test-token\",\"device\":\"device\"}"
    private val hash = "8E10D8825DDE03BCABBDE13E5A4150D2"

    @Test
    fun statusZeroSongsUseConceptAuthorizationAndTheSameDevice() {
        val requests = mutableListOf<LoginRequest>()
        val source =
            PlatformLibraryClient(
                profile,
                session,
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    val json =
                        when (URL(request.url).path) {
                            "/v1/get_union_vip" -> """{"status":1,"data":{"vip_type":0}}"""
                            "/v5/url" -> "{\"status\":0,\"error_code\":0}"
                            "/v1/user_verify" ->
                                "{\"status\":1,\"data\":{\"auth\":\"account-auth\"}}"
                            "/v1/authorization" -> {
                                assertEquals("account-auth", query(request)["authorization"])
                                "{\"status\":1,\"data\":{\"auth\":\"song-auth\",\"open_time\":123}}"
                            }
                            "/tracker/v5/url" ->
                                "{\"status\":1,\"url\":[\"https://cdn.example.com/song.ogg\"],\"quality\":\"320\",\"bitRate\":320}"
                            else -> error("Unexpected request")
                        }
                    LoginResponse(JSONObject(json))
                },
            )
        val stream =
            source.stream(
                PlatformTrack(MusicPlatform.KUGOU, hash, "音乐", albumId = "12", audioId = "34"),
                AudioQuality.HIGH,
            )
        assertEquals("https://cdn.example.com/song.ogg", stream.url)
        assertEquals(AudioQuality.HIGH, stream.quality)
        assertEquals(5, requests.size)
        assertEquals(1, requests.map { query(it)["dfid"] }.distinct().size)
        val tracker = query(requests.last())
        assertEquals("song-auth", tracker["auth"])
        assertEquals("320", tracker["quality"])
        assertEquals("3116", tracker["appid"])
        assertEquals("1", tracker["need_ogg"])
    }

    @Test
    fun noPlaybackRightsDoNotTellTheUserTheirLoginExpired() {
        val source =
            PlatformLibraryClient(
                profile,
                session,
                LoginTransport { LoginResponse(JSONObject("{\"status\":0,\"error_code\":0}")) },
            )
        val error =
            assertThrows(IllegalStateException::class.java) {
                source.stream(PlatformTrack(MusicPlatform.KUGOU, hash, "音乐"))
            }
        assertTrue(error.message!!.contains("播放地址"))
        assertFalse(error.message!!.contains("重新登录"))
    }

    @Test
    fun missingFavoritesSummaryConfirmsTheDefaultCollectionBeforeReadingItsSongs() {
        val source =
            PlatformLibraryClient(
                profile,
                session,
                LoginTransport { request ->
                    val json =
                        when (URL(request.url).path) {
                            "/v7/get_all_list" ->
                                "{\"status\":1,\"data\":{\"info\":[],\"total\":0}}"
                            "/v3/get_list_info" ->
                                """{"status":1,"data":[{"listid":2,"type":0,"is_def":2,"name":"听友喜欢的音乐","count":1,"list_create_userid":42,"global_collection_id":"collection_3_42_2_0"}]}"""
                            else -> {
                                assertEquals("2", JSONObject(request.body!!).getString("listid"))
                                "{\"status\":1,\"data\":{\"info\":[{\"hash\":\"$hash\",\"filename\":\"艺人 - 音乐\",\"fileid\":7}],\"total\":1}}"
                            }
                        }
                    LoginResponse(JSONObject(json))
                },
            )
        val library = source.loadLibrary()
        assertTrue(library.favoritesReady)
        assertEquals("2", library.favoritePlaylist!!.listId)
        assertEquals(1, library.favoritePlaylist!!.count)
        assertEquals(1, library.favorites.size)
    }

    @Test
    fun unavailableFavoritesDoNotEraseTheOtherPlaylists() {
        val source =
            PlatformLibraryClient(
                profile,
                session,
                LoginTransport { request ->
                    LoginResponse(
                        JSONObject(
                            if (URL(request.url).path == "/v7/get_all_list")
                                "{\"status\":1,\"data\":{\"info\":[{\"listid\":9,\"name\":\"歌单\",\"type\":0}],\"total\":1}}"
                            else "{\"status\":0,\"error_code\":0}"
                        )
                    )
                },
            )
        val library = source.loadLibrary()
        assertEquals(1, library.playlists.size)
        assertFalse(library.favoritesReady)
        assertTrue(library.notices.isNotEmpty())
    }

    @Test
    fun favoritesOwnershipAndRankNestedMetadataStayCorrect() {
        val favorite =
            PlatformJson.kugouPlaylist(
                JSONObject("{\"listid\":1,\"name\":\"我的喜欢\",\"type\":2,\"userid\":42}"),
                "42",
            )
        assertTrue(favorite.favorites)
        assertTrue(favorite.owned)
        assertEquals(0, favorite.type)
        assertFalse(
            PlatformJson.kugouPlaylist(
                    JSONObject("{\"listid\":1,\"name\":\"我喜欢\",\"type\":0,\"userid\":99}"),
                    "42",
                )
                .favorites
        )
        val song =
            PlatformJson.kugouTrack(
                JSONObject(
                    "{\"base\":{\"songname\":\"音乐\",\"author_name\":\"艺人\",\"album_audio_id\":34},\"audio_info\":{\"hash\":\"$hash\",\"timelength\":123000},\"album_info\":{\"cover\":\"http://imge.kugou.com/music/{size}/a.jpg\"}}"
                )
            )!!
        assertEquals("音乐", song.title)
        assertEquals("艺人", song.artist)
        assertEquals("34", song.audioId)
        assertEquals(123000L, song.durationMs)
        assertTrue(song.cover.contains("/480/"))
    }

    @Test
    fun commentDatesAvatarsAndNestedLikeCountsUseRealResponseFields() {
        val comment =
            PlatformDiscoveryJson.kugouComment(
                JSONObject(
                    "{\"id\":7,\"user_name\":\"听友\",\"content\":\"好听\",\"addtime\":\"2026-11-09 00:51:10\",\"user_pic\":\"http://imge.kugou.com/a.jpg\",\"like\":{\"count\":139}}"
                )
            )
        assertEquals(139L, comment.likes)
        assertTrue(comment.time > 0)
        assertTrue(comment.avatar.startsWith("https://"))
    }

    private fun query(request: LoginRequest) =
        URL(request.url).query.split('&').associate { entry ->
            val part = entry.split('=', limit = 2)
            URLDecoder.decode(part[0], "UTF-8") to URLDecoder.decode(part[1], "UTF-8")
        }
}
