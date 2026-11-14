package com.qiuyuxc.tingyu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalMusicClientTest {
    @Test
    fun thePersonalCatalogDoesNotDownloadThousandsOfFavoriteSongs() {
        var requests = 0
        val client =
            PlatformLibraryClient(
                PlatformProfile(MusicPlatform.NETEASE, "42", "听友"),
                "MUSIC_U=session",
                LoginTransport { request ->
                    request.validate()
                    requests++
                    assertTrue(request.url.endsWith("/user/playlist"))
                    LoginResponse(
                        JSONObject(
                            """{"code":200,"more":false,"playlist":[{"id":7,"name":"喜欢的音乐","specialType":5,"trackCount":5000,"creator":{"userId":42}}]}"""
                        )
                    )
                },
            )
        val catalog = client.playlistCatalog()
        assertEquals(1, requests)
        assertEquals(5000, catalog.single().count)
        assertTrue(catalog.single().favorites)
    }

    @Test
    fun privateRadioUsesTheRealEndpointAndFiltersUnavailableOrRepeatedSongs() {
        val paths = mutableListOf<String>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=session",
                LoginTransport { request ->
                    request.validate()
                    paths += request.url
                    if (request.url.endsWith("/v1/radio/get"))
                        LoginResponse(
                            JSONObject(
                                """{"code":200,"data":[{"id":1,"name":"听过"},{"id":2,"name":"新歌"},{"id":2,"name":"重复"},{"id":3,"name":"不可播放","privilege":{"st":-1}}]}"""
                            )
                        )
                    else
                        LoginResponse(JSONObject("""{"code":200,"songs":[{"id":2,"name":"新歌"}]}"""))
                },
            )
        val songs = client.radioSongs(excluded = setOf("platform:netease:1"))
        assertEquals(listOf("2"), songs.map { it.id })
        assertTrue(paths.first().endsWith("/weapi/v1/radio/get"))
    }

    @Test
    fun aRejectedRadioRequestReportsFailureWithoutSubstitutingAChart() {
        var requests = 0
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=session",
                LoginTransport {
                    requests++
                    LoginResponse(JSONObject("""{"code":301}"""))
                },
            )
        assertThrows(IllegalArgumentException::class.java) { client.radioSongs() }
        assertEquals(1, requests)
    }

    @Test
    fun privateRadioRechecksAvailabilityAfterReadingSongDetails() {
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                "MUSIC_U=session",
                LoginTransport { request ->
                    val response =
                        if (request.url.endsWith("/v1/radio/get"))
                            """{"code":200,"data":[{"id":2,"name":"歌曲"}]}"""
                        else
                            """{"code":200,"songs":[{"id":2,"name":"歌曲"}],"privileges":[{"id":2,"st":-1}]}"""
                    LoginResponse(JSONObject(response))
                },
            )
        assertThrows(IllegalArgumentException::class.java) { client.radioSongs() }
    }

    @Test
    fun conceptHeartContinuationAdvancesOnlyAfterAcceptingANewBatch() {
        val pages = mutableListOf<Int>()
        val client =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                transport =
                    LoginTransport { request ->
                        request.validate()
                        val response =
                            if (request.url.contains("/concepts/v1/ai/recommend_song")) {
                                val page = JSONObject(request.body!!).getInt("page")
                                pages += page
                                val hash = if (page == 2) "B".repeat(32) else "C".repeat(32)
                                """{"status":1,"data":{"list":[{"hash":"${"A".repeat(32)}","filename":"已听过"},{"hash":"$hash","filename":"新歌曲"}]}}"""
                            } else """{"status":0,"error_code":0}"""
                        LoginResponse(JSONObject(response))
                    },
            )
        val source = RecommendationTrackSource(MusicPlatform.KUGOU, heart = true) { client }
        val excluded = setOf("platform:kugou:${"A".repeat(32)}")
        val first = source.next(excluded)
        assertEquals(listOf("B".repeat(32)), first.items.map { it.id })
        assertEquals(first, source.next(excluded))
        source.commit(first, excluded)
        assertEquals(listOf("C".repeat(32)), source.next(excluded).items.map { it.id })
        assertEquals(listOf(2, 2, 3), pages)
        assertEquals("heart:kugou", source.id)
        assertTrue(source.endless)
    }

    @Test
    fun accountSettingsReturnToTheOriginalMusicPage() {
        val navigation = SettingsNavigation()
        navigation.open("accounts", 0)
        assertEquals("accounts", navigation.page)
        assertTrue(navigation.back())
        assertEquals("home", navigation.page)
        assertEquals(0, navigation.takeReturnTab())
    }
}
