package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.ArrayList
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 展开式播放页。
 *
 * 从列表里那一行的封面位置弹开; 封面和歌词占同一块矩形, 点一下翻面 —— 和网易云 一样的交互。歌词跟着播放位置走, 当前句放大提亮并居中, 整块平滑上滚。
 */
class PlayerSheet(ctx: Context) : FrameLayout(ctx), Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private var coverFace: View? = null
    private var lyricsFace: View? = null
    private var lyricsView: me.wcy.lrcview.LrcView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var coverLyricView: TextView? = null
    private var previewLines: MutableList<Lrc.Line?>? = null
    private var songMetadata: View? = null
    /** One shared progress control stays in place when the artwork and lyrics switch. */
    private val progressRows = ArrayList<ProgressRow?>()
    private var controlsVersion by mutableIntStateOf(0)
    private var disposed = false
    private var boundSong: Song? = null
    private var boundLyrics = ""
    private var boundPlatformArtwork: ByteArray? = null
    private var boundPlatformTrack: PlatformTrack? = null
    private var coverGeneration = 0L
    private var coverImage: android.widget.ImageView? = null
    private var coverGlyph: TextView? = null
    private var queueHint: TextView? = null
    private var backdrop: android.widget.ImageView? = null
    private var col: LinearLayout? = null
    /** 刚 seek 过去、还没生效的位置 (ms)。见 {@link #refreshProgress()}。 */
    private var pendingSeekMs = -1
    private var pendingSeekAt: Long = 0

    private var flipped by mutableStateOf(false)
    /** 手指正按在进度条上, 这期间别让 ticker 把进度冲掉。 */
    private var dragging = false
    var isOpen = false
        private set

    private val ticker =
        object : Runnable {
            public override fun run() {
                if (!isOpen) return
                refreshProgress()
                refreshLyric()
                handler.postDelayed(this, 250)
            }
        }

    init {
        build()
        Player.get().addListener(this)
    }

    // ------------------------------------------------------------ 构建

    private fun build() {
        setClickable(true)
        setVisibility(View.GONE)
        setAlpha(0f)
        // 底: 没有封面 (或封面还没解出来) 时下面什么都没有, 不铺一层会直接透出下面的页面
        setBackgroundColor(SURFACE)

        // ---- 毛玻璃封面底: 放大 + 模糊 + 压暗, 再罩一层上浅下深的纱
        backdrop = android.widget.ImageView(getContext())
        backdrop!!.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        backdrop!!.setScaleX(1.25f)
        backdrop!!.setScaleY(1.25f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            backdrop!!.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    46f,
                    46f,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            )
        }
        addView(
            backdrop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val dim = View(getContext())
        dim.setBackgroundColor(-0x6c000000)
        addView(
            dim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val veil = View(getContext())
        veil.setBackground(
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(-0x73f5f5f6, -0x24f5f5f6),
            )
        )
        addView(
            veil,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        col = UI.column(getContext())
        addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        col!!.addView(appbar())

        // 这一页是铺满全屏的, Android 15+ 强制 edge-to-edge 之后顶栏会钻到状态栏底下,
        // 收起键和状态栏图标叠在一起。自己补内边距。
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            col!!.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val wide = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val workspace =
            LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
        col!!.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = FrameLayout(context)
        workspace.addView(
            content,
            if (wide) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f),
        )
        coverFace = coverPage()
        content.addView(coverFace, FrameLayout.LayoutParams(-1, -1))
        lyricsFace = lyricsPage()
        lyricsFace!!.visibility = View.INVISIBLE
        content.addView(lyricsFace, FrameLayout.LayoutParams(-1, -1))

        val transport = UI.column(context).apply { gravity = Gravity.BOTTOM }
        workspace.addView(
            transport,
            if (wide)
                LinearLayout.LayoutParams(
                    UI.dp(maxOf(280, resources.configuration.screenWidthDp / 2)),
                    -1,
                )
            else LinearLayout.LayoutParams(-1, -2),
        )
        val details =
            object : ScrollView(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        // Large type can scroll inside the metadata area without pushing transport
                        // offscreen.
                        val limit =
                            UI.dp(
                                minOf(210, (resources.configuration.screenHeightDp * 0.28f).toInt())
                            )
                        val available =
                            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
                                limit
                            else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                        super.onMeasure(
                            widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                        )
                    }
                }
                .apply {
                    isVerticalScrollBarEnabled = false
                    addView(songDetails(), FrameLayout.LayoutParams(-1, -2))
                }
        songMetadata = details
        transport.addView(
            details,
            if (wide) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2),
        )
        transport.addView(progressBlock(28))
        transport.addView(controls())
        queueHint =
            UI.text(context, "", 11.5f, MUTE, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
        transport.addView(
            queueHint,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(UI.dp(24), 0, UI.dp(24), UI.dp(12))
            },
        )
        val density = getResources()!!.getDisplayMetrics()!!.density
        coverFace!!.setCameraDistance(9000 * density)
        lyricsFace!!.setCameraDistance(9000 * density)
    }

    /** MIUIX controls keep the player heading and queue within reach. */
    private fun appbar(): View =
        object : MiuixView(context, true) {
                @Composable
                override fun Render() {
                    val mode = remember(controlsVersion) { Player.get().mode() }
                    val modeLabel =
                        when (mode) {
                            Player.MODE_SHUFFLE -> "随机播放"
                            Player.MODE_ONE -> "单曲循环"
                            else -> "列表循环"
                        }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MiuixIconButton(
                            R.drawable.ic_chevron_down,
                            "收起播放器",
                        ) {
                            close()
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            UiText(
                                if (flipped) "歌词" else Player.get().queueSourceLabel ?: "正在播放",
                                size = 14,
                                maxLines = 1,
                            )
                        }
                        MiuixIconButton(R.drawable.ic_action_more, "播放设置") {
                            Sheet.of(context, "播放设置")
                                .size(SheetSize.COMPACT)
                                .item(modeIcon(mode), "$modeLabel · 切换模式") {
                                    Player.get().cycleMode()
                                    refreshProgress()
                                }
                                .apply {
                                    Player.get().current()?.platformTrack?.let { track ->
                                        item(
                                            R.drawable.ic_quality,
                                            "音质 · ${AccountStore.quality(context, track.platform).label(track.platform)}",
                                        ) {
                                            (context as? MainActivity)?.showAudioQualityOptions(
                                                track.platform,
                                                track,
                                            )
                                        }
                                    }
                                }
                                .show()
                        }
                    }
                }
            }
            .apply {
                layoutParams =
                    LinearLayout.LayoutParams(-1, -2).apply {
                        leftMargin = UI.dp(12)
                        rightMargin = UI.dp(12)
                        topMargin = UI.dp(4)
                        bottomMargin = UI.dp(4)
                    }
            }

    /** Artwork is sized from the available viewport, including split-screen and landscape. */
    private class ArtworkStage(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - UI.dp(48)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec) - UI.dp(24)
            val side = minOf(availableWidth, availableHeight, UI.dp(400)).coerceAtLeast(0)
            getChildAt(0)?.let { child ->
                (child.layoutParams as FrameLayout.LayoutParams).apply {
                    width = side
                    height = side
                    gravity = Gravity.CENTER
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun coverPage(): View {
        val stage = ArtworkStage(context)
        val art =
            FrameLayout(context).apply {
                background = UI.rounded(TRACK_BG, 24f)
                clipToOutline = true
                contentDescription = "专辑封面，点按查看歌词"
                isFocusable = true
                setOnClickListener { flip() }
            }
        coverImage =
            android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.INVISIBLE
            }
        coverGlyph =
            UI.text(context, "♪", 52f, 0x66F2F5FA, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
            }
        art.addView(coverImage, FrameLayout.LayoutParams(-1, -1))
        art.addView(coverGlyph, FrameLayout.LayoutParams(-1, -1))
        stage.addView(art, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        return stage
    }

    private fun songDetails(): View {
        val details = UI.column(context)
        details.setPadding(UI.dp(28), UI.dp(8), UI.dp(28), 0)
        titleView =
            UI.text(context, "", 24f, INK, Typeface.BOLD).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        artistView =
            UI.text(context, "", 13f, BODY, Typeface.NORMAL).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        details.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        details.addView(UI.spacer(context, 6))
        details.addView(artistView, LinearLayout.LayoutParams(-1, -2))
        coverLyricView =
            UI.text(context, "暂无歌词", 15f, ACCENT, Typeface.NORMAL).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = "当前歌词，点按查看完整歌词"
                isFocusable = true
                setOnClickListener { if (isOpen && !flipped) flip() }
            }
        details.addView(
            coverLyricView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UI.dp(10) },
        )
        details.addView(
            metadataActions(),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UI.dp(6) },
        )
        return details
    }

    /** The shared LrcView draws lyric scrolling, its timeline and play marker in one view. */
    private fun lyricsPage(): View {
        val page = FrameLayout(context)
        page.setPadding(UI.dp(16), 0, UI.dp(16), 0)
        lyricsView =
            (android.view.LayoutInflater.from(context).inflate(R.layout.player_lyrics, page, false)
                    as me.wcy.lrcview.LrcView)
                .apply {
                    setNormalColor(MUTE)
                    setCurrentColor(INK)
                    setTimelineTextColor(INK)
                    setTimelineColor(0x66FFFFFF)
                    setTimeTextColor(BODY)
                    setNormalTextSize(18f * resources.displayMetrics.scaledDensity)
                    setCurrentTextSize(22f * resources.displayMetrics.scaledDensity)
                    setLabel("这首歌没有歌词")
                    setSeekOnRelease(true)
                    setDraggable(true) { _, time ->
                        if (!isOpen || Player.get().duration() <= 0) false
                        else {
                            seekTo(time.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            refreshProgress()
                            true
                        }
                    }
                    setOnTapListener { _, _, _ -> if (isOpen && flipped) flip() }
                    contentDescription = "歌词，滑动选择目标句，松手跳转，轻点返回封面"
                }
        page.addView(lyricsView, FrameLayout.LayoutParams(-1, -1))
        return page
    }

    /** The cover and lyrics use the same slider and time labels. */
    private class ProgressRow {
        var fraction by mutableFloatStateOf(0f)
        var available by mutableStateOf(false)
        @JvmField var elapsed: TextView? = null
        @JvmField var total: TextView? = null
    }

    private fun progressBlock(sideDp: Int): View {
        val state = ProgressRow()
        progressRows.add(state)
        val block = UI.column(context)
        block.layoutParams =
            LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = UI.dp(sideDp)
                rightMargin = UI.dp(sideDp)
            }
        block.addView(
            object : MiuixView(context, true) {
                @Composable
                override fun Render() {
                    Slider(
                        value = state.fraction,
                        enabled = state.available,
                        modifier =
                            Modifier.fillMaxWidth().height(48.dp).semantics {
                                contentDescription = "播放进度"
                            },
                        height = 10.dp,
                        onValueChange = { fraction ->
                            val duration = Player.get().duration()
                            if (duration > 0) {
                                dragging = true
                                state.fraction = fraction
                                showTimes(state, (duration * fraction).toInt(), duration)
                            }
                        },
                        onValueChangeFinished = {
                            val duration = Player.get().duration()
                            if (duration > 0) seekTo((duration * state.fraction).toInt())
                            dragging = false
                            refreshProgress()
                        },
                    )
                }
            },
            LinearLayout.LayoutParams(-1, UI.dp(48)),
        )
        val times = UI.row(context)
        state.elapsed = timeLabel("0:00", Gravity.START)
        state.total = timeLabel("0:00", Gravity.END)
        times.addView(state.elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(state.total, LinearLayout.LayoutParams(-2, -2))
        block.addView(times, LinearLayout.LayoutParams(-1, -2))
        return block
    }

    /** 时间 / 体积一类要对齐的数字: 11.5, 等宽, -mute。 */
    private fun timeLabel(value: String?, gravity: Int): TextView? {
        val v = UI.text(getContext(), value, 11.5f, MUTE, Typeface.NORMAL)
        v.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        v.setGravity(gravity)
        return v
    }

    /** Elapsed playback on the left, the song's total duration on the right. */
    private fun showTimes(row: ProgressRow?, pos: Int, dur: Int) {
        row!!.elapsed!!.setText(Lrc.mmss(pos.toLong()))
        row.total!!.setText(Lrc.mmss(dur.coerceAtLeast(0).toLong()))
        row.elapsed!!.contentDescription = "已播放 ${row.elapsed!!.text}"
        row.total!!.contentDescription = "歌曲总时长 ${row.total!!.text}"
    }

    private fun metadataActions(): View =
        object : MiuixView(context, true) {
            @Composable
            override fun Render() {
                val song = remember(controlsVersion) { Player.get().current() } ?: return
                val track = song.platformTrack
                val colors = MiuixTheme.colorScheme
                val accountVersion = AccountStore.revision
                val account =
                    remember(accountVersion, track?.platform) {
                        AccountStore.accounts(context).firstOrNull {
                            it.platform == track?.platform
                        }
                    }
                val libraryVersion = PlatformLibraryStore.revision
                val library =
                    remember(libraryVersion, account) { account?.let(PlatformLibraryStore::get) }
                val liked = track != null && library?.favorites?.containsKey(track.key) == true
                LaunchedEffect(account?.userId, track?.platform) {
                    val profile = account ?: return@LaunchedEffect
                    if (PlatformLibraryStore.get(profile) != null) return@LaunchedEffect
                    val result =
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val auth =
                                    AccountStore.authorization(context, profile.platform)
                                        ?: return@runCatching null
                                if (auth.profile.userId != profile.userId) return@runCatching null
                                PlatformLibraryClient(auth.profile, auth.session).loadLibrary()
                            }
                                .getOrNull()
                        } ?: return@LaunchedEffect
                    if (
                        PlatformLibraryStore.get(profile) == null &&
                            AccountStore.accounts(context).any {
                                it.platform == profile.platform && it.userId == profile.userId
                            }
                    ) {
                        PlatformLibraryStore.publish(profile, result)
                    }
                }
                var comments by
                    remember(track?.key) {
                        mutableStateOf(track?.let(DiscoveryStore::commentCount))
                    }
                LaunchedEffect(track?.key) {
                    val target = track ?: return@LaunchedEffect
                    comments =
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                PlatformDiscoveryClient.forPlatform(
                                        context.applicationContext,
                                        target.platform,
                                    )
                                    .commentCount(target)
                            }
                                .getOrNull()
                        }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SourceBadge(
                            track?.platform,
                            track?.access ?: TrackAccess.UNKNOWN,
                        )
                        if (track != null)
                            UiText(
                                "音质偏好 · ${AccountStore.quality(context, track.platform).label(track.platform)}",
                                size = 12,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1,
                            )
                    }
                    if (track != null) {
                        val pending = favoritePending(track)
                        if (pending) {
                            Box(
                                Modifier.size(44.dp).semantics { contentDescription = "正在更新喜欢" },
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            }
                        } else
                            MiuixIconButton(
                                if (liked) R.drawable.ic_favorite
                                else R.drawable.ic_favorite_outline,
                                if (liked) "取消喜欢" else "喜欢这首歌",
                                diameter = 44.dp,
                                enabled = !pending,
                                iconColor = if (liked) colors.primary else colors.onSurface,
                            ) {
                                (context as? MainActivity)?.togglePlatformFavorite(
                                    track,
                                    desired = !liked,
                                )
                            }
                        MiuixIconButton(R.drawable.ic_quality, "调整音质", diameter = 44.dp) {
                            (context as? MainActivity)?.showAudioQualityOptions(
                                track.platform,
                                track,
                            )
                        }
                        MusicCommentsButton(comments) {
                            (context as? MainActivity)?.openComments(track)
                        }
                    }
                }
            }
        }

    /** Mode and queue belong beside transport; the heading only changes the view. */
    private fun controls(): View =
        object : MiuixView(context, true) {
                @Composable
                override fun Render() {
                    val playing = remember(controlsVersion) { Player.get().isPlaying }
                    val mode = remember(controlsVersion) { Player.get().mode() }
                    val modeLabel =
                        when (mode) {
                            Player.MODE_SHUFFLE -> "随机播放"
                            Player.MODE_ONE -> "单曲循环"
                            else -> "列表循环"
                        }
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        MiuixIconButton(modeIcon(mode), "$modeLabel，切换播放模式", diameter = 44.dp) {
                            Player.get().cycleMode()
                            controlsVersion++
                        }
                        MiuixIconButton(R.drawable.ic_media_prev, "上一首", diameter = 44.dp) {
                            Player.get().prev()
                        }
                        MiuixIconButton(
                            if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                            if (playing) "暂停" else "播放",
                            accent = true,
                            diameter = 68.dp,
                        ) {
                            Player.get().toggle()
                        }
                        MiuixIconButton(R.drawable.ic_media_next, "下一首", diameter = 44.dp) {
                            Player.get().next()
                        }
                        MiuixIconButton(R.drawable.ic_queue, "播放列表", diameter = 44.dp) {
                            showQueue()
                        }
                    }
                }
            }
            .apply {
                layoutParams =
                    LinearLayout.LayoutParams(-1, UI.dp(88)).apply {
                        leftMargin = UI.dp(12)
                        rightMargin = UI.dp(12)
                        bottomMargin = UI.dp(8)
                    }
            }

    /** The same independent sheet is also used by heart radio. */
    fun showQueue() {
        (context as? MainActivity)?.let { act -> PlaybackQueue.show(act) { close() } }
    }

    fun closeQueueIfOpen(): Boolean = PlaybackQueue.dismissVisible()

    // ------------------------------------------------------------ 翻面

    /** Only playback notices need another line; the queue already has its own control. */
    private fun updateQueueHint() {
        val hint = queueHint ?: return
        val message =
            Player.get().lastError
                ?: if (Player.get().isLoading) "正在加载音乐…" else Player.get().playbackNotice.orEmpty()
        hint.text = message
        hint.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    /** 翻面状态复位到封面页 (不动画)。 */
    private fun showCoverFace() {

        flipped = false
        songMetadata?.visibility = View.VISIBLE
        coverFace!!.setRotationY(0f)
        lyricsFace!!.setRotationY(0f)
        coverFace!!.animate().cancel()
        lyricsFace!!.animate().cancel()
        coverFace!!.alpha = 1f
        lyricsFace!!.alpha = 0f
        coverFace!!.translationY = 0f
        lyricsFace!!.translationY = 0f
        coverFace!!.setVisibility(View.VISIBLE)
        lyricsFace!!.setVisibility(View.INVISIBLE)
    }

    private fun flip() {

        val previous = if (flipped) lyricsFace!! else coverFace!!
        flipped = !flipped
        songMetadata?.visibility = if (flipped) View.GONE else View.VISIBLE
        if (flipped) {
            refreshLyric()
        }
        val next = if (flipped) lyricsFace!! else coverFace!!
        previous.animate().cancel()
        next.animate().cancel()
        next.visibility = View.VISIBLE
        next.rotationY = 0f
        if (!PageMotion.enabled(context)) {
            next.alpha = 1f
            next.translationY = 0f
            previous.alpha = 0f
            previous.translationY = 0f
            previous.visibility = View.INVISIBLE
            return
        }
        val direction = if (flipped) 1 else -1
        next.alpha = 0f
        next.translationY = UI.dp(8).toFloat() * direction
        next
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(PageMotion.easing)
            .start()
        previous
            .animate()
            .alpha(0f)
            .translationY(-UI.dp(6).toFloat() * direction)
            .setDuration(180)
            .setInterpolator(PageMotion.easing)
            .withEndAction {
                if (previous !== (if (flipped) lyricsFace else coverFace))
                    previous.visibility = View.INVISIBLE
            }
            .start()
    }

    // ------------------------------------------------------------ 打开/关闭

    fun openFrom(origin: View?, screenLoc: IntArray?) {
        val s = Player.get().current()
        if (s == null) return

        animate().cancel()
        bindSong(s)
        // 每次都从封面页开始 —— 上一次翻到歌词的状态不该留到下一次
        showCoverFace()
        setVisibility(View.VISIBLE)
        setAlpha(0f)
        isOpen = true

        // 这一刻本视图还是 GONE, 没被测量过, getWidth()/getLocationOnScreen() 都不可信。
        // 父容器是常驻的, 用它的尺寸和屏幕位置做基准。
        val parent = getParent() as View?
        val pw = if (parent != null && parent.getWidth() > 0) parent.getWidth() else getWidth()
        val ph = if (parent != null && parent.getHeight() > 0) parent.getHeight() else getHeight()
        val base = IntArray(2)
        if (parent != null) parent.getLocationOnScreen(base)
        // screenLoc 可能是 null(药丸点击处没算坐标就直接开)——那就现算 origin 的屏幕位置,
        // 拿不到 origin 就退化成「原地淡入」(tx=ty=0, scale=1), 绝不能拿 null[0] 崩掉。
        var from = screenLoc
        if (from == null && origin != null) {
            from = IntArray(2)
            origin.getLocationOnScreen(from)
        }
        val sx = if (origin != null) origin.getWidth() / Math.max(1, pw).toFloat() else 1f
        val sy = if (origin != null) origin.getHeight() / Math.max(1, ph).toFloat() else 1f
        val tx = if (from != null) (from[0] - base[0]).toFloat() else 0f
        val ty = if (from != null) (from[1] - base[1]).toFloat() else 0f

        setPivotX(0f)
        setPivotY(0f)
        setTranslationX(tx)
        setTranslationY(ty)
        setScaleX(sx)
        setScaleY(sy)

        animate()!!
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(440)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
            .start()

        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Restore the retained player fully opaque before its child page is removed. */
    fun restoreFromRoute() {
        val s = Player.get().current() ?: return
        PageMotion.reset(this)
        bindSong(s)
        scaleX = 1f
        scaleY = 1f
        visibility = View.VISIBLE
        isOpen = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun close(animated: Boolean = true) {
        animate().cancel()
        isOpen = false
        dragging = false
        handler.removeCallbacks(ticker)
        PlaybackQueue.dismissNow()
        if (getContext() is MainActivity) {
            (getContext() as MainActivity).onPlayerClosed()
        }
        if (!animated) {
            alpha = 0f
            visibility = View.GONE
            return
        }
        animate()!!
            .alpha(0f)
            .setDuration(200)
            .withEndAction(
                object : Runnable {
                    public override fun run() {
                        setVisibility(View.GONE)
                    }
                }
            )
            .start()
    }

    // ------------------------------------------------------------ 数据绑定

    private fun bindSong(s: Song?) {
        val songChanged = s?.key != boundSong?.key
        val lyricsChanged = songChanged || s?.lyrics.orEmpty() != boundLyrics
        if (songChanged) pendingSeekMs = -1
        val coverChanged =
            songChanged ||
                s?.platformArtwork !== boundPlatformArtwork ||
                s?.platformTrack?.cover != boundPlatformTrack?.cover
        boundSong = s
        boundLyrics = s?.lyrics.orEmpty()
        if (lyricsChanged) previewLines = Lrc.parse(boundLyrics)
        boundPlatformArtwork = s?.platformArtwork
        boundPlatformTrack = s?.platformTrack
        if (coverChanged) showCover(s)
        titleView!!.text = s?.title ?: ""
        artistView!!.text =
            if (s == null) "让下一首遇见你"
            else if (s.album.isNotBlank()) "${s.artist} · ${s.album}" else s.artist
        updateQueueHint()
        // Update the shared slider immediately on a track change.
        refreshProgress()

        if (!lyricsChanged) {
            refreshLyric()
            return
        }

        val text = Lrc.splitTranslation(s?.lyrics)
        lyricsView?.loadLrc(text.first, text.second.takeIf { it.isNotBlank() })
        refreshLyric()
    }

    /** 从文件标签里把封面读出来显示。读不到就退回 ♪ 占位符。 */
    private fun showCover(s: Song?) {
        val generation = ++coverGeneration
        if (s == null) {
            applyCover(null)
            return
        }
        applyCover(SongArtwork.cached(s, 720) ?: SongArtwork.cached(s, 160))
        SongArtwork.load(context, s, 720) { bitmap ->
            if (
                !disposed &&
                    generation == coverGeneration &&
                    boundSong?.key == s.key &&
                    bitmap != null
            )
                applyCover(bitmap)
        }
    }

    /** Artwork and its backdrop update together; missing art uses the music glyph. */
    private fun applyCover(bmp: android.graphics.Bitmap?) {
        val has = bmp != null
        coverImage!!.setImageBitmap(bmp)
        backdrop!!.setImageBitmap(bmp)
        backdrop!!.visibility = if (has && Theme.artworkBackground()) View.VISIBLE else View.GONE
        coverImage!!.setVisibility(if (has) View.VISIBLE else View.INVISIBLE)
        coverGlyph!!.setVisibility(if (has) View.GONE else View.VISIBLE)
    }

    /** 跳转并记住目标位置, 让 UI 立刻跟上 (真正的 seek 是异步的)。 */
    private fun seekTo(ms: Int) {
        pendingSeekMs = ms
        pendingSeekAt = System.currentTimeMillis()
        Player.get().seekTo(ms)
    }

    /** 只画进度条和时间, 不动左边那个已播数字 —— 拖动时它由手指控制。 */
    private fun paintProgress(row: ProgressRow?, f: Float) {
        row!!.fraction = f.coerceIn(0f, 1f)
    }

    private fun refreshProgress() {
        val dur = Player.get().duration()
        var pos = Player.get().position()

        // MediaPlayer.seekTo 是异步的: 生效之前 getCurrentPosition() 还是旧值, 而 ticker
        // 每 400ms 就按旧值把进度条重画一遍 —— 松手后条子弹回原处, 看着就是"点了没反应"。
        // 这里先用目标位置顶住, 等真实位置追上来 (或超时) 再交还给播放器。
        if (pendingSeekMs >= 0) {
            if (
                (Math.abs(pos - pendingSeekMs) < 800 ||
                    System.currentTimeMillis() - pendingSeekAt > 2000)
            ) {
                pendingSeekMs = -1
            } else {
                pos = pendingSeekMs
            }
        }

        for (row in progressRows) row!!.available = dur > 0
        if (!dragging) {
            val f = if (dur > 0) Math.min(1f, pos / dur.toFloat()) else 0f
            for (i in progressRows.indices) {
                showTimes(progressRows.get(i), pos, dur)
                paintProgress(progressRows.get(i), f)
            }
        }
        controlsVersion++
    }

    private fun refreshLyric() {
        val position = (if (pendingSeekMs >= 0) pendingSeekMs else Player.get().position()).toLong()
        lyricsView?.updateTime(position)
        val text =
            Lrc.currentText(previewLines, position)
                ?: if (previewLines.isNullOrEmpty()) "暂无歌词" else "前奏"
        if (coverLyricView?.text?.toString() != text) coverLyricView?.text = text
    }

    // ------------------------------------------------------------ Player 回调

    public override fun onPlayerChanged() {
        if (disposed || !isOpen) return
        post(
            object : Runnable {
                public override fun run() {
                    val s = Player.get().current()
                    if (
                        s !== boundSong ||
                            s?.lyrics.orEmpty() != boundLyrics ||
                            s?.platformArtwork !== boundPlatformArtwork ||
                            s?.platformTrack !== boundPlatformTrack
                    ) {
                        bindSong(s)
                    }
                    updateQueueHint()
                    refreshProgress()
                    refreshLyric()
                }
            }
        )
    }

    fun dispose() {
        disposed = true
        isOpen = false
        handler.removeCallbacksAndMessages(null)
        animate().cancel()
        PlaybackQueue.dismissNow()
        Player.get().removeListener(this)
    }

    companion object {

        /**
         * 播放页固定用毛玻璃暗色底, 不跟随控制台主题。
         *
         * <p>浅底压不住封面, 白字也读不出来 —— 这一页的对比度是「封面 + 一层暗纱」 决定的, 和 Palette 的亮/暗无关, 所以底和文字这几档直接写死。
         *
         * <p>V5 (稿 ⑥⑦) 换成冷石墨一档: 稿里 {@code .ph.darkSurface} 那组 canvas —— {@code #080A0E} 底,
         * 文字冷白/冷灰。强调色例外, 那两档跟主题走, 稿里 darkSurface 也没覆盖它们 (见 {@link #ACCENT} 与 {@link #ON_ACCENT})。
         */
        private val PLAYER_PALETTE
            get() = Palette.themed(Theme.palette(), true)

        private val INK
            get() = PLAYER_PALETTE.ink

        /** 控件图标 / 次级文字 (稿里的 -body)。 */
        private val BODY
            get() = PLAYER_PALETTE.body

        /** 艺人、时间、说明 (稿里的 -mute)。 */
        private val MUTE
            get() = PLAYER_PALETTE.mute

        /** 无封面时的底 (稿里 darkSurface 的 -soft2)。 */
        private val TRACK_BG
            get() = PLAYER_PALETTE.canvasRaised

        /** 发丝线 (稿里 darkSurface 的 -hair, 白 7%)。 */
        private val HAIR
            get() = PLAYER_PALETTE.hairline

        /** 把手 / 需要稍亮一档的线 (稿里 -hair2, 白 14%)。 */
        private val HAIR2
            get() = PLAYER_PALETTE.hairlineStrong

        /** 玻璃面的描边 (稿里 darkSurface 的 --glassLine, 白 16%) —— 用在顶栏那两个小圆 chip 上。 */
        private val LINE
            get() = PLAYER_PALETTE.hairlineStrong

        /** 进度轨 / 槽位的底色 (稿里的 -track, 白 10%)。压在玻璃上, 不能用不透明色。 */
        /** 歌词页那块纯色底 (稿里的 -canvas)。 */
        private val SURFACE
            get() = PLAYER_PALETTE.canvas

        /**
         * 播放页专属的强调色档 (#7FB4FF) —— 进度条渐变的末端、三条柱、正在播那行的标题。
         *
         * <p>不写死字面量, 走 {@link UI#ACCENT_SOFT}; 也不用 {@link UI#accentInk()} —— 那个跟主题, 亮色下是深蓝,
         * 压在常暗的播放页上读不出来。
         */
        private val ACCENT
            get() = PLAYER_PALETTE.accentInk

        /** 播放键 64 圆那个填充上的图标色 (稿里的 -onAccent)。填充用 {@link UI#accent()}。 */
        private val ON_ACCENT
            get() = PLAYER_PALETTE.onAccent

        /** 播放模式图标: 列表循环 / 随机 / 单曲。 */
        private fun modeIcon(mode: Int): Int {
            return if (mode == Player.MODE_ONE) R.drawable.ic_mode_one
            else
                (if (mode == Player.MODE_SHUFFLE) R.drawable.ic_mode_shuffle
                else R.drawable.ic_mode_repeat)
        }
    }
}
