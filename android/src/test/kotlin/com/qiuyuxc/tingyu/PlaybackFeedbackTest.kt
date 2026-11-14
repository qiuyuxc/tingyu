package com.qiuyuxc.tingyu

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackFeedbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val concept = PlatformProfile(MusicPlatform.KUGOU, "42", "听友")
    private val session =
        """{"edition":"lite","appid":"3116","userid":"42","token":"test-token","device":"device"}"""

    @Test
    fun albumOriginalLargerThanThreeMegabytesSurvivesCacheRestart() {
        val directory = temporary.newFolder()
        val original = ByteArray(3339251) { (it % 251).toByte() }
        val cache = ArtworkCache(directory, { original }, memoryLimit = 1)
        assertArrayEquals(original, cache.get("rain-love-original"))
        assertEquals(original.size.toLong(), cache.diskBytes())
        val restored = ArtworkCache(directory, { error("Original should already be cached") })
        assertArrayEquals(original, restored.get("rain-love-original"))
    }

    @Test
    fun conceptDoesNotShareNeteaseQualityNamesOrUnsupportedPreferences() {
        val choices = AudioQuality.forPlatform(MusicPlatform.KUGOU)
        assertFalse(AudioQuality.MASTER in choices)
        assertFalse(AudioQuality.HIGHER in choices)
        assertTrue(choices.none { it.label(MusicPlatform.KUGOU) == "超清母带" })
        assertEquals("高品", AudioQuality.HIGH.label(MusicPlatform.KUGOU))
        assertEquals(
            AudioQuality.LOSSLESS,
            AudioQuality.preference(MusicPlatform.KUGOU, AudioQuality.MASTER),
        )
    }

    @Test
    fun neteaseSongDetailKeepsKnownAudioMetadataWithoutLoweringTheRequestedQuality() {
        val song =
            PlatformJson.neteaseTrack(
                JSONObject(
                    """{
            "id":42,"name":"歌曲","fee":1,
            "l":{"br":128000},"h":{"br":320000},"sq":{"br":900000},"hr":null,"jm":null
        }"""
                )
            )
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.HIGH, AudioQuality.LOSSLESS),
            AudioQuality.forTrack(song).map { it.quality },
        )
        assertEquals(AudioQuality.MASTER, AudioQuality.resolve(song, AudioQuality.MASTER))
        assertTrue(song.qualities.all { it.access == TrackAccess.VIP })
    }

    @Test
    fun conceptRecommendationAudioHashesDescribeItsAvailableQualities() {
        val song =
            PlatformJson.kugouTrack(
                JSONObject(
                    """{
            "songname":"歌曲","audio_info":{"hash":"${"A".repeat(32)}",
            "hash_320":"${"B".repeat(32)}","hash_flac":"${"C".repeat(32)}","hash_high":"",
            "privilege":10,"pay_type":1},"relate_goods":{}
        }"""
                )
            )!!
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.HIGH, AudioQuality.LOSSLESS),
            song.qualities.map { it.quality },
        )
        assertEquals(AudioQuality.HIRES, AudioQuality.resolve(song, AudioQuality.HIRES))
    }

    @Test
    fun fullSyncFindsTheOfficialFavoritesInsideAnObjectWithItsRealListId() {
        val requests = mutableListOf<LoginRequest>()
        val reader =
            PlatformLibraryClient(
                concept,
                session,
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    val body = JSONObject(request.body!!)
                    val json =
                        if (URL(request.url).path == "/v7/get_all_list") {
                            assertEquals(0, body.getInt("total_ver"))
                            JSONObject(
                                """{"status":1,"data":{"info":[],"total":0,
                    "default_list":{"favorite":{"listid":9,"name":" 我喜欢 ","type":0,
                    "userid":42,"list_create_userid":0,"count":1}}}}"""
                            )
                        } else {
                            assertEquals("9", body.getString("listid"))
                            JSONObject(
                                """{"status":1,"data":{"info":[{"hash":"${"A".repeat(32)}",
                    "filename":"艺人 - 歌曲","fileid":7}],"total":1}}"""
                            )
                        }
                    LoginResponse(json)
                },
            )
        val library = reader.loadLibrary()
        assertTrue(library.favoritesReady)
        assertEquals("9", library.favoritePlaylist!!.listId)
        assertEquals("我喜欢", library.favoritePlaylist!!.name)
        assertEquals(1, library.favorites.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun anOmittedSystemPlaylistIsReadFromTheOwnedListResponseBeforeTheReservedFallback() {
        val reader =
            PlatformLibraryClient(
                concept,
                session,
                LoginTransport { request ->
                    val body = JSONObject(request.body!!)
                    val json =
                        if (URL(request.url).path == "/v7/get_all_list") {
                            if (body.getInt("type") == 2)
                                """{"status":1,"data":{"info":[{"listid":8,"name":"歌单","type":0}],"total":1}}"""
                            else
                                """{"status":1,"data":{"info":[{"listid":5,"name":"我喜欢","type":0}],"total":1}}"""
                        } else {
                            assertEquals("5", body.getString("listid"))
                            """{"status":1,"data":{"info":[],"total":0}}"""
                        }
                    LoginResponse(JSONObject(json))
                },
            )
        val library = reader.loadLibrary()
        assertTrue(library.favoritesReady)
        assertEquals("5", library.favoritePlaylist!!.listId)
        assertEquals(2, library.playlists.size)
    }

    @Test
    fun collectingAnotherUsersFavoritesDoesNotMakeItTheAccountsFavoriteList() {
        val playlist =
            PlatformJson.kugouPlaylist(
                JSONObject(
                    """{
            "listid":8,"name":"我喜欢","type":1,"userid":42,"list_create_userid":99
        }"""
                ),
                "42",
            )
        assertFalse(playlist.favorites)
        assertFalse(playlist.owned)
    }

    @Test
    fun aCancelledPageReadDoesNotSkipSongsWhenPlaybackResumes() {
        val pages = mutableListOf<Int>()
        val source =
            PagedTrackSource("playlist", "歌单", MusicPlatform.NETEASE, 1) { page ->
                pages += page
                PlatformPage(
                    listOf(PlatformTrack(MusicPlatform.NETEASE, "$page", "歌曲$page")),
                    page < 2,
                )
            }
        val cancelled = source.next(emptySet())
        val retry = source.next(emptySet())
        assertEquals(cancelled, retry)
        source.commit(retry, emptySet())
        val next = source.next(retry.items.map { it.key }.toSet())
        assertEquals("2", next.items.single().id)
        assertFalse(next.more)
        assertEquals(listOf(1, 1, 2), pages)
    }

    @Test
    fun delayedRecommendationsCannotAppendIntoANewPlaylist() {
        val first =
            PagedTrackSource("a", "歌单A", MusicPlatform.NETEASE, 1) {
                PlatformPage(emptyList(), false)
            }
        val second =
            PagedTrackSource("b", "歌单B", MusicPlatform.NETEASE, 1) {
                PlatformPage(emptyList(), false)
            }
        val continuation = PlaybackContinuation()
        continuation.reset(first)
        val delayed = continuation.request()!!
        continuation.reset(second)
        assertFalse(continuation.accept(delayed, false))
        assertSame(second, continuation.source)
        assertTrue(continuation.more)
        assertTrue(continuation.accept(continuation.request()!!, false))
        assertNull(continuation.request())
    }

    @Test
    fun stoppingInvalidatesTheReadButRetainsThePlaylistForResume() {
        val source =
            PagedTrackSource("a", "歌单", MusicPlatform.NETEASE, 1) {
                PlatformPage(emptyList(), false)
            }
        val continuation = PlaybackContinuation()
        continuation.reset(source)
        val delayed = continuation.request()!!
        continuation.invalidate()
        assertFalse(continuation.accept(delayed, false))
        assertSame(source, continuation.request()!!.source)
        continuation.reset()
        assertNull(continuation.request())
    }

    @Test
    fun radioKeepsFindingDistinctSongsBeyondTheFirstTen() {
        val calls = AtomicInteger()
        val reader =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                transport =
                    LoginTransport { request ->
                        val json =
                            if (request.url.endsWith("/v1/radio/get")) {
                                val id = calls.incrementAndGet()
                                JSONObject()
                                    .put("code", 200)
                                    .put(
                                        "data",
                                        JSONArray()
                                            .put(JSONObject().put("id", id).put("name", "歌曲$id")),
                                    )
                            } else JSONObject("""{"code":200,"songs":[]}""")
                        LoginResponse(json)
                    },
            )
        val source = RecommendationTrackSource(MusicPlatform.NETEASE) { reader }
        val found = LinkedHashSet<String>()
        repeat(16) {
            val batch = source.next(found)
            assertTrue(batch.more)
            assertTrue(batch.items.none { it.key in found })
            source.commit(batch, found)
            found.addAll(batch.items.map { it.key })
        }
        assertEquals(16, found.size)
        assertEquals(16, calls.get())
    }

    @Test
    fun conceptRecommendationPagesUseTheConceptApiAndKeepTheNextPageCursor() {
        val pages = mutableListOf<Int>()
        val reader =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                session,
                LoginTransport { request ->
                    val json =
                        if (URL(request.url).path == "/concepts/v1/ai/recommend_song") {
                            assertTrue(request.url.contains("appid=3116"))
                            val page = JSONObject(request.body!!).getInt("page")
                            pages += page
                            JSONObject(
                                """{"status":1,"data":{"list":[{"songname":"歌曲$page",
                    "audio_info":{"hash":"${page.toString().padStart(32, '0')}"}}]}}"""
                            )
                        } else JSONObject("""{"status":0,"error_code":0}""")
                    LoginResponse(json)
                },
            )
        val source = RecommendationTrackSource(MusicPlatform.KUGOU) { reader }
        val first = source.next(emptySet())
        source.commit(first, emptySet())
        val second = source.next(first.items.map { it.key }.toSet())
        assertEquals(listOf(1, 2), pages)
        assertNotEquals(first.items.single().key, second.items.single().key)
    }

    @Test
    fun latestCommentPaginationSendsTheCursorReturnedByThePlatform() {
        val sent = mutableListOf<JSONObject>()
        val reader =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                transport =
                    LoginTransport { request ->
                        sent += eapiBody(request)
                        LoginResponse(
                            JSONObject(
                                """{"code":200,"data":{"comments":[{"commentId":${sent.size},
                "content":"评论","time":123000}],"hasMore":true,"totalCount":197491,"cursor":"next-cursor"}}"""
                            )
                        )
                    },
            )
        val track = PlatformTrack(MusicPlatform.NETEASE, "316100", "雨爱")
        val first = reader.comments(track, hot = false)
        val next = reader.comments(track, 1, false, first.cursor)
        assertEquals(197491L, next.total)
        assertEquals(3, sent[1].getInt("sortType"))
        assertEquals(2, sent[1].getInt("pageNo"))
        assertEquals("next-cursor", sent[1].getString("cursor"))
        assertNotEquals(first.items.single().id, next.items.single().id)
    }

    private fun eapiBody(request: LoginRequest): JSONObject {
        val hex = request.body!!.removePrefix("params=")
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val plain =
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                String(doFinal(bytes), Charsets.UTF_8)
            }
        return JSONObject(plain.split("-36cd479b6b5-")[1])
    }

    @Test
    fun anUnexpectedEmptyFavoritesResponseKeepsThePreviousSongs() {
        val song = PlatformTrack(MusicPlatform.KUGOU, "A".repeat(32), "歌曲", fileId = "7")
        PlatformLibraryStore.publish(
            concept,
            PlatformLibrary(
                listOf(
                    PlatformPlaylist(
                        MusicPlatform.KUGOU,
                        "0:9",
                        "我喜欢",
                        1,
                        favorites = true,
                        owned = true,
                        listId = "9",
                    )
                ),
                mapOf(song.key to song),
            ),
        )
        try {
            val reader =
                PlatformLibraryClient(
                    concept,
                    session,
                    LoginTransport { request ->
                        LoginResponse(
                            JSONObject(
                                if (URL(request.url).path == "/v7/get_all_list")
                                    """{"status":1,"data":{"info":[{"listid":9,"name":"我喜欢","type":0,"count":1}],"total":1}}"""
                                else """{"status":1,"data":{"info":[],"total":0}}"""
                            )
                        )
                    },
                )
            val library = reader.loadLibrary()
            assertFalse(library.favoritesReady)
            assertEquals(song, library.favorites[song.key])
            assertEquals(1, library.favoritePlaylist!!.count)
            assertTrue(library.notices.isNotEmpty())
        } finally {
            PlatformLibraryStore.clear(MusicPlatform.KUGOU)
        }
    }

    @Test
    fun findingNewSongsContinuesPastUnavailableCharts() {
        DiscoveryStore.clear(MusicPlatform.NETEASE)
        val attempts = AtomicInteger()
        try {
            val reader =
                PlatformDiscoveryClient(
                    MusicPlatform.NETEASE,
                    transport =
                        LoginTransport { request ->
                            val path = URL(request.url).path
                            val json =
                                when {
                                    path.endsWith("/v1/radio/get") ->
                                        """{"code":200,"data":[{"id":1,"name":"已听过"}]}"""
                                    path.endsWith("/toplist") ->
                                        """{"code":200,"list":[{"id":11,"name":"榜单一"},{"id":12,"name":"榜单二"},{"id":13,"name":"榜单三"}]}"""
                                    path.endsWith("/v6/playlist/detail") ->
                                        if (attempts.incrementAndGet() < 3) """{"code":500}"""
                                        else
                                            """{"code":200,"playlist":{"trackIds":[{"id":99}],"tracks":[{"id":99,"name":"新歌曲"}]}}"""
                                    else -> """{"code":200,"songs":[{"id":99,"name":"新歌曲"}]}"""
                                }
                            LoginResponse(JSONObject(json))
                        },
                )
            val songs = reader.recommendations(0, setOf("platform:netease:1"))
            assertEquals("99", songs.single().id)
            assertEquals(3, attempts.get())
        } finally {
            DiscoveryStore.clear(MusicPlatform.NETEASE)
        }
    }
}
