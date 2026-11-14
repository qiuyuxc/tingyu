package com.qiuyuxc.tingyu

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.ArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One playback queue for local files and authorized platform streams. */
class Player private constructor() {

    private val mp = PlaybackAudio()
    private val playbackGain = PlaybackGain()
    private val listening = ListeningSession()
    private var listeningSong: Song? = null
    private var historyRecorded = false
    private val historyHandler = Handler(Looper.getMainLooper())
    private val historyTick =
        object : Runnable {
            override fun run() {
                sampleListening()
                if (isPlaying || isLoading) historyHandler.postDelayed(this, 1000)
            }
        }
    private var mode = MODE_LIST
    internal var pauseRevision = 0L
        private set

    @Volatile private var autoplayPending = false
    /** 药丸被 ✕ 收起了。队列保留, 详情页还能接着放。 */
    var isDismissed = false
        private set

    private val random = java.util.Random()
    private val queue = ArrayList<Song?>()
    private val listeners = ArrayList<Listener?>()
    private var index = -1
    private var prepared = false
    private val platformScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var platformJob: Job? = null
    private var metadataJob: Job? = null
    private var continuationJob: Job? = null
    private val continuationState = PlaybackContinuation()
    private val continuation
        get() = continuationState.source

    private val continuationGeneration
        get() = continuationState.generation

    private val continuationMore
        get() = continuationState.more

    private var continuationError: String? = null
    private var waitingForNext = false
    internal val queueSourceLabel: String?
        get() = continuation?.label

    internal val queueSourceId: String?
        get() = continuation?.id

    private var loadGeneration = 0L
    private var queueRequest = 0L
    private var resumePosition: Int? = null
    private var resumeAfterSeek = false
    private var trialStream = false
    var isLoading = false
        private set

    var lastError: String? = null
        private set

    var playbackNotice: String? = null
        private set

    private var pendingQualitySuggestion: QualitySuggestion? = null
    private var qualitySuggestion: QualitySuggestion? = null
    private val offeredQualitySuggestions = mutableSetOf<String>()

    internal fun takeQualitySuggestion(): QualitySuggestion? {
        val suggestion = qualitySuggestion ?: return null
        qualitySuggestion = null
        val key =
            "${suggestion.platform.id}:${suggestion.userId}:${suggestion.requested.id}:${suggestion.suggested.id}"
        return suggestion.takeIf { offeredQualitySuggestions.add(key) }
    }

    val isPlaying: Boolean
        get() {
            try {
                return prepared && mp.isPlaying
            } catch (e: IllegalStateException) {
                return false
            }
        }

    interface Listener {
        fun onPlayerChanged()
    }

    init {
        // Seeking is asynchronous; publish the settled position so system lyrics seek with it.
        mp.listener =
            object : PlaybackAudio.Listener {
                override fun onSeekComplete() {
                    if (resumeAfterSeek && autoplayPending && prepared) mp.start()
                    resumeAfterSeek = false
                    notifyChanged()
                }

                override fun onCompletion() {
                    advance(true)
                }

                override fun onPrepared() {
                    prepared = true
                    isLoading = false
                    applyVolume()
                    qualitySuggestion = pendingQualitySuggestion
                    pendingQualitySuggestion = null
                    val position = resumePosition?.let {
                        if (trialStream && it >= mp.duration) 0 else it
                    }
                    resumePosition = null
                    if (position != null && position > 0) {
                        resumeAfterSeek = autoplayPending
                        mp.seekTo(position.coerceAtMost((mp.duration - 1).coerceAtLeast(0)))
                    } else if (autoplayPending) mp.start()
                    notifyChanged()
                }

                override fun onError(what: Int, extra: Int) {
                    val failure = mp.failure
                    prepared = false
                    isLoading = false
                    lastError = failure?.message ?: "音频播放失败（$what / $extra），请重试"
                    MainActivity.diagLine(
                        "播放出错: what=$what extra=$extra; ${failure?.diagnostic.orEmpty()}"
                    )
                    notifyChanged()
                }
            }
    }

