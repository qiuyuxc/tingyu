package com.qiuyuxc.tingyu

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal data class PlatformChart(
    val platform: MusicPlatform,
    val id: String,
    val name: String,
    val cover: String = "",
    val description: String = "",
    val cycleId: Long = 0,
)

internal data class PlatformComment(
    val id: String,
    val author: String,
    val content: String,
    val avatar: String = "",
    val likes: Long = 0,
    val time: Long = 0,
    val liked: Boolean? = null,
    val resourceId: String = "",
    val authorId: String = "",
)

internal data class CommentPage(
    val items: List<PlatformComment>,
    val more: Boolean,
    val total: Long = 0,
    val cursor: String = "0",
    val totalKnown: Boolean = true,
)

internal data class CommentLikeResult(val liked: Boolean, val likes: Long? = null)

internal data class DiscoveryHome(
    val charts: List<PlatformChart>,
    val chart: PlatformChart?,
    val songs: List<PlatformTrack>,
    val keywords: List<String>,
    val notices: List<String> = emptyList(),
    val recommendations: List<PlatformTrack> = emptyList(),
    val recommendationLabel: String = "为你推荐",
    val recommendationPage: Int = 0,
)

/** Public discovery metadata is separate from the signed-in user's library and credentials. */
internal object DiscoveryStore {
    private data class Entry(val value: DiscoveryHome, val time: Long)

    private val home = ConcurrentHashMap<MusicPlatform, Entry>()

    private data class Count(val total: Long, val time: Long)

    private val commentCounts = ConcurrentHashMap<String, Count>()

    fun commentCount(track: PlatformTrack): Long? =
        commentCounts[track.key]
            ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60 * 1000 }
            ?.total

    fun publishCommentCount(track: PlatformTrack, total: Long) {
        if (total < 0) return
        commentCounts[track.key] = Count(total, System.currentTimeMillis())
        if (commentCounts.size > 256) {
            commentCounts
                .minByOrNull { it.value.time }
                ?.let { commentCounts.remove(it.key, it.value) }
        }
    }

    fun invalidateCommentCount(track: PlatformTrack) {
        commentCounts.remove(track.key)
    }

    fun get(platform: MusicPlatform): DiscoveryHome? =
        home[platform]
            ?.takeIf {
                System.currentTimeMillis() - it.time < 10 * 60 * 1000
            }
            ?.value

    fun publish(platform: MusicPlatform, value: DiscoveryHome) {
        if (value.charts.isNotEmpty() || value.keywords.isNotEmpty())
            home[platform] = Entry(value, System.currentTimeMillis())
    }

    fun clear(platform: MusicPlatform) {
        home.remove(platform)
        commentCounts.keys.removeAll { it.startsWith("platform:${platform.id}:") }
    }
}

