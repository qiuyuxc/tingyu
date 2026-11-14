package com.qiuyuxc.tingyu

/** A playback queue can continue reading its playlist or finding new recommendations. */
internal interface PlatformTrackSource {
    val id: String
    val label: String
    val platform: MusicPlatform
    val endless: Boolean
        get() = false

    fun next(excluded: Set<String>): PlatformPage<PlatformTrack>

    fun commit(result: PlatformPage<PlatformTrack>, excluded: Set<String>) {}
}

internal class PagedTrackSource(
    override val id: String,
    override val label: String,
    override val platform: MusicPlatform,
    @Volatile private var page: Int,
    private val read: (Int) -> PlatformPage<PlatformTrack>,
) : PlatformTrackSource {
    override fun next(excluded: Set<String>): PlatformPage<PlatformTrack> {
        val result = read(page)
        require(result.items.isNotEmpty() || !result.more) { "平台没有返回下一页歌曲，请重试" }
        val items = result.items.filter { it.key !in excluded }
        require(items.isNotEmpty() || !result.more) { "平台分页没有继续，请重试" }
        return result.copy(items = items)
    }

    override fun commit(result: PlatformPage<PlatformTrack>, excluded: Set<String>) {
        page++
    }
}

internal class RecommendationTrackSource(
    override val platform: MusicPlatform,
    private val heart: Boolean = false,
    private val client: () -> PlatformDiscoveryClient,
) : PlatformTrackSource {
    override val id = "${if (heart) "heart" else "recommendations"}:${platform.id}"
    override val label = if (heart) "心动 · 随心听" else "推荐播放"
    override val endless = true
    @Volatile private var page = if (heart) 1 else 0
    private val heard = LinkedHashSet<String>()

    override fun next(excluded: Set<String>): PlatformPage<PlatformTrack> {
        val blocked = synchronized(heard) { heard.toSet() + excluded }
        val reader = client()
        val songs =
            (if (heart) reader.radioSongs(page, blocked) else reader.recommendations(page, blocked))
                .distinctBy { it.key }
                .filter { it.key !in blocked }
        require(songs.isNotEmpty()) { "暂时没有获取到新的推荐歌曲，请重试" }
        return PlatformPage(songs, true)
    }

    override fun commit(result: PlatformPage<PlatformTrack>, excluded: Set<String>) {
        page++
        synchronized(heard) {
            heard.addAll(excluded)
            heard.addAll(result.items.map { it.key })
            while (heard.size > 600) heard.remove(heard.first())
        }
    }
}

/** A cancelled request cannot consume a page or append into a replacement queue. */
internal class PlaybackContinuation {
    data class Request(val generation: Long, val source: PlatformTrackSource)

    var source: PlatformTrackSource? = null
        private set

    var more = false
        private set

    var generation = 0L
        private set

    fun reset(next: PlatformTrackSource? = null) {
        generation++
        source = next
        more = next != null
    }

    fun invalidate() {
        generation++
    }

    fun request(): Request? = source?.takeIf { more }?.let { Request(generation, it) }

    fun accept(request: Request, hasMore: Boolean): Boolean {
        if (request.generation != generation || request.source !== source) return false
        more = hasMore
        return true
    }
}