    fun addListener(l: Listener?) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener?) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        sampleListening()
        historyHandler.removeCallbacks(historyTick)
        if (isPlaying || isLoading) historyHandler.postDelayed(historyTick, 1000)
        for (i in listeners.indices.reversed()) {
            listeners.get(i)!!.onPlayerChanged()
        }
    }

    private fun sampleListening() {
        val song = listeningSong ?: return
        val playing = isPlaying && current()?.key == song.key
        val counted = listening.sample(playing, SystemClock.elapsedRealtime(), duration().toLong())
        if (playing && !historyRecorded) {
            historyRecorded = true
            recordHistory(song.key)
            ListeningHistory.record(song, touch = true)
        }
        if (counted) ListeningHistory.record(song, count = true)
    }

    fun current(): Song? {
        return if (index >= 0 && index < queue.size) queue.get(index) else null
    }

    fun position(): Int {
        try {
            return if (prepared) mp.currentPosition else resumePosition ?: 0
        } catch (e: IllegalStateException) {
            return 0
        }
    }

    fun duration(): Int {
        try {
            return if (prepared) mp.duration else current()?.durationSec?.times(1000) ?: 0
        } catch (e: IllegalStateException) {
            return 0
        }
    }

    /** 用一份新列表替换播放队列, 并从 start 开始放。 */
    fun play(songs: MutableList<Song?>?, start: Int) {
        resetContinuation()
        queueRequest++
        isDismissed = false
        val want = if (start >= 0 && start < songs!!.size) songs.get(start) else null

        // 点的就是正在放的那首, 而且已经准备好了 —— 接着放, 别从头再来。
        // 从列表里点回同一首歌, 预期是「把播放页叫出来 / 继续听」, 不是重播。
        if (prepared && isSame(current(), want)) {
            if (want?.isPlatform == true) {
                want.lyrics = current()?.lyrics.orEmpty()
                want.platformArtwork = current()?.platformArtwork
                want.platformTrack = current()?.platformTrack ?: want.platformTrack
            }
            queue.clear()
            queue.addAll(songs!!)
            index = start
            try {
                if (!mp.isPlaying) mp.start()
            } catch (ignored: IllegalStateException) {}

            notifyChanged()
            return
        }

        queue.clear()
        queue.addAll(songs!!)
        index = start
        load(true)
        notifyChanged()
    }

    fun toggle() {
        if (isPlaying) {
            pause()
        } else {
            play()
        }
    }

    internal fun playPlatform(
        tracks: List<PlatformTrack>,
        start: Int,
        source: PlatformTrackSource? = null,
    ) {
        play(tracks.map { Song(it) as Song? }.toMutableList(), start)
        continuationState.reset(source)
        fillUpcoming()
    }

    private fun resetContinuation() {
        continuationJob?.cancel()
        continuationJob = null
        continuationState.reset()
        continuationError = null
        waitingForNext = false
    }

    /** Fill ahead of the current song so playback also continues while the page is closed. */
    private fun fillUpcoming(force: Boolean = false) {
        val request = continuationState.request() ?: return
        val source = request.source
        if (!continuationMore || continuationJob?.isActive == true || index < 0) return
        if (!force && (continuationError != null || queue.size - index - 1 >= 5)) return
        val generation = continuationGeneration
        continuationError = null
        continuationJob = platformScope.launch {
            try {
                var batches = 0
                do {
                    val excluded = queue.mapNotNull { it?.key }.toSet()
                    val result = withContext(Dispatchers.IO) { source.next(excluded) }
                    ensureActive()
                    if (!continuationState.accept(request, result.more)) return@launch
                    val additions =
                        result.items.distinctBy { it.key }.filter { it.key !in excluded }
                    require(additions.isNotEmpty() || !result.more) { "平台没有继续返回新歌曲，请重试" }
                    queue.addAll(additions.map { Song(it) })
                    source.commit(result, excluded)
                    if (source.endless && index > 80) {
                        val remove = index - 40
                        repeat(remove) { queue.removeAt(0) }
                        index -= remove
                    }
                    if (waitingForNext && (additions.isNotEmpty() || !continuationMore)) {
                        val autoplay = autoplayPending
                        waitingForNext = false
                        index = if (index + 1 < queue.size) index + 1 else 0
                        load(autoplay)
                    }
                    notifyChanged()
                    batches++
                } while (continuationMore && queue.size - index - 1 < 5 && batches < 4)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == continuationGeneration) {
                    continuationError = error.message ?: "下一批歌曲获取失败"
                    if (waitingForNext) {
                        isLoading = false
                        lastError = "$continuationError，点按播放或下一首重试"
                    }
                    notifyChanged()
                }
            }
        }
    }

    internal fun reserveQueueRequest(): Long = ++queueRequest

    internal fun isQueueRequestCurrent(request: Long): Boolean = queueRequest == request

    internal fun reloadQuality() {
        if (current()?.isPlatform != true) return
        val position = position()
        val autoplay = isPlaying || isLoading && autoplayPending
        load(autoplay, position)
        notifyChanged()
    }

    /**
     * 确保在播。
     *
     * 没准备好的话要重新加载 —— stop() 之后 prepared 是 false 但队列还在, 这时按播放键应该接着放, 而不是毫无反应。 锁屏/岛上的播放键走的就是这里。
     */
    fun play() {
        isDismissed = false
        if (waitingForNext) {
            autoplayPending = true
            isLoading = true
            lastError = null
            fillUpcoming(force = true)
            notifyChanged()
            return
        }
        if (!prepared) {
            if (isLoading) autoplayPending = true else if (current() != null) load(true)
            notifyChanged()
            return
        }
        try {
            if (!mp.isPlaying) mp.start()
        } catch (ignored: IllegalStateException) {}

        notifyChanged()
    }

    /** 确保暂停。耳机线控、锁屏、岛上按的都是这里。 */
    fun pause() {
        pauseRevision++
        autoplayPending = false
        if (!prepared) {
            notifyChanged()
            return
        }
        try {
            if (mp.isPlaying) mp.pause()
        } catch (ignored: IllegalStateException) {}

        notifyChanged()
    }

    /** 导航播报之类抢焦点时压低音量, 不用暂停。 */
    fun duck(on: Boolean) {
        playbackGain.setDucked(on)
        applyVolume()
    }

    private fun applyVolume() {
        try {
            val v = playbackGain.volume
            mp.setVolume(v)
        } catch (ignored: IllegalStateException) {}
    }

    fun mode(): Int {
        return mode
    }

    /** 当前队列的快照。播放页的队列面板用它, 所以给副本而不是内部那个 List。 */
    fun queue(): MutableList<Song?>? {
        return ArrayList<Song?>(queue)
    }

    /** 播到队列里的第几首。空队列返回 -1。 */
    fun queueIndex(): Int {
        return index
    }

    /** 直接跳到队列里的第 i 首。 */
    fun jumpTo(i: Int) {
        if (i < 0 || i >= queue.size || i == index) return
        index = i
        load(true)
        notifyChanged()
    }

    /** 队列面板里拖动排序: 把第 from 首挪到 to。正在播的那首跟着位置走, 不打断。 */
    fun move(from: Int, to: Int) {
        if (from == to || from < 0 || from >= queue.size || to < 0 || to >= queue.size) return
        queue.add(to, queue.removeAt(from))
        if (index == from) {
            index = to
        } else if (from < index && to >= index) {
            index--
        } else if (from > index && to <= index) {
            index++
        }
        notifyChanged()
    }

    /** 清空队列并停播 (队列面板的「清空」)。 */
    fun clear() {
        stop()
        resetContinuation()
        queue.clear()
        index = -1
        notifyChanged()
    }

    /** 列表循环 → 随机 → 单曲, 转一圈。 */
    fun cycleMode() {
        mode = (mode + 1) % 3
        if (mode == MODE_SHUFFLE && !queue.isEmpty()) {
            shuffleRemaining()
        }
        notifyChanged()
    }

    internal fun setMode(value: Int) {
        require(value in MODE_LIST..MODE_ONE)
        if (mode == value) return
        mode = value
        if (mode == MODE_SHUFFLE) shuffleRemaining()
        notifyChanged()
    }

    /** 随机的顺序: 从当前位置之后开始打乱, 已经放过的留在前面。 */
    private fun shuffleRemaining() {
        if (queue.size < 2) return
        val rest = java.util.ArrayList<Song?>(queue.subList(index + 1, queue.size))
        java.util.Collections.shuffle(rest, random)
        for (i in rest.indices) {
            queue.set(index + 1 + i, rest.get(i))
        }
    }

    /** 放完一首之后去哪。fromCompletion 为 true 表示是自然播完。 */
    private fun advance(fromCompletion: Boolean) {
        if (queue.isEmpty()) return
        if (mode == MODE_ONE && fromCompletion) {
            seekTo(0)
            load(true)
            notifyChanged()
            return
        }
        if (index + 1 >= queue.size && continuation != null && continuationMore) {
            waitingForNext = true
            autoplayPending = true
            isLoading = true
            lastError = null
            fillUpcoming(force = true)
            notifyChanged()
            return
        }
        if (mode == MODE_SHUFFLE) {
            if (index + 1 >= queue.size) shuffleRemaining()
            index = if (index + 1 >= queue.size) 0 else index + 1
        } else {
            index = (index + 1) % queue.size
        }
        load(true)
        notifyChanged()
    }

    fun next() {
        advance(false)
    }

    fun prev() {
        if (queue.isEmpty()) return
        index = (index - 1 + queue.size) % queue.size
        load(true)
        notifyChanged()
    }

    fun seekTo(ms: Int) {
        if (!prepared) return
        try {
            mp.seekTo(ms)
        } catch (ignored: IllegalStateException) {}

        notifyChanged()
    }

    private fun load(autoplay: Boolean, resumeAt: Int? = null) {
        sampleListening()
        waitingForNext = false
        queueRequest++
        resumePosition = resumeAt
        resumeAfterSeek = false
        trialStream = false
        val s = current()
        if (resumeAt == null || listeningSong?.key != s?.key) {
            listening.reset()
            historyRecorded = false
        }
        listeningSong = s
        queue.forEach { if (it !== s) it?.platformArtwork = null }
        platformJob?.cancel()
        metadataJob?.cancel()
        val generation = ++loadGeneration
        prepared = false
        isLoading = false
        lastError = null
        playbackNotice = null
        qualitySuggestion = null
        pendingQualitySuggestion = null
        playbackGain.setTrackGain(null)
        autoplayPending = autoplay
        fillUpcoming()
        try {
            mp.reset()
            applyVolume()
            if (s == null) return
            // 有歌要放了 —— 把前台服务 + 媒体会话拉起来, 否则锁屏和岛上看不到
            if (appCtx != null) {
                PlaybackService.ensure(appCtx)
            }
            if (s.isPlatform) {
                val track = requireNotNull(s.platformTrack)
                val context = requireNotNull(appCtx)
                isLoading = true
                platformJob = platformScope.launch {
                    try {
                        val result =
                            withContext(Dispatchers.IO) {
                                val authorization =
                                    AccountStore.authorization(context, track.platform)
                                        ?: error("请先连接${track.platform.label}或重新扫码")
                                ensureActive()
                                val client =
                                    PlatformLibraryClient(
                                        authorization.profile,
                                        authorization.session,
                                    )
                                val detail =
                                    runCatching {
                                        PlatformDiscoveryClient.forPlatform(context, track.platform)
                                            .details(listOf(track))
                                            .firstOrNull()
                                    }
                                        .getOrNull() ?: track
                                Triple(
                                    client,
                                    client.stream(
                                        detail,
                                        AudioQuality.resolve(
                                            detail,
                                            AccountStore.quality(context, track.platform),
                                        ),
                                        AccountStore.membership(context, authorization.profile),
                                    ),
                                    detail,
                                )
                            }
                        ensureActive()
                        if (generation != loadGeneration || current() !== s) return@launch
                        val stream = result.second
                        playbackGain.setTrackGain(stream.gainDb)
                        applyVolume()
                        result.first.resolvedMembership?.let {
                            AccountStore.updateMembership(context, result.first.profile, it)
                        }
                        pendingQualitySuggestion =
                            stream.defaultSuggestion?.let {
                                QualitySuggestion(
                                    s.key,
                                    track.platform,
                                    result.first.profile.userId,
                                    AccountStore.quality(context, track.platform),
                                    it,
                                )
                            }
                        s.platformTrack = result.third
                        trialStream = stream.trial
                        val audio =
                            listOfNotNull(
                                    stream.quality?.label(track.platform),
                                    stream.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                                    stream.format.takeIf { it.isNotBlank() }?.uppercase(),
                                )
                                .joinToString(" · ")
                        playbackNotice =
                            listOfNotNull(
                                    stream.notice,
                                    if (stream.trial) "试听片段" else null,
                                    if (audio.isBlank())
                                        "音质偏好：${AccountStore.quality(context, track.platform).label(track.platform)}"
                                    else "实际音质：$audio",
                                )
                                .joinToString(" · ")
                        val headers = PlatformAudioTransport.headers(track.platform)
                        mp.setDataSource(context, Uri.parse(result.second.url), headers)
                        mp.prepareAsync()
                        metadataJob = platformScope.launch {
                            launch {
                                val lyrics =
                                    withContext(Dispatchers.IO) {
                                        runCatching { result.first.lyrics(track) }.getOrDefault("")
                                    }
                                ensureActive()
                                if (generation == loadGeneration && current()?.key == s.key) {
                                    s.lyrics = lyrics
                                    notifyChanged()
                                }
                            }
                            launch {
                                val detail = result.third
                                ensureActive()
                                if (generation != loadGeneration || current()?.key != s.key)
                                    return@launch
                                s.platformTrack = detail
                                if (historyRecorded) ListeningHistory.record(s)
                                notifyChanged()
                                val artwork =
                                    withContext(Dispatchers.IO) {
                                        runCatching { PlatformArtwork.bytes(detail.cover) }
                                            .getOrNull()
                                    }
                                ensureActive()
                                if (generation == loadGeneration && current()?.key == s.key) {
                                    s.platformArtwork = artwork
                                    notifyChanged()
                                }
                            }
                        }
                        notifyChanged()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (generation == loadGeneration) {
                            isLoading = false
                            prepared = false
                            lastError =
                                if (error is IOException) "网络连接失败，请稍后重试"
                                else error.message ?: "平台歌曲暂时无法播放"
                            notifyChanged()
                        }
                    }
                }
                return
            } else {
                mp.setDataSource(s.file!!.getAbsolutePath())
                MainActivity.diagLine("播放: 本地文件 " + s.file!!.getName())
            }
            isLoading = true
            mp.prepareAsync() // prepared 由 onPrepared 置位
        } catch (e: Exception) {
            prepared = false
            isLoading = false
            lastError = "音乐文件读取失败，请检查文件或连接"
            MainActivity.diagLine("播放失败: " + e)
        }
    }

    fun stop() {
        sampleListening()
        listeningSong = null
        listening.reset()
        historyRecorded = false
        historyHandler.removeCallbacks(historyTick)
        continuationJob?.cancel()
        continuationJob = null
        continuationState.invalidate()
        waitingForNext = false
        continuationError = null
        queueRequest++
        resumePosition = null
        resumeAfterSeek = false
        qualitySuggestion = null
        pendingQualitySuggestion = null
        playbackGain.reset()
        platformJob?.cancel()
        metadataJob?.cancel()
        loadGeneration++
        try {
            mp.reset()
        } catch (ignored: IllegalStateException) {}

        prepared = false
        isLoading = false
        autoplayPending = false
        // 只停播 + 收起药丸, 队列和当前位置都留着 —— 详情页还能接着放同一首。
        isDismissed = true
        // 播放真的停了, 通知和会话也该收掉
        if (appCtx != null) PlaybackService.shutdown(appCtx)
        notifyChanged()
    }

    internal fun removePlatform(platform: MusicPlatform) {
        if (continuation?.platform == platform) resetContinuation()
        val song = current()
        if (song?.platformTrack?.platform == platform) stop()
        queue.removeAll { it?.platformTrack?.platform == platform }
        index =
            if (song != null && song in queue) queue.indexOf(song)
            else if (queue.isEmpty()) -1 else 0
        notifyChanged()
    }

    companion object {

        private var instance: Player? = null

        @JvmField val MODE_LIST = 0
        @JvmField val MODE_SHUFFLE = 1
        @JvmField val MODE_ONE = 2

        @JvmStatic
        @Synchronized
        fun get(): Player {
            if (instance == null) instance = Player()
            return instance!!
        }

        /** Identity is a platform key or a local file path. */
        private fun isSame(a: Song?, b: Song?): Boolean {
            if (a == null || b == null) return false
            if (a.isPlatform || b.isPlatform) return a.isPlatform && b.isPlatform && a.key == b.key
            return a.file != null && a.file == b.file
        }

        /** 起播放服务要用。存 Application 级的, 免得拽住 Activity。 */
        private var appCtx: android.content.Context? = null

        // ---------------- 播放历史 (首页「最近播放」用) ----------------

        /** 记录一条播放历史, 最近的在最前, 最多 20 条。 */
        @JvmStatic
        fun recordHistory(path: String?) {
            if (appCtx == null || path == null || path.length == 0) return
            try {
                val sp = appCtx!!.getSharedPreferences("history", 0)
                val old = org.json.JSONArray(sp!!.getString("recent", "[]"))
                val out = org.json.JSONArray()
                out.put(path)
                var i = 0
                while (i < old.length() && out.length() < 20) {
                    if (old.getString(i) != path) out.put(old.getString(i))
                    i++
                }
                sp.edit()!!.putString("recent", out.toString())!!.apply()
            } catch (ignored: Exception) {}
        }

        /** 最近播放的路径, 最近的在最前。 */
        @JvmStatic
        fun historyPaths(): MutableList<String?>? {
            val out = java.util.ArrayList<String?>()
            if (appCtx == null) return out
            try {
                val arr =
                    org.json.JSONArray(
                        appCtx!!.getSharedPreferences("history", 0)!!.getString("recent", "[]")
                    )
                for (i in 0 until arr.length()) out.add(arr.getString(i))
            } catch (ignored: Exception) {}

            return out
        }

        /** Initialize the application context, artwork store and listening history. */
        @JvmStatic
        fun init(ctx: android.content.Context?) {
            appCtx = ctx!!.getApplicationContext()
            ListeningHistory.init(appCtx!!)
            PlatformArtwork.init(requireNotNull(appCtx))
        }

    }
}