internal class PlatformDiscoveryClient(
    private val platform: MusicPlatform,
    private val session: String = "",
    private val transport: LoginTransport = LoginTransport(PlatformLoginClient::request),
) {
    private var cookie = if (platform == MusicPlatform.NETEASE) session else ""
    private val kugouSession =
        if (platform == MusicPlatform.KUGOU && session.isNotBlank()) {
            JSONObject(session).takeIf {
                it.optString("edition") == KugouLite.EDITION &&
                    it.optString("appid") == KugouLite.APP_ID
            } ?: JSONObject()
        } else JSONObject()
    private val playlistIds = ConcurrentHashMap<String, List<String>>()
    private val knownTracks = ConcurrentHashMap<String, PlatformTrack>()
    private val commentResources = ConcurrentHashMap<String, String>()
    private val commentTracks = ConcurrentHashMap<String, PlatformTrack>()

    fun home(force: Boolean = false): DiscoveryHome {
        if (!force)
            DiscoveryStore.get(platform)?.let {
                return it
            }
        val notices = mutableListOf<String>()
        val lists =
            try {
                charts()
            } catch (error: Exception) {
                notices += error.message ?: "榜单暂时不可用"
                emptyList()
            }
        val chart =
            lists.firstOrNull { it.name.contains("热歌") || it.name.contains("TOP500", true) }
                ?: lists.firstOrNull()
        val songs =
            if (chart == null) emptyList()
            else
                try {
                    chartSongs(chart).items
                } catch (error: Exception) {
                    notices += error.message ?: "榜单歌曲暂时不可用"
                    emptyList()
                }
        val hot =
            try {
                hotSearch()
            } catch (error: Exception) {
                notices += error.message ?: "热搜暂时不可用"
                emptyList()
            }
        var recommendationLabel = "为你推荐"
        val recommendations =
            try {
                dailyRecommendations().ifEmpty { error("推荐暂时不可用") }
            } catch (_: Exception) {
                recommendationLabel = "随机发现"
                songs.shuffled()
            }
        return DiscoveryHome(
                lists,
                chart,
                songs,
                hot,
                notices,
                recommendations,
                recommendationLabel,
            )
            .also {
                DiscoveryStore.publish(platform, it)
            }
    }

    fun dailyRecommendations(): List<PlatformTrack> {
        val songs =
            if (platform == MusicPlatform.NETEASE) {
                val json = netease("v3/discovery/recommend/songs", JSONObject(), web = true)
                val data = json.optJSONObject("data") ?: json
                objects(array(data, "dailySongs", "recommend", "songs")).map {
                    PlatformJson.neteaseTrack(it)
                }
            } else {
                val json =
                    kugou(
                        "/everyday_song_recommend",
                        data =
                            JSONObject()
                                .put("platform", "android")
                                .put("userid", kugouSession.optString("userid", "0")),
                        router = "everydayrec.service.kugou.com",
                    )
                objects(array(json.optJSONObject("data") ?: json, "song_list", "list", "songs"))
                    .mapNotNull { PlatformJson.kugouTrack(it) }
            }
        return runCatching { details(songs.take(100)) }.getOrDefault(songs).distinctBy { it.key }
    }

    /** Radio and the concept edition's recommendation feed keep supplying new songs. */
    fun radioSongs(page: Int = 0, excluded: Set<String> = emptySet()): List<PlatformTrack> {
        require(page >= 0)
        val songs =
            if (platform == MusicPlatform.NETEASE) {
                    val json =
                        netease("v1/radio/get", JSONObject().put("limit", SEARCH_SIZE), web = true)
                    objects(json.optJSONArray("data")).map { PlatformJson.neteaseTrack(it) }
                } else {
                    val json =
                        kugou(
                            "/concepts/v1/ai/recommend_song",
                            data =
                                JSONObject()
                                    .put("apiver", 2)
                                    .put("source", 2)
                                    .put("page", page + 1)
                                    .put("pagesize", SEARCH_SIZE),
                        )
                    objects(array(json.optJSONObject("data") ?: json, "list", "songs", "song_list"))
                        .mapNotNull { PlatformJson.kugouTrack(it) }
                }
                .distinctBy { it.key }
                .filter { it.key !in excluded && it.access != TrackAccess.UNAVAILABLE }
        require(songs.isNotEmpty()) { "暂时没有获取到心动歌曲，请稍后重试" }
        val resolved = runCatching {
            details(songs)
        }
            .getOrDefault(songs)
            .distinctBy { it.key }
            .filter { it.key !in excluded && it.access != TrackAccess.UNAVAILABLE }
        require(resolved.isNotEmpty()) { "暂时没有可播放的心动歌曲，请稍后重试" }
        return resolved
    }

    fun recommendations(page: Int, excluded: Set<String>): List<PlatformTrack> {
        require(page >= 0)
        val primary = runCatching {
            radioSongs(page, excluded)
        }
            .getOrDefault(emptyList())
            .filter { it.key !in excluded && it.access != TrackAccess.UNAVAILABLE }
        if (primary.isNotEmpty()) return primary
        val charts = (DiscoveryStore.get(platform)?.charts ?: charts()).shuffled()
        for (chart in charts.take(3)) {
            val offset = (0..4).random()
            val batch = runCatching {
                chartSongs(chart, offset).items.ifEmpty { chartSongs(chart).items }
            }
                .getOrDefault(emptyList())
                .filter { it.key !in excluded && it.access != TrackAccess.UNAVAILABLE }
                .shuffled()
            if (batch.isNotEmpty()) return batch
        }
        error("暂时没有获取到新的推荐歌曲，请稍后重试")
    }

    fun search(keyword: String, page: Int = 0): PlatformPage<PlatformTrack> {
        val query = keyword.trim()
        require(query.isNotBlank() && query.length <= 100 && page >= 0)
        val result =
            if (platform == MusicPlatform.NETEASE) {
                val json =
                    netease(
                        "cloudsearch/pc",
                        JSONObject()
                            .put("s", query)
                            .put("type", 1)
                            .put("limit", SEARCH_SIZE)
                            .put("offset", page * SEARCH_SIZE)
                            .put("total", true),
                    )
                val data =
                    json.optJSONObject("result") ?: json.optJSONObject("data") ?: JSONObject()
                val tracks =
                    objects(data.optJSONArray("songs")).map { PlatformJson.neteaseTrack(it) }
                PlatformPage(
                    tracks,
                    (page + 1) * SEARCH_SIZE < data.optInt("songCount", tracks.size),
                )
            } else {
                val json =
                    kugou(
                        "/v2/search/song",
                        values =
                            mapOf(
                                "keyword" to query,
                                "page" to (page + 1).toString(),
                                "pagesize" to SEARCH_SIZE.toString(),
                                "platform" to "AndroidFilter",
                                "iscorrection" to "1",
                                "privilegefilter" to "0",
                                "area_code" to "1",
                                "dopicfull" to "1",
                                "clientver" to "201",
                            ),
                        router = "complexsearch.kugou.com",
                    )
                val data = json.optJSONObject("data") ?: json
                val array = array(data, "info", "lists", "songs") ?: JSONArray()
                val songs = objects(array).mapNotNull { PlatformJson.kugouTrack(it) }
                PlatformPage(songs, (page + 1) * SEARCH_SIZE < data.optInt("total", songs.size))
            }
        if (platform == MusicPlatform.NETEASE && result.items.isNotEmpty()) {
            val complete = runCatching { details(result.items) }.getOrDefault(result.items)
            return result.copy(items = complete)
        }
        return result
    }

    fun charts(): List<PlatformChart> =
        if (platform == MusicPlatform.NETEASE) {
            val json = netease("toplist", JSONObject())
            objects(json.optJSONArray("list")).mapNotNull { item ->
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PlatformChart(
                    platform,
                    id,
                    item.optString("name"),
                    image(item.optString("coverImgUrl")),
                    item.optString("updateFrequency", item.optString("description")),
                )
            }
        } else {
            val json =
                kugou(
                    "/ocean/v6/rank/list",
                    values = mapOf("plat" to "2", "withsong" to "0", "parentid" to "0"),
                )
            chartObjects(json.opt("data") ?: json)
                .mapNotNull { item ->
                    val id =
                        string(item, "rankid", "rank_id", "id").takeIf {
                            it.isNotBlank() && it != "0"
                        } ?: return@mapNotNull null
                    val name =
                        string(item, "rankname", "rank_name", "name").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                    PlatformChart(
                        platform,
                        id,
                        name,
                        image(
                            string(
                                item,
                                "imgurl",
                                "sizable_cover",
                                "cover",
                                "icon",
                                "img_9",
                                "album_img_9",
                            )
                        ),
                        string(item, "update_frequency", "intro", "description"),
                        item.optLong("rank_cid"),
                    )
                }
                .distinctBy { it.id }
        }

    fun chartSongs(
        chart: PlatformChart,
        page: Int = 0,
        limit: Int = SEARCH_SIZE,
    ): PlatformPage<PlatformTrack> {
        require(chart.platform == platform && page >= 0 && limit in 1..100)
        return if (platform == MusicPlatform.NETEASE) {
            val ids =
                playlistIds.getOrPut(chart.id) {
                    val json =
                        netease(
                            "v6/playlist/detail",
                            JSONObject().put("id", chart.id).put("n", 100000).put("s", 0),
                        )
                    val list = json.optJSONObject("playlist") ?: error("这个榜单暂时不可用")
                    objects(list.optJSONArray("tracks"))
                        .map { PlatformJson.neteaseTrack(it) }
                        .forEach { knownTracks[it.id] = it }
                    objects(list.optJSONArray("trackIds"))
                        .map { it.optString("id") }
                        .filter { it.isNotBlank() }
                        .ifEmpty { objects(list.optJSONArray("tracks")).map { it.optString("id") } }
                }
            val selected =
                ids.drop(page * limit).take(limit).map { id ->
                    knownTracks[id] ?: PlatformTrack(platform, id, "暂不可用的歌曲")
                }
            val songs =
                if (selected.isEmpty()) selected
                else runCatching { details(selected) }.getOrDefault(selected)
            PlatformPage(songs, (page + 1) * limit < ids.size)
        } else {
            val json =
                kugou(
                    "/openapi/kmr/v2/rank/audio",
                    data =
                        JSONObject()
                            .put("show_portrait_mv", 1)
                            .put("show_type_total", 1)
                            .put("filter_original_remarks", 1)
                            .put("area_code", 1)
                            .put("pagesize", limit)
                            .put("rank_cid", chart.cycleId)
                            .put("type", 1)
                            .put("page", page + 1)
                            .put("rank_id", chart.id.toLongOrNull() ?: chart.id),
                    tid = "369",
                )
            val data = json.optJSONObject("data") ?: json
            val rows =
                array(data, "songlist", "audio_list", "info", "list", "songs", "data")
                    ?: JSONArray()
            val songs = objects(rows).mapNotNull { PlatformJson.kugouTrack(it) }
            val complete =
                if (songs.isEmpty()) songs else runCatching { details(songs) }.getOrDefault(songs)
            PlatformPage(
                complete,
                (page + 1) * limit <
                    data.optInt("total", data.optInt("total_count", rows.length())),
            )
        }
    }

    fun details(tracks: List<PlatformTrack>): List<PlatformTrack> {
        if (tracks.isEmpty()) return tracks
        require(tracks.size <= 300 && tracks.all { it.platform == platform })
        if (platform == MusicPlatform.NETEASE) {
            val json =
                netease(
                    "v3/song/detail",
                    JSONObject()
                        .put(
                            "c",
                            JSONArray(tracks.map { JSONObject().put("id", it.id) }).toString(),
                        ),
                    web = true,
                )
            val privileges =
                objects(json.optJSONArray("privileges")).associateBy { it.optString("id") }
            val resolved =
                objects(json.optJSONArray("songs")).associate { song ->
                    val track =
                        PlatformJson.neteaseTrack(song)
                            .copy(
                                access =
                                    PlatformAudioJson.neteaseAccess(
                                        song,
                                        privileges[song.optString("id")],
                                    ),
                                qualities =
                                    PlatformAudioJson.neteaseQualities(
                                        song,
                                        privileges[song.optString("id")],
                                    ),
                            )
                    track.id to track
                }
            return tracks.map { resolved[it.id] ?: it }
        }
        val json =
            kugou(
                "/v2/get_res_privilege/lite",
                data =
                    JSONObject()
                        .put("appid", KugouLite.APP_ID.toInt())
                        .put("clientver", KugouLite.CLIENT_VERSION.toInt())
                        .put("area_code", 1)
                        .put("behavior", "play")
                        .put("need_hash_offset", 1)
                        .put("relate", 1)
                        .put("support_verify", 1)
                        .put(
                            "resource",
                            JSONArray(
                                tracks.map {
                                    JSONObject()
                                        .put("type", "audio")
                                        .put("page_id", 0)
                                        .put("hash", it.id.lowercase())
                                        .put("album_id", it.albumId.toLongOrNull() ?: 0)
                                }
                            ),
                        )
                        .put(
                            "qualities",
                            JSONArray(
                                listOf(
                                    "128",
                                    "320",
                                    "flac",
                                    "high",
                                    "viper_tape",
                                    "viper_clear",
                                    "viper_atmos",
                                    "super",
                                    "multitrack",
                                )
                            ),
                        ),
                router = "media.store.kugou.com",
            )
        val resources =
            objects(json.optJSONArray("data")).associateBy { it.optString("hash").uppercase() }
        val updated = tracks.map { track ->
            val resource = resources[track.id.uppercase()] ?: return@map track
            val qualities = PlatformAudioJson.kugouQualities(resource)
            val standard =
                qualities.firstOrNull { it.quality == AudioQuality.STANDARD }?.access
                    ?: PlatformAudioJson.kugouAccess(resource)
            track.copy(
                access = standard,
                qualities = qualities,
                albumId = string(resource, "album_id").ifBlank { track.albumId },
                audioId = string(resource, "album_audio_id", "mixsongid").ifBlank { track.audioId },
                cover =
                    track.cover.ifBlank {
                        image(
                            string(resource, "cover", "imgurl", "sizable_cover").ifBlank {
                                string(
                                    resource.optJSONObject("info") ?: JSONObject(),
                                    "image",
                                    "cover",
                                )
                            }
                        )
                    },
            )
        }
        val missing = updated.filter { it.cover.isBlank() && it.audioId != "0" }
        if (missing.isEmpty()) return updated
        val metadata = runCatching { kugouMetadata(missing) }.getOrDefault(emptyMap())
        return updated.map { track ->
            metadata[track.audioId]?.let { value ->
                track.copy(
                    cover =
                        image(
                            string(
                                value.optJSONObject("album_info") ?: JSONObject(),
                                "sizable_cover",
                                "cover",
                                "imgurl",
                            )
                        ),
                    album =
                        track.album.ifBlank {
                            string(
                                value.optJSONObject("album_info") ?: JSONObject(),
                                "name",
                                "album_name",
                            )
                        },
                )
            } ?: track
        }
    }

    private fun kugouMetadata(tracks: List<PlatformTrack>): Map<String, JSONObject> {
        val json =
            kugou(
                "/kmr/v2/audio",
                data =
                    JSONObject()
                        .put(
                            "data",
                            JSONArray(
                                tracks.map {
                                    JSONObject().put("entity_id", it.audioId.toLongOrNull() ?: 0)
                                }
                            ),
                        )
                        .put("fields", "album_info,base,audio_info,authors.base"),
                router = "openapi.kugou.com",
                tid = "238",
            )
        val data = json.optJSONObject("data") ?: json
        return objects(array(data, "list", "info", "data") ?: json.optJSONArray("data"))
            .associateBy {
                string(it, "entity_id", "album_audio_id", "mixsongid", "id").ifBlank {
                    string(it.optJSONObject("base") ?: JSONObject(), "album_audio_id", "mixsongid")
                }
            }
    }

    fun hotSearch(): List<String> =
        if (platform == MusicPlatform.NETEASE) {
            val json = netease("hotsearchlist/get", JSONObject(), web = true)
            objects(json.optJSONArray("data"))
                .map { it.optString("searchWord") }
                .filter { it.isNotBlank() }
                .take(12)
        } else {
            val json =
                kugou(
                    "/api/v3/search/hot_tab",
                    values = mapOf("navid" to "1", "plat" to "2"),
                    router = "msearch.kugou.com",
                )
            keywordObjects(json.opt("data") ?: json)
                .map { string(it, "keyword", "searchword", "searchWord", "word") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(12)
        }

    fun comments(
        track: PlatformTrack,
        page: Int = 0,
        hot: Boolean = true,
        cursor: String = "0",
    ): CommentPage {
        require(track.platform == platform && page >= 0)
        val result =
            if (platform == MusicPlatform.NETEASE) {
                val json =
                    netease(
                        "v2/resource/comments",
                        JSONObject()
                            .put("threadId", "R_SO_4_${track.id}")
                            .put("pageNo", page + 1)
                            .put("pageSize", COMMENT_SIZE)
                            .put("showInner", true)
                            .put("sortType", if (hot) 2 else 3)
                            .put("cursor", if (hot) "normalHot#${page * COMMENT_SIZE}" else cursor),
                    )
                val data = json.optJSONObject("data") ?: json
                val comments =
                    objects(data.optJSONArray("comments")).map {
                        PlatformDiscoveryJson.neteaseComment(it)
                    }
                val total = commentTotal(data, "totalCount", "total")
                CommentPage(
                    comments,
                    data.optBoolean("hasMore", data.optBoolean("more")),
                    total ?: 0,
                    data.optString("cursor", comments.lastOrNull()?.time?.toString() ?: cursor),
                    totalKnown = total != null,
                )
            } else {
                val resolved =
                    if (track.audioId == "0") details(listOf(track)).firstOrNull() ?: track
                    else track
                require(resolved.audioId != "0") { "这首歌暂未提供评论信息" }
                commentTracks[track.key] = resolved
                val json =
                    kugou(
                        "/mcomment/v1/cmtlist",
                        values =
                            mapOf(
                                "ver" to "6",
                                "mixsongid" to resolved.audioId,
                                "need_show_image" to "1",
                                "p" to (page + 1).toString(),
                                "pagesize" to COMMENT_SIZE.toString(),
                                "show_classify" to "1",
                                "show_hotword_list" to "1",
                                "extdata" to "0",
                                "code" to PlatformProtocol.KUGOU_SONG_COMMENT_CODE,
                            ),
                        data = JSONObject(),
                    )
                val data = json.optJSONObject("data") ?: json
                string(data, "childrenid", "special_id")
                    .takeIf { it.isNotBlank() }
                    ?.let { commentResources[track.key] = it }
                val array =
                    if (hot) array(data, "hot_list", "hotlist", "hot", "list", "comments")
                    else array(data, "list", "comments", "new_list")
                val comments =
                    objects(array)
                        .map {
                            PlatformDiscoveryJson.kugouComment(it).let { comment ->
                                comment.copy(
                                    resourceId =
                                        comment.resourceId.ifBlank {
                                            string(data, "childrenid", "special_id")
                                        }
                                )
                            }
                        }
                        .filter { it.content.isNotBlank() }
                comments
                    .firstOrNull { it.resourceId.isNotBlank() }
                    ?.let { commentResources[track.key] = it.resourceId }
                val total = commentTotal(data, "count", "total")
                CommentPage(
                    comments,
                    comments.isNotEmpty() &&
                        ((page + 1) * COMMENT_SIZE < (total ?: Long.MAX_VALUE)),
                    total ?: 0,
                    totalKnown = total != null,
                )
            }
        if (result.totalKnown) DiscoveryStore.publishCommentCount(track, result.total)
        return result
    }

    private fun commentTotal(data: JSONObject, vararg fields: String): Long? =
        fields.firstNotNullOfOrNull { key ->
            val value = data.opt(key)
            ((value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull())?.takeIf { it >= 0 }
        }

    fun commentCount(track: PlatformTrack): Long? {
        require(track.platform == platform)
        return DiscoveryStore.commentCount(track) ?: comments(track).takeIf { it.totalKnown }?.total
    }

    fun postComment(
        track: PlatformTrack,
        content: String,
        reply: PlatformComment? = null,
    ): PlatformComment? {
        require(track.platform == platform)
        val text = content.trim()
        require(text.isNotBlank() && text.length <= 500) { "请输入 1–500 字的评论" }
        require(reply == null || reply.id.isNotBlank()) { "平台没有返回这条评论的标识，请刷新后重试" }
        requireCommentAccount()
        val json =
            if (platform == MusicPlatform.NETEASE) {
                val data = JSONObject().put("threadId", "R_SO_4_${track.id}").put("content", text)
                if (reply != null) data.put("commentId", reply.id)
                netease("resource/comments/${if (reply == null) "add" else "reply"}", data)
            } else {
                val resolved =
                    commentTracks[track.key]
                        ?: if (track.audioId == "0") details(listOf(track)).firstOrNull() ?: track
                        else track
                val resource =
                    reply?.resourceId?.takeIf { it.isNotBlank() } ?: commentResource(track)
                if (reply == null) {
                    val body =
                        JSONObject()
                            .put(
                                "data",
                                JSONObject()
                                    .put("content", text)
                                    .put("album_audio_id", resolved.audioId)
                                    .put("images", JSONArray()),
                            )
                            .toString()
                    kugouComment(
                        "commentsv3/add",
                        mapOf("childrenid" to resource, "childrenname" to track.title),
                        body,
                    )
                } else {
                    val message =
                        if (text.contains("//@")) text
                        else "$text//@${reply.author}:${reply.content}"
                    kugouComment(
                        "commentsv2/reply",
                        mapOf(
                            "childrenid" to resource,
                            "childrenname" to track.title,
                            "content" to message,
                            "tid" to reply.id,
                            "pid" to "0",
                            "is_t" to "1",
                        ),
                        "",
                    )
                }
            }
        DiscoveryStore.invalidateCommentCount(track)
        val comment =
            json.optJSONObject("comment") ?: json.optJSONObject("data")?.optJSONObject("comment")
        return comment?.let {
            if (platform == MusicPlatform.NETEASE) PlatformDiscoveryJson.neteaseComment(it)
            else PlatformDiscoveryJson.kugouComment(it)
        }
    }

    fun likeComment(
        track: PlatformTrack,
        comment: PlatformComment,
        liked: Boolean,
    ): CommentLikeResult {
        require(track.platform == platform && comment.id.isNotBlank()) { "评论标识无效，请刷新后重试" }
        requireCommentAccount()
        if (platform == MusicPlatform.NETEASE) {
            netease(
                "v1/comment/${if (liked) "like" else "unlike"}",
                JSONObject().put("threadId", "R_SO_4_${track.id}").put("commentId", comment.id),
                web = true,
            )
            return CommentLikeResult(liked)
        } else {
            val resolved =
                commentTracks[track.key]
                    ?: if (track.audioId == "0") details(listOf(track)).firstOrNull() ?: track
                    else track
            val resource = comment.resourceId.ifBlank { commentResource(track) }
            val request =
                PlatformProtocol.kugouCommentLike(
                    kugouSession,
                    comment.id,
                    resource,
                    resolved.audioId,
                    liked,
                )
            val json = transport.execute(request).json
            confirmKugouComment(json)
            val data = json.optJSONObject("data") ?: json
            val value = if (json.has("islike")) json.opt("islike") else data.opt("islike")
            val state = PlatformDiscoveryJson.parseLiked(value) ?: liked
            return CommentLikeResult(
                state,
                commentTotal(json, "likenum", "like_count")
                    ?: commentTotal(data, "likenum", "like_count"),
            )
        }
    }

    private fun requireCommentAccount() {
        require(
            if (platform == MusicPlatform.NETEASE)
                PlatformLoginClient.cookieValues(cookie)["MUSIC_U"].orEmpty().isNotBlank()
            else
                kugouSession.optString("token").isNotBlank() &&
                    kugouSession.optString("userid") !in listOf("", "0")
        ) {
            "请先登录${platform.label}，再发表评论或点赞"
        }
    }

    private fun commentResource(track: PlatformTrack): String {
        commentResources[track.key]?.let {
            return it
        }
        comments(track)
        return commentResources[track.key]?.takeIf { it.isNotBlank() }
            ?: error("平台没有返回歌曲的评论标识，请刷新后重试")
    }

    private fun kugouComment(
        route: String,
        values: Map<String, String>,
        body: String?,
    ): JSONObject {
        val request = PlatformProtocol.kugouComment(route, kugouSession, values, body)
        val json = transport.execute(request).json
        confirmKugouComment(json)
        return json
    }

    private fun confirmKugouComment(json: JSONObject) {
        PlatformJson.checkKugou(json, "评论操作")
        require(json.optInt("status") == 1) {
            string(json, "msg", "message").ifBlank { "平台未确认评论操作，请重试" }
        }
    }

    private fun netease(path: String, data: JSONObject, web: Boolean = false): JSONObject {
        val response = transport.execute(PlatformProtocol.netease(path, data, cookie, web = web))
        cookie = PlatformLoginClient.mergeCookies(cookie, response.cookies)
        require(response.json.optInt("code") == 200) {
            "网易云暂时无法读取这些内容（${response.json.optInt("code")}），请重试"
        }
        return response.json
    }

    private fun kugou(
        path: String,
        values: Map<String, String> = emptyMap(),
        data: JSONObject? = null,
        router: String? = null,
        tid: String? = null,
    ): JSONObject {
        var request =
            PlatformProtocol.kugou(
                path,
                kugouSession.optString("device").ifBlank { "ncm-music-discovery" },
                kugouSession,
                values,
                data,
                router,
            )
        if (tid != null) request = request.copy(headers = request.headers + ("kg-tid" to tid))
        val json = transport.execute(request).json
        PlatformJson.checkKugou(json, "酷狗内容读取")
        return json
    }

    companion object {
        const val SEARCH_SIZE = 30
        const val COMMENT_SIZE = 20

        fun forPlatform(context: Context, platform: MusicPlatform): PlatformDiscoveryClient =
            PlatformDiscoveryClient(
                platform,
                AccountStore.authorization(context, platform)?.session.orEmpty(),
            )

        internal fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList()
            else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun array(json: JSONObject, vararg keys: String): JSONArray? =
            keys.firstNotNullOfOrNull {
                json.optJSONArray(it)
            }

        internal fun string(json: JSONObject, vararg keys: String): String =
            keys
                .firstNotNullOfOrNull {
                    json.optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
                }
                .orEmpty()

        private fun image(value: String) =
            PlatformLoginClient.safeAvatar(value.replace("{size}", "480"))

        private fun chartObjects(value: Any?): List<JSONObject> =
            when (value) {
                is JSONArray -> objects(value).flatMap { chartObjects(it) }
                is JSONObject ->
                    (if (value.has("rankid") || value.has("rank_id")) listOf(value)
                    else emptyList()) +
                        listOf("info", "list", "children", "ranks", "rank_list", "data").flatMap {
                            chartObjects(value.opt(it))
                        }
                else -> emptyList()
            }

        private fun keywordObjects(value: Any?): List<JSONObject> =
            when (value) {
                is JSONArray -> objects(value).flatMap { keywordObjects(it) }
                is JSONObject ->
                    if (listOf("keyword", "searchword", "searchWord", "word").any { value.has(it) })
                        listOf(value)
                    else
                        listOf("list", "info", "keywords", "data", "hotword", "hotkeywords")
                            .flatMap { keywordObjects(value.opt(it)) }
                else -> emptyList()
            }
    }
}

internal object PlatformDiscoveryJson {
    fun parseLiked(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt().takeIf { it in 0..1 }?.let { it == 1 }
            is String ->
                value.toBooleanStrictOrNull()
                    ?: value.toIntOrNull()?.takeIf { it in 0..1 }?.let { it == 1 }
            else -> null
        }

    fun neteaseComment(json: JSONObject): PlatformComment {
        val user = json.optJSONObject("user") ?: JSONObject()
        return PlatformComment(
            json.optString("commentId"),
            user.optString("nickname", "听友"),
            json.optString("content"),
            PlatformLoginClient.safeAvatar(user.optString("avatarUrl")),
            json.optLong("likedCount"),
            json.optLong("time"),
            parseLiked(json.opt("liked")),
            json.optString("threadId"),
            user.optString("userId"),
        )
    }

    fun kugouComment(json: JSONObject): PlatformComment {
        val user = json.optJSONObject("user") ?: JSONObject()
        val numericTime = json.optLong("addtime", json.optLong("time", json.optLong("create_time")))
        val time =
            if (numericTime > 0) {
                if (numericTime < 100000000000L) numericTime * 1000 else numericTime
            } else
                runCatching {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                            .apply { isLenient = false }
                            .parse(json.optString("addtime"))
                            ?.time ?: 0L
                    }
                    .getOrDefault(0L)
        return PlatformComment(
            PlatformDiscoveryClient.string(json, "cmtid", "comment_id", "id"),
            PlatformDiscoveryClient.string(json, "user_name", "nickname", "username").ifBlank {
                user.optString("nickname", "听友")
            },
            PlatformDiscoveryClient.string(json, "content", "comment", "message"),
            PlatformLoginClient.safeAvatar(
                PlatformDiscoveryClient.string(json, "userpic", "avatar", "user_pic").ifBlank {
                    user.optString("avatar")
                }
            ),
            json.optLong(
                "like_count",
                json.optLong("upcount", json.optJSONObject("like")?.optLong("count") ?: 0),
            ),
            time,
            listOf(
                    json.opt("is_like"),
                    json.optJSONObject("like")?.opt("haslike"),
                    json.optJSONObject("like")?.opt("status"),
                )
                .firstNotNullOfOrNull(::parseLiked),
            PlatformDiscoveryClient.string(json, "special_child_id", "childrenid", "special_id"),
            PlatformDiscoveryClient.string(json, "kugouid", "user_id", "userid").ifBlank {
                user.optString("userid")
            },
        )
    }
}
