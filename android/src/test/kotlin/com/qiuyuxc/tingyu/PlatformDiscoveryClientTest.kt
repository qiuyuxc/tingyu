package com.qiuyuxc.tingyu

import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformDiscoveryClientTest {
    @Test
    fun rankingCategoriesKeepTheirChildrenAndCurrentCycle() {
        val source =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                transport =
                    LoginTransport { request ->
                        request.validate()
                        LoginResponse(
                            JSONObject(
                                "{\"status\":1,\"data\":{\"info\":[{\"rankid\":0,\"rankname\":\"分类\",\"children\":[{\"rankid\":8888,\"rank_cid\":164300,\"rankname\":\"TOP500\",\"imgurl\":\"http://imge.kugou.com/{size}/rank.png\"}]}]}}"
                            )
                        )
                    },
            )
        val chart = source.charts().single()
        assertEquals("8888", chart.id)
        assertEquals(164300L, chart.cycleId)
        assertTrue(chart.cover.startsWith("https://"))
        assertTrue(chart.cover.contains("/480/"))
    }

    @Test
    fun liveRankingSonglistShapeKeepsAudioIdsCoversAndDurations() {
        val source =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                transport =
                    LoginTransport { request ->
                        request.validate()
                        if (URL(request.url).path.endsWith("/rank/audio")) {
                            assertEquals(164300L, JSONObject(request.body!!).getLong("rank_cid"))
                            assertEquals("369", request.headers["kg-tid"])
                            LoginResponse(fixture("kugou-rank-audio.json"))
                        } else LoginResponse(JSONObject("{\"status\":0,\"error_code\":0}"))
                    },
            )
        val page =
            source.chartSongs(
                PlatformChart(MusicPlatform.KUGOU, "8888", "TOP500", cycleId = 164300)
            )
        assertTrue(page.more)
        assertEquals(2, page.items.size)
        val first = page.items.first()
        assertEquals("示例音乐一", first.title)
        assertEquals("示例艺人", first.artist)
        assertEquals("34", first.audioId)
        assertEquals(236000L, first.durationMs)
        assertTrue(first.cover.contains("/480/"))
    }

    @Test
    fun privilegeMetadataSuppliesAMissingCoverAndPerQualityFlags() {
        var calls = 0
        val source =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                transport =
                    LoginTransport { request ->
                        calls++
                        request.validate()
                        assertEquals("media.store.kugou.com", request.headers["x-router"])
                        val resource =
                            JSONObject(request.body!!).getJSONArray("resource").getJSONObject(0)
                        assertFalse(resource.has("album_audio_id"))
                        LoginResponse(fixture("kugou-privilege.json"))
                    },
            )
        val track =
            PlatformTrack(
                MusicPlatform.KUGOU,
                "A".repeat(32),
                "示例音乐",
                albumId = "12",
                audioId = "34",
            )
        val result = source.details(listOf(track)).single()
        assertEquals(1, calls)
        assertTrue(result.cover.endsWith("/480/cover-one.jpg"))
        assertEquals(TrackAccess.PAID, result.access)
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.HIGH, AudioQuality.LOSSLESS),
            result.qualities.map { it.quality },
        )
    }

    @Test
    fun conceptSearchUsesPublicSongFieldsAndPreservesPagination() {
        val source =
            PlatformDiscoveryClient(
                MusicPlatform.KUGOU,
                transport =
                    LoginTransport { request ->
                        request.validate()
                        assertTrue(request.url.contains("clientver=201"))
                        LoginResponse(
                            JSONObject(
                                "{\"status\":1,\"data\":{\"total\":42,\"lists\":[{\"FileHash\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"SongName\":\"示例音乐\",\"SingerName\":\"艺人\",\"Duration\":123,\"MixSongID\":34,\"Privilege\":10,\"PayType\":3,\"Image\":\"http://imge.kugou.com/{size}/cover.jpg\"}]}}"
                            )
                        )
                    },
            )
        val result = source.search("示例")
        assertTrue(result.more)
        assertEquals(TrackAccess.PAID, result.items.single().access)
        assertEquals(123000L, result.items.single().durationMs)
        assertEquals("34", result.items.single().audioId)
    }

    @Test
    fun neteaseCommentsKeepTheCursorTotalAndAuthor() {
        val source =
            PlatformDiscoveryClient(
                MusicPlatform.NETEASE,
                transport =
                    LoginTransport { request ->
                        request.validate()
                        assertTrue(request.url.endsWith("/v2/resource/comments"))
                        LoginResponse(
                            JSONObject(
                                "{\"code\":200,\"data\":{\"comments\":[{\"commentId\":7,\"content\":\"好听\",\"time\":1230000,\"likedCount\":9,\"user\":{\"nickname\":\"听友\"}}],\"totalCount\":110,\"hasMore\":true,\"cursor\":\"next-cursor\"}}"
                            )
                        )
                    },
            )
        val result =
            source.comments(
                PlatformTrack(MusicPlatform.NETEASE, "42", "音乐"),
                hot = false,
                cursor = "previous-cursor",
            )
        assertTrue(result.more)
        assertEquals(110L, result.total)
        assertEquals("next-cursor", result.cursor)
        assertEquals("听友", result.items.single().author)
        assertEquals(9L, result.items.single().likes)
    }

    private fun fixture(name: String): JSONObject =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use {
            JSONObject(it.readText())
        }
}
