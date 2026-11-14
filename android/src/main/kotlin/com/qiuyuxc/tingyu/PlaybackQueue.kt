package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** The cover player and heart radio open exactly the same playback list. */
internal object PlaybackQueue {
    private var visible: ComponentDialog? = null
    private var content: PlaybackQueueView? = null

    fun show(act: MainActivity, onClear: () -> Unit = {}) {
        if (Player.get().queue().isNullOrEmpty()) return
        dismissNow()
        val dialog = ComponentDialog(act, R.style.SheetDialog_Dark)
        val view = PlaybackQueueView(act, onClear)
        visible = dialog
        content = view
        view.onDismissRequest = { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.onBackPressedDispatcher.addCallback(
            dialog,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    view.closeQueueIfOpen()
                }
            },
        )
        dialog.setOnDismissListener {
            view.dispose()
            if (visible === dialog) {
                visible = null
                content = null
            }
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        ViewCompat.requestApplyInsets(view)
        view.showQueue()
    }

    fun dismissVisible(): Boolean = content?.closeQueueIfOpen() == true

    fun dismissNow() {
        visible?.dismiss()
    }
}

/** Original player row styling, artwork reuse and drag ordering, independent of its page. */
private class PlaybackQueueView(ctx: Context, private val onQueueCleared: () -> Unit) :
    FrameLayout(ctx), Player.Listener {
    var onDismissRequest: () -> Unit = {}
    private var disposed = false
    private var dragging = false

    private var queueOverlay: FrameLayout? = null
    private var queueSheet: DraggableSheetLayout? = null
    private var queueScrim: View? = null
    private var queueScroll: ScrollView? = null
    private var queueList: LinearLayout? = null
    private var queueCount: TextView? = null
    /** Visual rows keep the real playback order, including songs preceding the current one. */
    private val queueOrder = ArrayList<Int>()
    /** 手势条高度 —— 面板贴着屏幕底, 得自己让开。 */
    private var bottomInset: Int = 0
    private var rebuildPosted: Boolean = false

    private data class QueueRowBinding(
        val index: Int,
        val songKey: String,
        val artwork: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
    )

    private var queueBinding = emptyList<QueueRowBinding>()

    private class QueueRowViews(
        val songKey: String,
        val row: LinearLayout,
        val title: TextView,
        val artist: TextView,
        val image: android.widget.ImageView,
        val glyph: View,
        val equalizer: View,
        val handle: View,
    ) {
        var index = -1
        var position = -1
        var artwork: String? = null
        var playing = false
    }

    private var queueRows = emptyList<QueueRowViews>()

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            bottomInset = bars.bottom
            queueSheet?.setPadding(bars.left, UI.dp(10), bars.right, sheetBottomPad())
            insets
        }
        Player.get().addListener(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && h != oldh) {
            queueSheet?.let { sheet ->
                sheet.layoutParams =
                    sheet.layoutParams.apply { height = (h * SheetSize.MEDIUM.fraction).toInt() }
            }
        }
    }

    fun showQueue() {
        if (Player.get().queue()!!.isEmpty()) {
            android.widget.Toast.makeText(
                    getContext(),
                    "队列是空的",
                    android.widget.Toast.LENGTH_SHORT,
                )!!
                .show()
            return
        }
        if (queueOverlay == null) buildQueueSheet()
        queueSheet!!.layoutParams =
            queueSheet!!.layoutParams.apply {
                val viewport =
                    this@PlaybackQueueView.height.takeIf { it > 0 }
                        ?: resources.displayMetrics.heightPixels
                height = (viewport * SheetSize.MEDIUM.fraction).toInt()
            }
        fillQueueRows()
        queueSheet!!.resetDrag()
        queueOverlay!!.animate().cancel()
        queueSheet!!.animate().cancel()
        queueOverlay!!.setVisibility(View.VISIBLE)
        queueOverlay!!.setAlpha(0f)
        queueSheet!!.setTranslationY(queueSheet!!.layoutParams.height.toFloat())
        queueOverlay!!.animate()!!.alpha(1f).setDuration(160).start()
        queueSheet!!
            .animate()!!
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
        queueScroll?.post {
            if (queueOverlay?.visibility == View.VISIBLE) {
                queueRows
                    .firstOrNull { it.index == Player.get().queueIndex() }
                    ?.let {
                        queueScroll?.scrollTo(0, (it.row.top - UI.dp(8)).coerceAtLeast(0))
                    }
            }
        }
    }

    /** 返回键: 面板开着就先收面板。收掉了返回 true。 */
    fun closeQueueIfOpen(): Boolean {
        if (queueOverlay == null || queueOverlay!!.getVisibility() != View.VISIBLE) return false
        hideQueue()
        return true
    }

    private fun hideQueue() {
        val overlay = queueOverlay ?: return
        val sheet = queueSheet ?: return
        sheet.animate().cancel()
        overlay.animate().cancel()
        sheet.animateDismiss {
            overlay.visibility = View.GONE
            sheet.resetDrag()
            onDismissRequest()
        }
    }

    private fun buildQueueSheet() {
        val ctx = getContext()
        queueOverlay = FrameLayout(ctx)
        queueOverlay!!.setClickable(true)
        queueOverlay!!.setVisibility(View.GONE)
        addView(
            queueOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val scrim = View(ctx)
        queueScrim = scrim
        scrim.setBackgroundColor(-0x67000000)
        scrim.setOnClickListener(
            object : View.OnClickListener {
                public override fun onClick(v: View?) {
                    hideQueue()
                }
            }
        )
        queueOverlay!!.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val sheet = DraggableSheetLayout(ctx)
        // 比普通玻璃实一档: 队列面板盖住半个屏幕, 太透会看穿到下面的歌词/封面 (用户 2026-09-20)
        sheet.setBackground(UI.darkSurfaceTop(30f, UI.DARK_SHEET_FILL))
        sheet.setClickable(true)
        sheet.setPadding(0, UI.dp(10f), 0, sheetBottomPad())
        val slp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * SheetSize.MEDIUM.fraction).toInt(),
            )
        slp.gravity = Gravity.BOTTOM
        // 底边贴着屏幕底: 玻璃只圆上面两个角 (UI.darkSurfaceTop), 下面两个平角,
        // 所以不需要把面板顶出屏幕那一套。
        queueOverlay!!.addView(sheet, slp)
        queueSheet = sheet
        sheet.onDragStarted = {
            sheet.animate().cancel()
            queueOverlay?.animate()?.cancel()
            queueOverlay?.alpha = 1f
        }
        sheet.onDragFractionChanged = { fraction -> queueScrim?.alpha = 1f - fraction }
        sheet.onDismissRequest = { hideQueue() }

        val grabArea =
            UI.row(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, UI.dp(8), 0, UI.dp(12))
                minimumHeight = UI.dp(32)
                contentDescription = "向下拖动关闭播放列表"
            }
        val grab = View(ctx)
        grab.setBackground(UI.rounded(HAIR2, UI.RADIUS_PILL.toFloat()))
        grabArea.addView(grab, LinearLayout.LayoutParams(UI.dp(36f), UI.dp(4f)))
        sheet.addView(grabArea, LinearLayout.LayoutParams(-1, -2))

        val head = UI.row(ctx)
        head.setPadding(UI.dp(20f), 0, UI.dp(20f), UI.dp(4f))
        head.addView(UI.text(ctx, "播放列表", 16f, INK, Typeface.BOLD))
        head.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        queueCount = UI.text(ctx, "", 12.5f, ACCENT, Typeface.BOLD)
        queueCount!!.setPadding(UI.dp(10f), UI.dp(6f), UI.dp(10f), UI.dp(6f))
        queueCount!!.setBackground(
            UI.pressable(UI.rounded(0x00000000, UI.RADIUS_PILL.toFloat()), 0x33FFFFFF)
        )
        queueCount!!.setOnClickListener(
            object : View.OnClickListener {
                public override fun onClick(v: View?) {
                    // 清空 = 停播 + 收起播放页: 队列空了, 这一页也没什么可看的了
                    Player.get().clear()
                    hideQueue()
                    onQueueCleared()
                }
            }
        )
        head.addView(queueCount)
        val close =
            android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_nav_close)
                setColorFilter(MUTE)
                setPadding(UI.dp(12), UI.dp(12), UI.dp(12), UI.dp(12))
                contentDescription = "关闭播放列表"
                setOnClickListener { hideQueue() }
            }
        head.addView(close, LinearLayout.LayoutParams(UI.dp(44), UI.dp(44)))
        sheet.addView(
            head,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        queueList = UI.column(ctx)
        val scroll = ScrollView(ctx)
        queueScroll = scroll
        scroll.setVerticalScrollBarEnabled(false)
        scroll.setClipToPadding(false)
        scroll.addView(
            queueList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        sheet.addView(
            scroll,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    /** Keep the actual playback order and only update the current song's highlight. */
    private fun fillQueueRows() {
        if (queueList == null) return
        val q = Player.get().queue()
        val now = Player.get().queueIndex()
        val hasCurrent = now >= 0 && now < q!!.size
        queueOrder.clear()
        queueOrder.addAll(q!!.indices.toList())

        val binding = queueOrder.map { queueIndex ->
            val index = requireNotNull(queueIndex)
            val song = requireNotNull(q[index])
            QueueRowBinding(
                index,
                song.key,
                SongArtwork.key(song, UI.dp(46f)),
                song.title,
                song.artist,
                hasCurrent && index == now,
            )
        }
        queueCount!!.text = "${q.size} 首 · 清空"
        if (binding == queueBinding) return
        queueBinding = binding

        // Reorder the existing views so switching tracks never discards their displayed covers.
        // Grouping also preserves duplicate occurrences of a song in a user-created queue.
        val available = queueRows.groupBy { it.songKey }.mapValues { it.value.toMutableList() }
        queueRows = binding.mapIndexed { position, item ->
            val song = requireNotNull(q[item.index])
            val views =
                available[item.songKey]?.let { if (it.isEmpty()) null else it.removeAt(0) }
                    ?: queueRow(song)
            if (
                (views.position > 0) != (position > 0) ||
                    views.position < 0 ||
                    views.playing != item.playing
            ) {
                views.row.background = UI.pressable(RowBg(position > 0, item.playing), 0x1FFFFFFF)
            }
            views.playing = item.playing
            views.index = item.index
            views.position = position
            views.title.text = item.title
            views.title.setTextColor(if (item.playing) ACCENT else INK)
            views.title.setTypeface(null, if (item.playing) Typeface.BOLD else Typeface.NORMAL)
            views.artist.text = if (item.playing) "正在播放 · ${item.artist}" else item.artist
            views.equalizer.visibility = if (item.playing) View.VISIBLE else View.GONE
            views.handle.visibility = View.VISIBLE
            if (views.artwork != item.artwork) {
                views.artwork = item.artwork
                loadQueueCover(song, views.image, views.glyph)
            }
            if (queueList!!.getChildAt(position) !== views.row) {
                (views.row.parent as? android.view.ViewGroup)?.removeView(views.row)
                queueList!!.addView(views.row, position)
            }
            views
        }
        while (queueList!!.childCount > queueRows.size) {
            queueList!!.removeViewAt(queueList!!.childCount - 1)
        }
    }

    private fun queueRow(song: Song): QueueRowViews {
        val ctx = getContext()

        val row = UI.row(ctx)
        row.setPadding(UI.dp(18f), UI.dp(9f), UI.dp(18f), UI.dp(9f))

        val art = FrameLayout(ctx)
        art.setBackground(UI.rounded(TRACK_BG, 12f))
        art.setClipToOutline(true)
        row.addView(art, LinearLayout.LayoutParams(UI.dp(46f), UI.dp(46f)))

        val glyph = UI.text(ctx, "♪", 16f, 0x55F2F5FA, Typeface.NORMAL)
        glyph.setGravity(Gravity.CENTER)
        art.addView(
            glyph,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val image = android.widget.ImageView(ctx)
        image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
        art.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val tx = UI.column(ctx)
        val txlp = LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f)
        txlp.leftMargin = UI.dp(13f)
        row.addView(tx, txlp)

        val name = UI.text(ctx, song.title, 14f, INK, Typeface.NORMAL)
        name.setSingleLine(true)
        name.setEllipsize(TextUtils.TruncateAt.END)
        tx.addView(
            name,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val by =
            UI.text(
                ctx,
                song.artist,
                12f,
                MUTE,
                Typeface.NORMAL,
            )
        by.setSingleLine(true)
        by.setEllipsize(TextUtils.TruncateAt.END)
        val bylp =
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        bylp.topMargin = UI.dp(2f)
        tx.addView(by, bylp)
        tx.addView(
            songSourceLine(ctx, song, dark = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UI.dp(4) },
        )

        val eq = UI.Equalizer(ctx, ACCENT)
        val elp = LinearLayout.LayoutParams(UI.dp(15f), UI.dp(13f))
        elp.leftMargin = UI.dp(10f)
        row.addView(eq, elp)
        val handle = android.widget.ImageView(ctx)
        handle.setImageResource(R.drawable.ic_drag)
        handle.contentDescription = "拖动调整${song.title}的顺序"
        handle.setColorFilter(MUTE)
        handle.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE)
        handle.setPadding(UI.dp(8f), UI.dp(8f), UI.dp(8f), UI.dp(8f))
        row.addView(handle, LinearLayout.LayoutParams(UI.dp(34f), UI.dp(34f)))
        val views = QueueRowViews(song.key, row, name, by, image, glyph, eq, handle)
        row.setOnClickListener {
            Player.get().jumpTo(views.index)
            rebuildQueueSoon()
        }
        handle.setOnTouchListener(dragListener(row) { views.position })
        return views
    }

    /** Dragging any row changes the real playback order without interrupting the current song. */
    private fun dragListener(row: View?, visualPosition: () -> Int): View.OnTouchListener? {
        return object : View.OnTouchListener {
            /** 手指按在行内的高度 —— 松手时用它反推行被拖到了哪儿。 */
            private var grabOffset: Float = 0.toFloat()
            private var rowTop = 0f

            public override fun onTouch(v: View?, e: MotionEvent?): Boolean {
                when (e!!.getActionMasked()) {
                    MotionEvent.ACTION_DOWN -> {
                        grabOffset = e.getRawY() - screenTop(row)
                        rowTop = screenTop(row).toFloat()
                        dragging = true
                        row!!.setAlpha(0.86f)
                        // 不抢过来的话, 外面的 ScrollView 一超过 touch slop 就把手势收走,
                        // 拖到一半变成滚列表 (松开时只当没拖过)。
                        v!!.getParent()!!.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        row!!.setTranslationY(e.getRawY() - grabOffset - rowTop)
                        return true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        v!!.getParent()!!.requestDisallowInterceptTouchEvent(false)
                        // 按落点找目标位置, 而不是「位移 ÷ 行高」—— 行高会随字号、内边距变,
                        // 除出来的数一旦偏一档, 排序就跳到别处去。
                        val target = visualAt(e.getRawY() - grabOffset + row!!.getHeight() / 2f)
                        row.setTranslationY(0f)
                        row.setAlpha(1f)
                        dragging = false
                        val visualPos = visualPosition()
                        if (e.actionMasked == MotionEvent.ACTION_UP && target != visualPos)
                            moveInQueue(visualPos, target)
                        rebuildQueueSoon()
                        return true
                    }
                    else -> return false
                }
            }
        }
    }

    /**
     * 这一行 (中心落在 rawCenterY) 该排在第几个。
     *
     * <p>比的是行的**下沿**而不是中心: 用中心的话, 往上拖正好一行会停在两格中间, 差十几像素就不换位 —— 手感上就是「拖了没反应」。
     */
    private fun visualAt(rawCenterY: Float): Int {
        if (queueList == null || queueList!!.getChildCount() == 0) return 0
        val base = IntArray(2)
        queueList!!.getLocationOnScreen(base)
        val count = queueList!!.getChildCount()
        for (i in 0 until count) {
            val child = queueList!!.getChildAt(i)
            if (rawCenterY < base[1] + child!!.getBottom()) return i
        }
        return count - 1
    }

    /** 视觉位置换算回队列下标, 再交给 {@link Player#move}。 */
    private fun moveInQueue(fromVisual: Int, toVisual: Int) {
        val from = queueOrder.getOrNull(fromVisual) ?: return
        val to = queueOrder.getOrNull(toVisual) ?: return
        if (from == to) return
        Player.get().move(from, to)
        rebuildQueueSoon()
    }

    /**
     * 下一帧再重排队列行。
     *
     * <p>拖着的那一行就是马上要拆掉的那一行, 当场 {@code removeAllViews} 会把带着 位移/透明度的它一起收走 —— 这台 ROM 上紧接着的 view
     * attribute 收集会拿到空 child 直接 NPE (ViewGroup.dispatchCollectViewAttributes)。挪到下一帧就没事了。
     */
    private fun rebuildQueueSoon() {
        if (rebuildPosted) return
        rebuildPosted = true
        post(
            object : Runnable {
                public override fun run() {
                    rebuildPosted = false
                    if (!disposed && queueOverlay?.visibility == View.VISIBLE) fillQueueRows()
                }
            }
        )
    }

    /** Reuse the shared artwork cache for visible queue rows. */
    private fun loadQueueCover(song: Song?, image: android.widget.ImageView?, glyph: View?) {
        val target = requireNotNull(song)
        val px = UI.dp(46f)
        val key = SongArtwork.key(target, px)
        image!!.setTag(key)
        SongArtwork.load(context, target, px) { bitmap ->
            if (disposed || key != image.tag) return@load
            image.setImageBitmap(bitmap)
            glyph!!.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        }
    }

    override fun onPlayerChanged() {
        if (disposed || queueOverlay?.visibility != View.VISIBLE) return
        if (Player.get().queue().isNullOrEmpty()) hideQueue() else if (!dragging) rebuildQueueSoon()
    }

    fun dispose() {
        disposed = true
        queueOverlay?.animate()?.cancel()
        queueSheet?.animate()?.cancel()
        queueSheet?.resetDrag()
        Player.get().removeListener(this)
    }

    private fun sheetBottomPad(): Int = UI.dp(18) + bottomInset

    /** A transparent queue row with an optional top separator. */
    private class RowBg
    internal constructor(private val hairline: Boolean, private val playing: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        public override fun draw(c: Canvas) {
            val b = getBounds()
            if (playing) {
                paint.color = (ACCENT and 0x00FFFFFF) or 0x1A000000
                c.drawRect(b, paint)
            }
            if (hairline) {
                paint.setColor(HAIR)
                c.drawRect(
                    b.left.toFloat(),
                    b.top.toFloat(),
                    b.right.toFloat(),
                    (b.top + Math.max(1, UI.dp(1f))).toFloat(),
                    paint,
                )
            }
        }

        public override fun setAlpha(alpha: Int) {}

        public override fun setColorFilter(cf: android.graphics.ColorFilter?) {}

        public override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    companion object {
        private val palette
            get() = Palette.themed(Theme.palette(), true)

        private val INK
            get() = palette.ink

        private val MUTE
            get() = palette.mute

        private val TRACK_BG
            get() = palette.canvasRaised

        private val HAIR
            get() = palette.hairline

        private val HAIR2
            get() = palette.hairlineStrong

        private val ACCENT
            get() = palette.accentInk

        private fun screenTop(view: View?): Int {
            val location = IntArray(2)
            requireNotNull(view).getLocationOnScreen(location)
            return location[1]
        }
    }
}
