package com.qiuyuxc.tingyu

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlatformControlsTest {
    private val platform = MusicPlatform.NETEASE
    private val concept = PlatformProfile(MusicPlatform.KUGOU, "42", "听友")
    private val conceptSession =
        """{"edition":"lite","appid":"3116","userid":"42","token":"test-token","device":"device"}"""

    @After
    fun clearPublicState() {
        DiscoveryStore.clear(MusicPlatform.NETEASE)
        DiscoveryStore.clear(MusicPlatform.KUGOU)
        PlatformLibraryStore.clear(MusicPlatform.KUGOU)
    }

    @Test
    fun losslessOnlySongMetadataDoesNotCutTheNeteaseMenu() {
        val song =
            PlatformJson.neteaseTrack(JSONObject("""{"id":42,"name":"歌曲","sq":{"br":900000}}"""))
        assertEquals(listOf(AudioQuality.LOSSLESS), song.qualities.map { it.quality })
        assertEquals(
            listOf(
                "standard",
                "higher",
                "exhigh",
                "lossless",
                "hires",
                "jyeffect",
                "sky",
                "dolby",
                "jymaster",
            ),
            AudioQuality.choices(platform, song).map { it.quality.netease },
        )
        assertEquals(AudioQuality.HIRES, AudioQuality.resolve(song, AudioQuality.HIRES))
        assertEquals(AudioQuality.MASTER, AudioQuality.resolve(song, AudioQuality.MASTER))
    }

    @Test
    fun defaultQualityChoicesAreIndependentOfTheCurrentSong() {
        val song =
            PlatformTrack(
                MusicPlatform.KUGOU,
                "A".repeat(32),
                "歌曲",
                qualities = listOf(QualityAccess(AudioQuality.LOSSLESS, TrackAccess.UNKNOWN)),
            )
        assertEquals(
            listOf(AudioQuality.LOSSLESS),
            AudioQuality.choices(song.platform, song).map { it.quality },
        )
        assertTrue(AudioQuality.HIRES in AudioQuality.choices(song.platform).map { it.quality })
        assertTrue(AudioQuality.MASTER in AudioQuality.choices(platform).map { it.quality })
        assertTrue(AudioQuality.choices(song.platform).none { it.quality == AudioQuality.MASTER })
        assertEquals("高清臻音", AudioQuality.SURROUND.label(platform))
        assertEquals("沉浸环绕声", AudioQuality.SPATIAL.label(platform))
    }

    @Test
    fun everyNeteaseSelectionReachesTheStreamRequestAndActualQualityComesFromTheReply() {
        val sent = mutableListOf<LoginRequest>()
        val reader =
            PlatformLibraryClient(
                PlatformProfile(platform, "42", "听友"),
                "MUSIC_U=session; __csrf=csrf",
                LoginTransport { request ->
                    request.validate()
                    sent += request
                    LoginResponse(
                        JSONObject(
                            """{"code":200,"data":[{"id":42,"url":"https://cdn.example.com/a.mp3","level":"standard","br":128000,"type":"mp3"}]}"""
                        )
                    )
                },
            )
        val song =
            PlatformJson.neteaseTrack(JSONObject("""{"id":42,"name":"歌曲","sq":{"br":900000}}"""))
        for (quality in AudioQuality.forPlatform(platform)) {
            val reply = reader.stream(song, AudioQuality.resolve(song, quality))
            val body = eapiBody(sent.last())
            assertEquals(quality.netease, body.getString("level"))
            assertEquals("42", JSONArray(body.getString("ids")).optString(0))
            assertEquals("session", body.getJSONObject("header").getString("MUSIC_U"))
            assertEquals("csrf", body.getString("csrf_token"))
            assertEquals("pc", body.getJSONObject("header").getString("os"))
            if (quality == AudioQuality.SPATIAL) assertEquals("c51", body.getString("immerseType"))
            assertEquals(AudioQuality.STANDARD, reply.quality)
            assertEquals(128000, reply.bitrate)
        }
        assertEquals(9, sent.size)
    }

    @Test
    fun removedVividQualityIsHiddenAndAnOldPreferenceBecomesLossless() {
        assertFalse(AudioQuality.VIVID in AudioQuality.forPlatform(platform))
        assertEquals(
            AudioQuality.LOSSLESS,
            AudioQuality.preference(platform, AudioQuality.fromId("vivid")),
        )
    }

    @Test
    fun favoriteWritesUseTheNativeSongLikeEndpointAndAccountId() {
        val sent = mutableListOf<LoginRequest>()
        val reader =
            PlatformLibraryClient(
                PlatformProfile(platform, "42", "听友"),
                "MUSIC_U=session",
                LoginTransport { request ->
                    sent += request
                    LoginResponse(JSONObject("""{"code":200}"""))
                },
            )
        val song = PlatformTrack(platform, "314", "歌曲")
        val snapshot = PlatformLibrary(emptyList(), emptyMap())
        reader.setFavorite(song, true, snapshot)
        reader.setFavorite(song, false, snapshot)
        sent.forEachIndexed { index, request ->
            request.validate()
            assertEquals("/eapi/song/like", URL(request.url).path)
            val body = eapiBody(request)
            assertEquals("42", body.optString("userid"))
            assertEquals("314", body.optString("trackId"))
            assertEquals(index == 0, body.getBoolean("like"))
        }
        assertTrue(snapshot.favorites.isEmpty())
    }

    @Test
    fun aRejectedFavoriteWriteKeepsTheSnapshotAndReportsVerification() {
        val requests = AtomicInteger()
        val reader =
            PlatformLibraryClient(
                PlatformProfile(platform, "42", "听友"),
                "MUSIC_U=session",
                LoginTransport {
                    requests.incrementAndGet()
                    LoginResponse(JSONObject("""{"code":-460}"""))
                },
            )
        val song = PlatformTrack(platform, "314", "歌曲")
        val snapshot = PlatformLibrary(emptyList(), mapOf(song.key to song))
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                reader.setFavorite(song, false, snapshot)
            }
        assertTrue(error.message.orEmpty().contains("-460"))
        assertTrue(error.message.orEmpty().contains("安全验证"))
        assertEquals(1, requests.get())
        assertEquals(song, snapshot.favorites[song.key])
    }

    @Test
    fun theDefaultMarkerRecognizesPersonalizedNamesAndOnlyTheOwnersCollection() {
        val raw = defaultMetadata().getJSONArray("data").getJSONObject(0)
        val own = PlatformJson.kugouPlaylist(raw, "42")
        assertTrue(own.favorites)
        assertTrue(own.owned)
        assertEquals("2", own.listId)
        assertEquals(147, own.count)
        assertFalse(PlatformJson.kugouPlaylist(raw, "99").favorites)
        assertFalse(
            PlatformJson.kugouPlaylist(
                    JSONObject("""{"listid":1,"name":"普通歌单","type":0,"userid":42}"""),
                    "42",
                )
                .favorites
        )
        raw.remove("listid")
        raw.remove("list_create_listid")
        assertEquals(
            "2",
            PlatformJson.kugouPlaylistObjects(JSONObject().put("favorite", raw))
                .single()
                .let { PlatformJson.kugouPlaylist(it, "42") }
                .listId,
        )
    }

    @Test
    fun anOmittedDefaultCollectionUsesItsMetadataAndLoadsAll147Songs() {
        val sent = mutableListOf<LoginRequest>()
        val reader = favoritesReader(147, sent)
        val library = reader.loadLibrary()
        assertTrue(library.favoritesReady)
        assertEquals(147, library.favorites.size)
        assertEquals(147, library.favoritePlaylist!!.count)
        assertEquals("collection_3_42_2_0", library.favoritePlaylist!!.id)
        assertEquals("歌曲1", library.favorites.values.first().title)
        assertEquals("歌曲147", library.favorites.values.last().title)
        assertTrue(library.notices.isEmpty())
        assertEquals(4, sent.size)
    }

    @Test
    fun aTruncatedCollectionDoesNotPretendThat147SongsBecame100() {
        val library = favoritesReader(100, mutableListOf()).loadLibrary()
        assertFalse(library.favoritesReady)
        assertEquals(147, library.favoritePlaylist!!.count)
        assertTrue(library.favorites.isEmpty())
        assertTrue(library.notices.single().contains("147"))
        assertTrue(library.notices.single().contains("100"))
    }

    @Test
    fun commentTotalsAreFetchedBeforeOpeningCommentsAndReused() {
        DiscoveryStore.clear(platform)
        val requests = AtomicInteger()
        val reader =
            PlatformDiscoveryClient(
                platform,
                transport =
                    LoginTransport {
                        requests.incrementAndGet()
                        LoginResponse(
                            JSONObject("""{"code":200,"data":{"comments":[],"totalCount":1937}}""")
                        )
                    },
            )
        val song = PlatformTrack(platform, "314", "歌曲")
        assertEquals(1937L, reader.commentCount(song))
        assertEquals(1937L, reader.commentCount(song))
        assertEquals(1, requests.get())
    }

    @Test
    fun aMissingCommentCountIsUnknownWhileAnExplicitZeroIsValid() {
        DiscoveryStore.clear(platform)
        val song = PlatformTrack(platform, "314", "歌曲")
        val missing =
            PlatformDiscoveryClient(
                platform,
                transport =
                    LoginTransport {
                        LoginResponse(JSONObject("""{"code":200,"data":{"comments":[]}}"""))
                    },
            )
        assertNull(missing.commentCount(song))
        val empty =
            PlatformDiscoveryClient(
                platform,
                transport =
                    LoginTransport {
                        LoginResponse(
                            JSONObject("""{"code":200,"data":{"comments":[],"totalCount":0}}""")
                        )
                    },
            )
        assertEquals(0L, empty.commentCount(song))
    }

    private fun favoritesReader(count: Int, sent: MutableList<LoginRequest>) =
        PlatformLibraryClient(
            concept,
            conceptSession,
            LoginTransport { request ->
                request.validate()
                sent += request
                val body = JSONObject(request.body!!)
                val json =
                    when (URL(request.url).path) {
                        "/v7/get_all_list" ->
                            JSONObject("""{"status":1,"data":{"info":[],"total":0}}""")
                        "/v3/get_list_info" -> {
                            assertEquals(
                                "collection_3_42_2_0",
                                body
                                    .getJSONArray("data")
                                    .getJSONObject(0)
                                    .getString("global_collection_id"),
                            )
                            defaultMetadata()
                        }
                        "/v4/get_list_all_file_v3" -> {
                            assertEquals("2", body.optString("listid"))
                            assertEquals(0, body.getInt("type"))
                            assertEquals("42", body.optString("userid"))
                            assertEquals(300, body.getInt("pagesize"))
                            JSONObject()
                                .put("status", 1)
                                .put(
                                    "data",
                                    JSONObject()
                                        .put("total", count)
                                        .put(
                                            "info",
                                            JSONArray(
                                                (1..count).map { id ->
                                                    JSONObject()
                                                        .put("hash", "%032x".format(id))
                                                        .put("filename", "艺人 - 歌曲$id")
                                                        .put("fileid", id)
                                                }
                                            ),
                                        ),
                                )
                        }
                        else -> error("Unexpected endpoint ${URL(request.url).path}")
                    }
                LoginResponse(json)
            },
        )

    private fun defaultMetadata() =
        JSONObject(
            javaClass
                .getResourceAsStream("/fixtures/kugou-default-favorites.json")!!
                .bufferedReader()
                .use { it.readText() }
        )

    private fun eapiBody(request: LoginRequest): JSONObject {
        val bytes =
            request.body!!
                .removePrefix("params=")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val plain =
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                String(doFinal(bytes), Charsets.UTF_8)
            }
        return JSONObject(plain.split("-36cd479b6b5-")[1])
    }
}
