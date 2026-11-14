package com.qiuyuxc.tingyu

import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Reads the signed-in account's playlists and resolves a fresh, authorized URL for each play. */
internal class PlatformLibraryClient(
    val profile: PlatformProfile,
    private val session: String,
    private val transport: LoginTransport = LoginTransport(PlatformLoginClient::request),
) {
    private val kugouSession =
        if (profile.platform == MusicPlatform.KUGOU) JSONObject(session) else JSONObject()
    private val playlistIds = ConcurrentHashMap<String, List<String>>()
    private val knownTracks = ConcurrentHashMap<String, PlatformTrack>()
    private var neteaseCookie = session
    var resolvedMembership: PlatformMembership? = null
        private set

    init {
        when (profile.platform) {
            MusicPlatform.NETEASE ->
                require(
                    PlatformLoginClient.cookieValues(session)["MUSIC_U"].orEmpty().isNotBlank()
                ) {
                    "网易云登录已失效，请重新扫码"
                }
            MusicPlatform.KUGOU -> {
                require(
                    kugouSession.optString("edition") == KugouLite.EDITION &&
                        kugouSession.optString("appid") == KugouLite.APP_ID
                ) {
                    "请使用酷狗概念版重新扫码授权"
                }
                require(
                    kugouSession.optString("token").isNotBlank() &&
                        kugouSession.optString("userid") == profile.userId
                ) {
                    "酷狗概念版登录已失效，请重新扫码"
                }
                if (!kugouSession.optString("dfid").matches(Regex("[a-zA-Z0-9]{24}"))) {
                    kugouSession.put("dfid", UUID.randomUUID().toString().replace("-", "").take(24))
                }
            }
        }
    }

    fun loadLibrary(progress: (String) -> Unit = {}): PlatformLibrary {
        playlistIds.clear()
        knownTracks.clear()
        progress("正在同步歌单…")
        val lists = collectPages { page -> playlists(page) }.distinctBy { it.id }.toMutableList()
        var favorite = lists.firstOrNull { it.favorites }
        var favoriteProblem: String? = null
        if (favorite == null && profile.platform == MusicPlatform.KUGOU) {
            // Some accounts omit system lists from the combined collection response.
            val owned = runCatching {
                collectPages { page -> playlists(page, 0) }
            }
                .getOrDefault(emptyList())
            lists.addAll(owned.filter { item -> lists.none { it.id == item.id } })
            favorite = lists.firstOrNull { it.favorites }
        }
        if (favorite == null && profile.platform == MusicPlatform.KUGOU) {
            val lookup = runCatching { defaultFavorites() }
            favorite = lookup.getOrNull()
            favoriteProblem = lookup.exceptionOrNull()?.message
            favorite?.let { lists.add(0, it) }
        }
        val notices = mutableListOf<String>()
        var favoritesReady = true
        val liked =
            if (favorite == null) {
                if (profile.platform == MusicPlatform.KUGOU) {
                    favoritesReady = false
                    notices += "喜欢的歌曲尚未同步：${favoriteProblem ?: "平台未返回默认收藏歌单"}"
                    val previous = PlatformLibraryStore.get(profile)
                    previous?.favoritePlaylist?.let { lists.add(0, it) }
                    previous?.favorites?.values?.toList().orEmpty()
                } else emptyList()
            } else {
                progress("正在同步喜欢的歌曲…")
                try {
                    val list = favorite
                    favoriteTracks(list).also { songs ->
                        val synchronized =
                            list.copy(
                                count = songs.size,
                                cover = list.cover.ifBlank { songs.firstOrNull()?.cover.orEmpty() },
                            )
                        val index = lists.indexOfFirst { it.favorites }
                        if (index >= 0) lists[index] = synchronized else lists.add(0, synchronized)
                    }
                } catch (error: Exception) {
                    favoritesReady = false
                    notices += "喜欢的歌曲尚未同步：${error.message ?: "请重试"}"
                    val previous = PlatformLibraryStore.get(profile)
                    previous?.favoritePlaylist?.let { cached ->
                        if (lists.none { it.favorites }) lists.add(0, cached)
                    }
                    previous?.favorites?.values?.toList().orEmpty()
                }
            }
        return PlatformLibrary(lists, liked.associateBy { it.key }, notices, favoritesReady)
    }

    fun favoriteTracks(playlist: PlatformPlaylist): List<PlatformTrack> {
        require(playlist.favorites)
        return collectPages { page -> tracks(playlist, page) }
            .also { songs ->
                require(playlist.count <= songs.size) {
                    "平台歌单有 ${playlist.count} 首，本次只读到 ${songs.size} 首，请刷新重试"
                }
            }
    }

    /** The personal page only needs the catalog, without downloading every liked song first. */
    fun playlistCatalog(): List<PlatformPlaylist> {
        val lists = collectPages { page -> playlists(page) }.distinctBy { it.id }.toMutableList()
        if (profile.platform == MusicPlatform.KUGOU && lists.none { it.favorites }) {
            val owned = runCatching {
                collectPages { page -> playlists(page, 0) }
            }
                .getOrDefault(emptyList())
            lists.addAll(owned.filter { item -> lists.none { it.id == item.id } })
            if (lists.none { it.favorites })
                runCatching { defaultFavorites() }.getOrNull()?.let { lists.add(0, it) }
        }
        return lists.sortedByDescending { it.favorites }
    }

    private fun defaultFavorites(): PlatformPlaylist {
        val id = "collection_3_${profile.userId}_2_0"
        val json =
            kugou(
                "/v3/get_list_info",
                data =
                    authData()
                        .put("data", JSONArray().put(JSONObject().put("global_collection_id", id))),
                router = "pubsongs.kugou.com",
            )
        return PlatformJson.kugouPlaylistObjects(json.opt("data"))
            .map { PlatformJson.kugouPlaylist(it, profile.userId) }
            .firstOrNull { it.id == id && it.favorites && it.owned }
            ?: error("平台未返回当前账号的默认收藏歌单，请刷新重试")
    }

    fun playlists(page: Int = 0, type: Int = 2): PlatformPage<PlatformPlaylist> {
        require(page >= 0)
        return when (profile.platform) {
            MusicPlatform.NETEASE -> {
                val json =
                    netease(
                        "user/playlist",
                        JSONObject()
                            .put("uid", profile.userId)
                            .put("limit", PAGE_SIZE)
                            .put("offset", page * PAGE_SIZE)
                            .put("includeVideo", false),
                        web = true,
                    )
                val array = json.optJSONArray("playlist") ?: error("网易云未返回歌单，请刷新重试")
                PlatformPage(
                    objects(array).map { PlatformJson.neteasePlaylist(it, profile.userId) },
                    json.optBoolean("more", array.length() >= PAGE_SIZE),
                )
            }
            MusicPlatform.KUGOU -> {
                val json =
                    kugou(
                        "/v7/get_all_list",
                        data =
                            authData()
                                .put("total_ver", 0)
                                .put("type", type)
                                .put("page", page + 1)
                                .put("pagesize", PAGE_SIZE),
                        values = mapOf("plat" to "1"),
                        router = "cloudlist.service.kugou.com",
                    )
                val data = json.optJSONObject("data") ?: json
                val primary =
                    listOf("info", "lists", "list").firstNotNullOfOrNull { key ->
                        data.opt(key)?.takeUnless { it == JSONObject.NULL }
                    } ?: json.optJSONArray("data")
                val entries =
                    PlatformJson.kugouPlaylistObjects(primary) +
                        listOf("special_list", "default_list", "special_lists").flatMap { key ->
                            PlatformJson.kugouPlaylistObjects(data.opt(key))
                        }
                require(primary != null || entries.isNotEmpty()) { "概念版未返回歌单，请刷新重试" }
                PlatformPage(
                    entries
                        .map { PlatformJson.kugouPlaylist(it, profile.userId) }
                        .filter { it.listId.isNotBlank() }
                        .distinctBy { it.id },
                    hasMore(
                        data,
                        (primary as? JSONArray)?.length() ?: entries.size,
                        page,
                        PAGE_SIZE,
                    ),
                )
            }
        }
    }

    fun tracks(playlist: PlatformPlaylist, page: Int = 0): PlatformPage<PlatformTrack> {
        require(playlist.platform == profile.platform && page >= 0)
        return when (profile.platform) {
            MusicPlatform.NETEASE -> {
                val ids =
                    playlistIds.getOrPut(playlist.id) {
                        val json =
                            netease(
                                "v6/playlist/detail",
                                JSONObject().put("id", playlist.id).put("n", 100000).put("s", 0),
                            )
                        val detail = json.optJSONObject("playlist") ?: error("网易云歌单暂时不可用")
                        objects(detail.optJSONArray("tracks")).forEach { song ->
                            val track = PlatformJson.neteaseTrack(song)
                            knownTracks[track.id] = track
                        }
                        objects(detail.optJSONArray("trackIds"))
                            .map { it.optString("id") }
                            .filter { it.isNotBlank() }
                            .ifEmpty {
                                objects(detail.optJSONArray("tracks")).map { it.optString("id") }
                            }
                    }
                val selected = ids.drop(page * TRACK_PAGE_SIZE).take(TRACK_PAGE_SIZE)
                val missing = selected.filter { !knownTracks.containsKey(it) }
                if (missing.isNotEmpty()) {
                    val songs =
                        netease(
                            "v3/song/detail",
                            JSONObject()
                                .put(
                                    "c",
                                    JSONArray(missing.map { JSONObject().put("id", it) })
                                        .toString(),
                                ),
                            web = true,
                        )
                    objects(songs.optJSONArray("songs")).forEach {
                        val track = PlatformJson.neteaseTrack(it)
                        knownTracks[track.id] = track
                    }
                }
                PlatformPage(
                    selected.map { id ->
                        knownTracks[id] ?: PlatformTrack(profile.platform, id, "暂不可用的歌曲")
                    },
                    (page + 1) * TRACK_PAGE_SIZE < ids.size,
                )
            }
            MusicPlatform.KUGOU -> {
                val json =
                    kugou(
                        "/v4/get_list_all_file_v3",
                        data =
                            authData()
                                .put("listid", playlist.listId)
                                .put("type", playlist.type)
                                .put("page", page + 1)
                                .put("pagesize", TRACK_PAGE_SIZE)
                                .put("area_code", 1)
                                .put("allplatform", 1)
                                .put("show_cover", 1),
                        router = "cloudlist.service.kugou.com",
                    )
                val data = json.optJSONObject("data") ?: json
                val items =
                    array(data, "info", "files", "list", "song_list", "songs")
                        ?: error("概念版未返回歌曲，请刷新重试")
                PlatformPage(
                    objects(items)
                        .mapNotNull { PlatformJson.kugouTrack(it) }
                        .also { songs ->
                            songs.forEach { knownTracks[it.id] = it }
                        },
                    hasMore(data, items.length(), page, TRACK_PAGE_SIZE),
                )
            }
        }
    }

    fun membership(): PlatformMembership =
        when (profile.platform) {
            MusicPlatform.NETEASE -> {
                val results =
                    listOf("client", "front").map { endpoint ->
                        runCatching {
                            netease(
                                "music-vip-membership/$endpoint/vip/info",
                                JSONObject().put("userId", profile.userId),
                                web = true,
                            )
                        }
                    }
                val responses = results.mapNotNull { it.getOrNull() }
                if (responses.isEmpty()) throw requireNotNull(results.first().exceptionOrNull())
                PlatformAudioJson.neteaseMembership(responses)
            }
            MusicPlatform.KUGOU -> {
                val response =
                    transport.execute(
                        PlatformProtocol.kugou(
                            "/v1/get_union_vip",
                            kugouSession.optString("device"),
                            kugouSession,
                            values =
                                mapOf(
                                    "busi_type" to "concept",
                                    "opt_product_types" to "dvip,qvip",
                                    "product_type" to "svip",
                                ),
                            host = "kugouvip.kugou.com",
                        )
                    )
                checkKugou(response.json, "会员信息读取")
                PlatformAudioJson.kugouMembership(response.json)
            }
        }

    fun stream(
        track: PlatformTrack,
        quality: AudioQuality = AudioQuality.STANDARD,
        knownMembership: PlatformMembership? = null,
    ): PlatformStream {
        require(track.platform == profile.platform)
        require(quality in AudioQuality.forPlatform(profile.platform)) { "这个平台不提供该音质" }
        if (profile.platform == MusicPlatform.NETEASE) {
            val data =
                JSONObject()
                    .put("ids", JSONArray(listOf(track.id)).toString())
                    .put("level", quality.netease)
                    .put(
                        "encodeType",
                        if (quality >= AudioQuality.LOSSLESS) "flac" else "mp3",
                    )
            if (quality == AudioQuality.SPATIAL) data.put("immerseType", "c51")
            return PlatformJson.stream(
                profile.platform,
                track.id,
                netease(
                    "song/enhance/player/url/v1",
                    data,
                ),
            )
        }
        val membership =
            knownMembership?.takeIf { it.freshAt() } ?: runCatching { membership() }.getOrNull()
        resolvedMembership = membership
        val candidates = KugouQualityFallback.candidates(track, quality, membership)
        var preview: PlatformStream? = null
        var provisional: PlatformStream? = null
        val playbackSession = JSONObject(kugouSession.toString())
        fun values(selected: AudioQuality) =
            mapOf(
                "album_id" to track.albumId,
                "area_code" to "1",
                "hash" to track.id.lowercase(),
                "ssa_flag" to "is_fromtrack",
                "version" to "11430",
                "page_id" to "967177915",
                "quality" to selected.kugou,
                "album_audio_id" to track.audioId,
                "behavior" to "play",
                "pid" to "411",
                "cmd" to "26",
                "pidversion" to "3001",
                "IsFreePart" to "0",
                "ppage_id" to "356753938,823673182,967485191",
                "cdnBackup" to "1",
                "module" to "",
                "clientver" to "11430",
                "need_ogg" to "1",
            )
        fun direct(selected: AudioQuality) =
            kugou(
                "/v5/url",
                values = values(selected),
                router = "trackercdn.kugou.com",
                tracker = true,
                checkStatus = false,
                requestSession = playbackSession,
            )

        fun result(json: JSONObject, selected: AudioQuality): PlatformStream? {
            if (
                !PlatformJson.kugouSuccess(json) ||
                    PlatformJson.streamUrl(profile.platform, track.id, json).isBlank()
            )
                return null
            val stream = PlatformJson.stream(profile.platform, track.id, json)
            val actual = stream.quality ?: selected
            val changed = actual != quality || selected != quality
            val resolved =
                stream.copy(
                    notice =
                        if (changed && stream.quality != null)
                            "${quality.label(profile.platform)}暂不可用，已切换至${actual.label(profile.platform)}"
                        else if (changed)
                            "${quality.label(profile.platform)}暂不可用，已改为请求${selected.label(profile.platform)}，实际音质待平台确认"
                        else stream.notice,
                    defaultSuggestion =
                        AudioQuality.HIRES.takeIf {
                            quality.ordinal > AudioQuality.HIRES.ordinal &&
                                stream.quality == AudioQuality.HIRES &&
                                !stream.trial &&
                                membership?.activeAt() == true
                        },
                )
            if (resolved.trial) {
                if (preview == null) preview = resolved
                return null
            }
            // Some replies silently return standard; still try the member's Hi-Res option.
            if (
                actual == AudioQuality.STANDARD &&
                    selected != AudioQuality.STANDARD &&
                    AudioQuality.HIRES in candidates
            ) {
                provisional = resolved
                return null
            }
            return resolved
        }

        var last = direct(quality)
        result(last, quality)?.let {
            return it
        }
        // An account rejection cannot be repaired by another quality or a song authorization.
        if (PlatformJson.kugouErrorCode(last) == 20018) checkKugou(last, "歌曲播放")

        // Concept songs can require per-song authorization in addition to the login token.
        // Keep the same device fingerprint through authorization and its tracker request.
        var authorizationFailure: JSONObject? = null
        if (playbackSession.optString("auth").isBlank()) {
            val verified =
                kugou(
                    "/v1/user_verify",
                    values = mapOf("module_id" to "51"),
                    router = "trackercdngz.kugou.com",
                    checkStatus = false,
                    requestSession = playbackSession,
                )
            if (PlatformJson.kugouErrorCode(verified) == 20018) checkKugou(verified, "账号权益验证")
            val accountAuth = verified.optJSONObject("data")?.optString("auth").orEmpty()
            if (PlatformJson.kugouSuccess(verified) && accountAuth.isNotBlank()) {
                playbackSession.put("auth", accountAuth)
            } else authorizationFailure = verified
        }
        val authorization =
            if (playbackSession.optString("auth").isNotBlank())
                kugou(
                    "/v1/authorization",
                    values =
                        mapOf(
                            "authorization" to playbackSession.optString("auth"),
                            "module_id" to "51",
                            "album_audio_id" to track.audioId,
                            "hash" to track.id.lowercase(),
                            "clientver" to "11561",
                        ),
                    router = "trackercdngz.kugou.com",
                    checkStatus = false,
                    requestSession = playbackSession,
                )
            else null
        if (authorization != null) {
            if (PlatformJson.kugouErrorCode(authorization) == 20018)
                checkKugou(authorization, "歌曲授权")
            if (!PlatformJson.kugouSuccess(authorization)) authorizationFailure = authorization
        }
        val auth = authorization?.takeIf { PlatformJson.kugouSuccess(it) }?.optJSONObject("data")
        fun authorized(selected: AudioQuality) =
            kugou(
                "/tracker/v5/url",
                values =
                    values(selected) +
                        mapOf(
                            "auth" to auth!!.getString("auth"),
                            "open_time" to auth.optString("open_time", "0"),
                            "need_ogg" to "1",
                            "need_m" to "0",
                            "ptype" to "0",
                            "mtype" to "0",
                            "module_id" to "51",
                            "clientver" to "11561",
                        ),
                tracker = true,
                checkStatus = false,
                requestSession = playbackSession,
            )

        if (!auth?.optString("auth").isNullOrBlank()) {
            last = authorized(quality)
            result(last, quality)?.let {
                return it
            }
            if (PlatformJson.kugouErrorCode(last) == 20018) checkKugou(last, "歌曲播放")
        }

        // Members try Hi-Res before standard. Never raise a lower saved preference.
        for (fallback in candidates.drop(1)) {
            if (fallback == AudioQuality.STANDARD)
                provisional?.let {
                    return it
                }
            last = direct(fallback)
            result(last, fallback)?.let {
                return it
            }
            if (PlatformJson.kugouErrorCode(last) == 20018) checkKugou(last, "歌曲播放")
            if (!auth?.optString("auth").isNullOrBlank()) {
                last = authorized(fallback)
                result(last, fallback)?.let {
                    return it
                }
                if (PlatformJson.kugouErrorCode(last) == 20018) checkKugou(last, "歌曲播放")
            }
        }
        provisional?.let {
            return it
        }
        preview?.let {
            return it
        }
        val failure = authorizationFailure?.takeIf { PlatformJson.kugouErrorCode(it) != 0 } ?: last
        if (
            PlatformJson.kugouErrorCode(failure) != 0 ||
                !PlatformJson.kugouSuccess(failure) && PlatformJson.kugouMessage(failure) != null
        )
            checkKugou(failure, "歌曲播放")
        error("这首歌暂时无法播放，平台未向当前账号提供播放地址；可重试或在酷狗概念版查看歌曲权限")
    }

    fun setFavorite(track: PlatformTrack, favorite: Boolean, library: PlatformLibrary) {
        require(track.platform == profile.platform)
        require(library.favoritesReady) { "喜欢的歌曲尚未同步，请刷新后重试" }
        when (profile.platform) {
            MusicPlatform.NETEASE ->
                netease(
                    "song/like",
                    JSONObject()
                        .put("trackId", track.id)
                        .put("userid", profile.userId)
                        .put("like", favorite),
                )
            MusicPlatform.KUGOU -> {
                val list = library.favoritePlaylist ?: error("没有找到概念版的「我喜欢」歌单，请先在平台创建")
                require(list.owned) { "这个歌单不能修改" }
                if (favorite) {
                    val song =
                        JSONObject()
                            .put("number", 1)
                            .put("name", "${track.artist} - ${track.title}")
                            .put("hash", track.id)
                            .put("size", 0)
                            .put("sort", 0)
                            .put("timelen", track.durationMs)
                            .put("bitrate", 0)
                            .put("album_id", track.albumId.toLongOrNull() ?: 0)
                            .put("mixsongid", track.audioId.toLongOrNull() ?: 0)
                    kugou(
                        "/cloudlist.service/v6/add_song",
                        data =
                            authData()
                                .put("listid", list.listId)
                                .put("list_ver", 0)
                                .put("type", 0)
                                .put("slow_upload", 1)
                                .put("scene", "false;null")
                                .put("data", JSONArray().put(song)),
                        values =
                            mapOf(
                                "last_time" to (System.currentTimeMillis() / 1000).toString(),
                                "last_area" to "gztx",
                            ),
                    )
                } else {
                    val fileId = library.favorites[track.key]?.fileId.orEmpty()
                    require(fileId.isNotBlank()) { "收藏信息尚未同步，请刷新后重试" }
                    kugou(
                        "/v4/delete_songs",
                        data =
                            authData()
                                .put("listid", list.listId)
                                .put("list_ver", 0)
                                .put("type", 0)
                                .put(
                                    "data",
                                    JSONArray().put(JSONObject().put("fileid", fileId.toLong())),
                                ),
                        router = "cloudlist.service.kugou.com",
                    )
                }
            }
        }
    }

    fun lyrics(track: PlatformTrack): String =
        when (track.platform) {
            MusicPlatform.NETEASE -> {
                val json =
                    netease(
                        "song/lyric",
                        JSONObject().put("id", track.id).put("tv", -1).put("lv", -1).put("rv", -1),
                    )
                json.optJSONObject("lrc")?.optString("lyric").orEmpty()
            }
            MusicPlatform.KUGOU -> {
                val search =
                    lyricRequest(
                        "/v1/search",
                        mapOf(
                            "appid" to KugouLite.APP_ID,
                            "clientver" to KugouLite.CLIENT_VERSION,
                            "hash" to track.id,
                            "duration" to track.durationMs.toString(),
                            "keyword" to "${track.artist} ${track.title}",
                            "lrctxt" to "1",
                            "man" to "no",
                        ),
                    )
                val candidate = objects(search.optJSONArray("candidates")).firstOrNull()
                if (candidate == null) ""
                else {
                    val json =
                        lyricRequest(
                            "/download",
                            mapOf(
                                "ver" to "1",
                                "client" to "android",
                                "id" to candidate.optString("id"),
                                "accesskey" to candidate.optString("accesskey"),
                                "fmt" to "lrc",
                                "charset" to "utf8",
                            ),
                        )
                    String(Base64.getDecoder().decode(json.optString("content")), Charsets.UTF_8)
                }
            }
        }

    private fun lyricRequest(path: String, values: Map<String, String>): JSONObject {
        val params = values + ("signature" to PlatformProtocol.kugouSignature(values))
        return transport
            .execute(LoginRequest("https://lyrics.kugou.com$path?${PlatformProtocol.form(params)}"))
            .json
    }

    private fun authData() =
        JSONObject()
            .put("userid", profile.userId.toLong())
            .put("token", kugouSession.getString("token"))

    private fun kugou(
        path: String,
        values: Map<String, String> = emptyMap(),
        data: JSONObject? = null,
        router: String? = null,
        tracker: Boolean = false,
        checkStatus: Boolean = true,
        requestSession: JSONObject = kugouSession,
    ): JSONObject {
        val json =
            transport
                .execute(
                    PlatformProtocol.kugou(
                        path,
                        requestSession.optString("device"),
                        requestSession,
                        values,
                        data,
                        router,
                        tracker,
                    )
                )
                .json
        if (checkStatus) checkKugou(json, "概念版请求")
        return json
    }

    private fun checkKugou(json: JSONObject, action: String) {
        PlatformJson.checkKugou(json, action)
    }

    private fun netease(
        path: String,
        data: JSONObject,
        web: Boolean = false,
        androidClient: Boolean = false,
    ): JSONObject {
        val response =
            transport.execute(
                PlatformProtocol.netease(
                    path,
                    data,
                    neteaseCookie,
                    web = web,
                    androidClient = androidClient,
                )
            )
        neteaseCookie = PlatformLoginClient.mergeCookies(neteaseCookie, response.cookies)
        val code = response.json.optInt("code")
        require(code == 200) {
            when (code) {
                301,
                302 -> "网易云登录已失效，请重新扫码"
                -460 -> "网易云要求安全验证，请在网易云 App 完成验证后重试（-460）"
                else ->
                    response.json.optString("message").takeIf { it.isNotBlank() }
                        ?: "网易云请求失败（$code），请稍后重试"
            }
        }
        return response.json
    }

    companion object {
        const val PAGE_SIZE = 100
        const val TRACK_PAGE_SIZE = 300

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList()
            else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun array(json: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull {
            json.optJSONArray(it)
        }

        private fun hasMore(json: JSONObject, count: Int, page: Int, size: Int): Boolean {
            listOf("has_more", "hasMore", "more")
                .firstOrNull { json.has(it) }
                ?.let { key ->
                    return json.optBoolean(key, json.optInt(key, 0) == 1)
                }
            val total =
                listOf("total", "total_count", "count").firstNotNullOfOrNull {
                    json.optInt(it, -1).takeIf { n -> n >= 0 }
                }
            return if (total != null) (page + 1) * size < total else count >= size
        }

        private fun <T> collectPages(load: (Int) -> PlatformPage<T>): List<T> {
            val results = ArrayList<T>()
            var page = 0
            while (true) {
                val next = load(page++)
                require(!next.more || next.items.isNotEmpty()) { "平台分页返回空页，请刷新重试" }
                require(next.items.isEmpty() || results.takeLast(next.items.size) != next.items) {
                    "平台分页未继续，请刷新重试"
                }
                results.addAll(next.items)
                if (!next.more) return results
            }
        }
    }
}

/** Decoding keeps platform identifiers out of local file paths and preserves playlist ordering. */
internal object PlatformJson {
    fun kugouErrorCode(json: JSONObject): Int =
        listOf("error_code", "errcode", "err_code").firstNotNullOfOrNull {
            json.optInt(it).takeIf { code -> code != 0 }
        } ?: 0

    fun kugouSuccess(json: JSONObject): Boolean =
        json.optInt("status", 1) == 1 && kugouErrorCode(json) == 0

    fun kugouMessage(json: JSONObject): String? =
        listOf("error", "errmsg", "error_msg", "msg", "message")
            .firstNotNullOfOrNull {
                json.optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
            }
            ?.take(120)

    fun checkKugou(json: JSONObject, action: String) {
        require(kugouSuccess(json)) {
            val code = kugouErrorCode(json)
            val detail = kugouMessage(json)
            when (code) {
                20028 -> "酷狗需要安全验证，请在概念版完成验证后重试"
                else ->
                    if (detail != null) "${action}失败：$detail"
                    else if (code == 0) "${action}暂时不可用，请稍后重试"
                    else "${action}失败（$code），请重试或在平台查看账号状态"
            }
        }
    }

    fun kugouPlaylistObjects(value: Any?): List<JSONObject> =
        when (value) {
            is JSONArray -> (0 until value.length()).flatMap { kugouPlaylistObjects(value.opt(it)) }
            is JSONObject ->
                if (
                    value.has("listid") ||
                        value.has("list_id") ||
                        value.has("list_create_listid") ||
                        ((value.has("global_collection_id") || value.has("global_specialid")) &&
                            (value.has("name") || value.has("listname") || value.has("is_def")))
                )
                    listOf(value)
                else
                    value
                        .keys()
                        .asSequence()
                        .flatMap { kugouPlaylistObjects(value.opt(it)).asSequence() }
                        .toList()
            else -> emptyList()
        }

    fun neteasePlaylist(json: JSONObject, user: String): PlatformPlaylist {
        val owned = json.optJSONObject("creator")?.optString("userId") == user
        return PlatformPlaylist(
            MusicPlatform.NETEASE,
            json.optString("id"),
            json.optString("name", "未命名歌单"),
            json.optInt("trackCount"),
            image(json.optString("coverImgUrl")),
            json.optInt("specialType") == 5 && owned,
            owned,
        )
    }

    fun kugouPlaylist(json: JSONObject, user: String): PlatformPlaylist {
        val name = string(json, "name", "listname", "list_name").trim().ifBlank { "未命名歌单" }
        val globalId =
            string(json, "global_collection_id", "global_specialid", "list_create_gid")
                .takeIf { it != "0" }
                .orEmpty()
        val collection =
            globalId.split('_').takeIf {
                it.size == 5 &&
                    it[0] == "collection" &&
                    it[1] == "3" &&
                    it[2].toLongOrNull() != null &&
                    it[3].toLongOrNull() != null &&
                    it[4].toIntOrNull() != null
            }
        val listId =
            string(json, "listid", "list_id", "list_create_listid").takeIf {
                it.toLongOrNull()?.let { value -> value > 0 } == true
            } ?: collection?.get(3).orEmpty()
        val owner =
            string(json, "userid", "user_id", "list_create_userid")
                .takeUnless { it == "0" }
                .orEmpty()
        val type =
            if (json.has("type")) json.optInt("type") else collection?.get(4)?.toIntOrNull() ?: 0
        val creator = string(json, "list_create_userid").takeUnless { it == "0" }.orEmpty()
        val accountList =
            (owner.isBlank() || owner == user) &&
                (collection == null || collection[2] == user) &&
                (type != 1 || creator.isBlank() || creator == user)
        val favorite =
            accountList &&
                type != 1 &&
                (json.optInt("is_def") == 2 ||
                    name in setOf("我喜欢", "我的喜欢", "我喜欢的音乐", "喜欢的音乐", "喜欢的歌曲"))
        val owned = accountList && (type == 0 || favorite)
        return PlatformPlaylist(
            MusicPlatform.KUGOU,
            globalId.ifBlank { "$type:$listId" },
            name,
            listOf("count", "songcount", "filecount", "song_count", "song_num")
                .firstNotNullOfOrNull {
                    json.optInt(it, -1).takeIf { n -> n >= 0 }
                } ?: 0,
            image(string(json, "pic", "cover", "imgurl")),
            favorite,
            owned,
            listId,
            if (favorite && type == 2) 0 else type,
        )
    }

    fun neteaseTrack(json: JSONObject): PlatformTrack {
        val album = json.optJSONObject("al") ?: json.optJSONObject("album") ?: JSONObject()
        val artists = json.optJSONArray("ar") ?: json.optJSONArray("artists") ?: JSONArray()
        return PlatformTrack(
            MusicPlatform.NETEASE,
            json.optString("id"),
            json.optString("name", "未命名歌曲"),
            (0 until artists.length())
                .mapNotNull { artists.optJSONObject(it)?.optString("name") }
                .joinToString(" / "),
            album.optString("name"),
            image(album.optString("picUrl")),
            json.optLong("dt", json.optLong("duration")),
            album.optString("id", "0"),
            access = PlatformAudioJson.neteaseAccess(json),
            qualities = PlatformAudioJson.neteaseQualities(json),
        )
    }

    fun kugouTrack(json: JSONObject): PlatformTrack? {
        val audio = json.optJSONObject("audio_info") ?: JSONObject()
        val base = json.optJSONObject("base") ?: JSONObject()
        val trans = json.optJSONObject("trans_param") ?: JSONObject()
        val hash =
            string(json, "hash", "filehash", "FileHash", "Hash", "hash_128")
                .ifBlank { string(audio, "hash", "hash_128", "FileHash") }
                .uppercase()
        if (!hash.matches(Regex("[0-9A-F]{32}"))) return null
        val filename =
            string(json, "filename", "name", "FileName", "OriSongName")
                .substringBeforeLast(".mp3")
                .substringBeforeLast(".flac")
        val split = filename.indexOf(" - ")
        val album =
            json.optJSONObject("album_info") ?: json.optJSONObject("albuminfo") ?: JSONObject()
        return PlatformTrack(
            MusicPlatform.KUGOU,
            hash,
            string(json, "songname", "song_name", "SongName", "song")
                .ifBlank { string(base, "songname", "song_name", "name") }
                .ifBlank { string(audio, "audio_name", "songname", "song_name") }
                .ifBlank { if (split >= 0) filename.substring(split + 3) else filename }
                .ifBlank { "未命名歌曲" },
            string(json, "singername", "singer_name", "author_name", "SingerName").ifBlank {
                string(base, "author_name", "singername", "singer_name").ifBlank {
                    if (split >= 0) filename.substring(0, split) else ""
                }
            },
            string(json, "album_name", "albumname", "AlbumName").ifBlank {
                string(album, "album_name", "name")
            },
            image(
                string(
                        json,
                        "cover",
                        "imgurl",
                        "pic",
                        "sizable_cover",
                        "album_sizable_cover",
                        "Image",
                    )
                    .ifBlank { string(album, "cover", "imgurl", "pic", "sizable_cover") }
                    .ifBlank { string(trans, "union_cover", "sizable_cover") }
            ),
            json.optLong(
                "timelen",
                audio.optLong(
                    "timelength",
                    audio.optLong(
                        "duration_128",
                        json.optLong("duration", json.optLong("Duration")) * 1000,
                    ),
                ),
            ),
            string(json, "album_id", "AlbumID")
                .ifBlank { string(album, "album_id", "id") }
                .ifBlank { string(base, "album_id") }
                .ifBlank { "0" },
            string(json, "mixsongid", "album_audio_id", "MixSongID")
                .ifBlank { string(base, "album_audio_id", "mixsongid") }
                .ifBlank { string(audio, "mixsongid", "album_audio_id", "audio_id") }
                .ifBlank { "0" },
            string(json, "fileid", "file_id"),
            PlatformAudioJson.kugouAccess(json),
            PlatformAudioJson.kugouQualities(json),
        )
    }

    fun stream(platform: MusicPlatform, id: String, json: JSONObject): PlatformStream {
        val data = streamData(platform, id, json)
        val url = streamUrl(platform, id, json)
        require(url.isNotBlank() && url != "null") { "这首歌暂无播放权限，请在${platform.label}确认会员或歌曲状态" }
        val rawBitrate = data.optInt("br", data.optInt("bitrate", data.optInt("bitRate")))
        return PlatformStream(
            PlatformAudioTransport.url(platform, url),
            data.optJSONObject("freeTrialInfo") != null ||
                data.optInt("is_free_part") == 1 ||
                json.optInt("is_free_part") == 1,
            AudioQuality.fromPlatform(platform, data.optString("level", data.optString("quality"))),
            if (rawBitrate in 1..10000) rawBitrate * 1000 else rawBitrate,
            data
                .optString("type", data.optString("extName", data.optString("extname")))
                .lowercase(),
            gainDb =
                if (platform == MusicPlatform.NETEASE)
                    data.optDouble("gain", Double.NaN).takeIf { it.isFinite() }
                else null,
        )
    }

    private fun streamData(platform: MusicPlatform, id: String, json: JSONObject): JSONObject {
        val data =
            if (platform == MusicPlatform.NETEASE) {
                val array = json.optJSONArray("data") ?: JSONArray()
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == id } ?: JSONObject()
            } else json.optJSONObject("data") ?: json
        return data
    }

    fun streamUrl(platform: MusicPlatform, id: String, json: JSONObject): String {
        val data = streamData(platform, id, json)
        return when (val value = data.opt("url") ?: data.opt("play_url")) {
                is JSONArray ->
                    (0 until value.length())
                        .map { value.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                is String -> value
                else -> ""
            }
            .takeUnless { it == "null" }
            .orEmpty()
    }

    private fun string(json: JSONObject, vararg keys: String) =
        keys
            .firstNotNullOfOrNull {
                json.optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
            }
            .orEmpty()

    private fun image(value: String) =
        PlatformLoginClient.safeAvatar(value.replace("{size}", "480"))
}
