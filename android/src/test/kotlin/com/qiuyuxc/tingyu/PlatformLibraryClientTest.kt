package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformLibraryClientTest {
    private val netease = PlatformProfile(MusicPlatform.NETEASE, "42", "网易听友")
    private val concept = PlatformProfile(MusicPlatform.KUGOU, "42", "概念听友")
    private val conceptSession =
        "{\"edition\":\"lite\",\"appid\":\"3116\",\"token\":\"concept-token\",\"userid\":\"42\",\"device\":\"device\"}"
    private val hash = "8E10D8825DDE03BCABBDE13E5A4150D2"

    @Test
    fun mobileEncryptionAndConceptSignatureMatchIndependentReferences() {
        assertEquals("193068343998760371938075009409918566583", PlatformProtocol.kugouMid("device"))
        assertEquals(
            "params=1CA81A97B7BAEB099F29A9B99A25CD6E58E7BA5B35F25231033065C776830C0ACD212933C9432002B586ED0F458155F631B44BD2C5B733983A66E6224C0585A52777484232CB13E16B21684479BA3B9B3CF9FA119BBE8E17B07F40E02346BE3781A9B22A53CC3C44EC77569FAD1631FF",
            PlatformProtocol.eapi("/api/login/qrcode/unikey", "{\"type\":3,\"e_r\":false}"),
        )
        assertEquals(
            "9375b4857f1520ee0aa919ad85de2593",
            PlatformProtocol.kugouSignature(
                mapOf(
                    "appid" to "3116",
                    "clientver" to "11440",
                    "mid" to "device",
                    "userid" to "42",
                ),
                "{\"listid\":1}",
            ),
        )
    }

    @Test
    fun standardEditionTokenCannotBeUsedForConceptLibrary() {
        assertThrows(IllegalArgumentException::class.java) {
            PlatformLibraryClient(
                concept,
                "{\"token\":\"standard-token\",\"userid\":\"42\"}",
                LoginTransport { error("Must not send standard token") },
            )
        }
    }

    @Test
    fun accountIdMustMatchTheConceptSession() {
        assertThrows(IllegalArgumentException::class.java) {
            PlatformLibraryClient(concept.copy(userId = "43"), conceptSession)
        }
    }

    @Test
    fun neteasePlaylistPaginationIncludesTheSecondPage() {
        var calls = 0
        val source =
            PlatformLibraryClient(
                netease,
                "MUSIC_U=session",
                LoginTransport { request ->
                    request.validate()
                    assertTrue(request.cookie.contains("MUSIC_U=session"))
                    val playlists = JSONArray()
                    val range = if (calls++ == 0) 1..100 else 101..105
                    range.forEach { id ->
                        playlists.put(
                            JSONObject().put("id", id).put("name", "歌单$id").put("trackCount", 0)
                        )
                    }
                    LoginResponse(
                        JSONObject()
                            .put("code", 200)
                            .put("playlist", playlists)
                            .put("more", calls == 1)
                    )
                },
            )
        val library = source.loadLibrary()
        assertEquals(105, library.playlists.size)
        assertEquals("105", library.playlists.last().id)
        assertEquals(2, calls)
    }

    @Test
    fun neteaseTracksKeepPlaylistOrderAcrossMetadataBatchesAndPages() {
        var metadataCalls = 0
        val source =
            PlatformLibraryClient(
                netease,
                "MUSIC_U=session",
                LoginTransport { request ->
                    request.validate()
                    if (request.url.endsWith("/v6/playlist/detail")) {
                        val ids = JSONArray((1..305).map { JSONObject().put("id", it) })
                        LoginResponse(
                            JSONObject()
                                .put("code", 200)
                                .put("playlist", JSONObject().put("trackIds", ids))
                        )
                    } else {
                        assertTrue(request.url.endsWith("/v3/song/detail"))
                        val range = if (metadataCalls++ == 0) 1..300 else 301..305
                        val songs =
                            JSONArray(
                                range.reversed().map { id ->
                                    JSONObject().put("id", id).put("name", "音乐$id")
                                }
                            )
                        LoginResponse(JSONObject().put("code", 200).put("songs", songs))
                    }
                },
            )
        val playlist = PlatformPlaylist(MusicPlatform.NETEASE, "123", "歌单", 305)
        val first = source.tracks(playlist)
        val second = source.tracks(playlist, 1)
        assertTrue(first.more)
        assertFalse(second.more)
        assertEquals((1..305).map { it.toString() }, (first.items + second.items).map { it.id })
        assertEquals("音乐305", second.items.last().title)
        assertEquals(2, metadataCalls)
    }

    @Test
    fun missingMetadataPreservesTheSongIdentityAndPosition() {
        val source =
            PlatformLibraryClient(
                netease,
                "MUSIC_U=session",
                LoginTransport { request ->
                    if (request.url.endsWith("/v6/playlist/detail"))
                        LoginResponse(
                            JSONObject(
                                "{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1},{\"id\":2}]}}"
                            )
                        )
                    else
                        LoginResponse(
                            JSONObject("{\"code\":200,\"songs\":[{\"id\":2,\"name\":\"可用音乐\"}]}")
                        )
                },
            )
        val result = source.tracks(PlatformPlaylist(MusicPlatform.NETEASE, "123", "歌单", 2))
        assertEquals(listOf("1", "2"), result.items.map { it.id })
        assertEquals("暂不可用的歌曲", result.items.first().title)
    }

    @Test
    fun trackerRequestsCarryConceptParametersAndNoNeteaseCookie() {
        var sent: LoginRequest? = null
        val source =
            PlatformLibraryClient(
                concept,
                conceptSession,
                LoginTransport { request ->
                    request.validate()
                    sent = request
                    LoginResponse(
                        JSONObject("{\"status\":1,\"url\":[\"https://cdn.example.com/music.mp3\"]}")
                    )
                },
            )
        val stream =
            source.stream(
                PlatformTrack(MusicPlatform.KUGOU, hash, "音乐", albumId = "12", audioId = "34")
            )
        assertEquals("https://cdn.example.com/music.mp3", stream.url)
        val request = sent!!
        val query = query(request)
        assertEquals("3116", query["appid"])
        assertEquals("411", query["pid"])
        assertEquals("967177915", query["page_id"])
        assertEquals("193068343998760371938075009409918566583", query["mid"])
        assertEquals(24, query.getValue("dfid").length)
        assertEquals(query["dfid"], request.headers["dfid"])
        assertEquals(hash.lowercase(), query["hash"])
        assertEquals("34", query["album_audio_id"])
        assertEquals("trackercdn.kugou.com", request.headers["x-router"])
        assertTrue(request.cookie.isEmpty())
        assertEquals(PlatformProtocol.kugouSignature(query - "signature"), query["signature"])
    }

    @Test
    fun removingFavoriteUsesItsFavoriteFileIdRatherThanAnotherPlaylistFileId() {
        val sent = mutableListOf<LoginRequest>()
        val source =
            PlatformLibraryClient(
                concept,
                conceptSession,
                LoginTransport { request ->
                    request.validate()
                    sent += request
                    LoginResponse(JSONObject("{\"status\":1,\"error_code\":0}"))
                },
            )
        val favorite = PlatformTrack(MusicPlatform.KUGOU, hash, "音乐", fileId = "777")
        val other = favorite.copy(fileId = "99")
        val library =
            PlatformLibrary(
                listOf(
                    PlatformPlaylist(
                        MusicPlatform.KUGOU,
                        "global",
                        "我喜欢",
                        1,
                        favorites = true,
                        owned = true,
                        listId = "1",
                    )
                ),
                mapOf(favorite.key to favorite),
            )
        source.setFavorite(other, false, library)
        val request = sent.single()
        val body = JSONObject(request.body!!)
        assertEquals(777L, body.getJSONArray("data").getJSONObject(0).getLong("fileid"))
        assertEquals("1", body.getString("listid"))
        assertEquals("42", body.optString("userid"))
        assertEquals(
            PlatformProtocol.kugouSignature(query(request) - "signature", request.body!!),
            query(request)["signature"],
        )
        assertEquals("777", library.favorites.getValue(favorite.key).fileId)
    }

    @Test
    fun refusedFavoriteWriteDoesNotChangeTheSnapshot() {
        val track = PlatformTrack(MusicPlatform.KUGOU, hash, "音乐", fileId = "777")
        val library =
            PlatformLibrary(
                listOf(
                    PlatformPlaylist(
                        MusicPlatform.KUGOU,
                        "global",
                        "我喜欢",
                        1,
                        favorites = true,
                        owned = true,
                        listId = "1",
                    )
                ),
                mapOf(track.key to track),
            )
        val source =
            PlatformLibraryClient(
                concept,
                conceptSession,
                LoginTransport {
                    LoginResponse(JSONObject("{\"status\":0,\"error_code\":20018}"))
                },
            )
        assertThrows(IllegalArgumentException::class.java) {
            source.setFavorite(track, false, library)
        }
        assertTrue(track.key in library.favorites)
    }

    @Test
    fun fullSongTrialAndUnavailableSongRemainDistinct() {
        val full =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "1",
                JSONObject(
                    "{\"data\":[{\"id\":1,\"url\":\"https://cdn.example.com/full.mp3\",\"freeTrialInfo\":null}]}"
                ),
            )
        val trial =
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "1",
                JSONObject(
                    "{\"data\":[{\"id\":1,\"url\":\"https://cdn.example.com/trial.mp3\",\"freeTrialInfo\":{\"start\":0,\"end\":30}}]}"
                ),
            )
        assertFalse(full.trial)
        assertTrue(trial.trial)
        assertThrows(IllegalArgumentException::class.java) {
            PlatformJson.stream(
                MusicPlatform.NETEASE,
                "1",
                JSONObject("{\"data\":[{\"id\":1,\"url\":null}]}"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlatformStream("file:///private/music.mp3")
        }
    }

    @Test
    fun numericMetadataAndConceptAliasesAreDecodedWithoutLocalFiles() {
        val cloud =
            PlatformJson.neteaseTrack(
                JSONObject(
                    "{\"id\":42,\"name\":\"音乐\",\"ar\":[{\"name\":\"艺人\"}],\"al\":{\"id\":12,\"name\":\"专辑\"},\"dt\":123000}"
                )
            )
        val dog =
            PlatformJson.kugouTrack(
                JSONObject()
                    .put("filehash", hash.lowercase())
                    .put("filename", "艺人 - 音乐")
                    .put("mixsongid", 34)
                    .put("fileid", 777)
                    .put("timelen", 123000)
            )!!
        assertEquals("42", cloud.id)
        assertEquals(123000L, cloud.durationMs)
        assertEquals(hash, dog.id)
        assertEquals("音乐", dog.title)
        assertEquals("艺人", dog.artist)
        val first = Song(cloud)
        val second = Song(dog)
        assertNull(first.file)
        assertNull(second.file)
        assertTrue(first.isPlatform)
        assertTrue(second.isPlatform)
        assertNotEquals(first.key, second.key)
        assertEquals("platform:netease:42", first.key)
        assertEquals(123, first.durationSec)
    }

    @Test
    fun onlyTheUsersOwnFavoritePlaylistIsSelectedForWrites() {
        val own =
            PlatformJson.neteasePlaylist(
                JSONObject(
                    "{\"id\":1,\"name\":\"喜欢\",\"specialType\":5,\"creator\":{\"userId\":42}}"
                ),
                "42",
            )
        val other =
            PlatformJson.neteasePlaylist(
                JSONObject(
                    "{\"id\":2,\"name\":\"喜欢\",\"specialType\":5,\"creator\":{\"userId\":43}}"
                ),
                "42",
            )
        assertTrue(own.favorites)
        assertFalse(other.favorites)
        assertTrue(
            PlatformJson.kugouPlaylist(
                    JSONObject("{\"listid\":1,\"name\":\"我喜欢\",\"type\":0,\"userid\":42}"),
                    "42",
                )
                .favorites
        )
        assertFalse(
            PlatformJson.kugouPlaylist(
                    JSONObject("{\"listid\":1,\"name\":\"我喜欢\",\"type\":1,\"userid\":43}"),
                    "42",
                )
                .favorites
        )
    }

    private fun query(request: LoginRequest) =
        URL(request.url).query.split('&').associate { field ->
            field.split('=', limit = 2).let { it[0] to URLDecoder.decode(it[1], "UTF-8") }
        }
}
