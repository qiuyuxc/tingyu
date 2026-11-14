package com.qiuyuxc.tingyu

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentDialog

internal enum class SheetSize(val fraction: Float) {
    COMPACT(0.30f),
    MEDIUM(0.60f),
    EXPANDED(0.80f),
}

/**
 * The phone console's bottom sheet — the web {@code SheetPanel} in native form.
 *
 * Everything the tab bar cannot hold lives behind one of these: the "+" create actions and the "更多"
 * page list. It is a dialog rather than a view in the activity because it has to dim the page, take
 * the back button, and sit above whatever the current fragment is drawing — including a WebView,
 * which no amount of z-ordering inside the fragment would cover.
 */
internal object Sheet {

    /** The sheet on screen, if any — the shell's back handler needs to see it. */
    private var visible: Dialog? = null

    /** Closes the open sheet. Returns false when there was nothing to close. */
    @JvmStatic
    fun dismissVisible(): Boolean {
        if (visible == null || !visible!!.isShowing()) return false
        visible!!.dismiss()
        return true
    }

    @JvmStatic
    fun of(ctx: Context, title: String?): Builder {
        return Builder(ctx, title)
    }

    internal class Builder constructor(private val ctx: Context, title: String?) {
        private val dialog: Dialog?
        private val body: LinearLayout?
        private val scroll: ScrollView?
        private val root: DraggableSheetLayout
        private var requestedSize: SheetSize? = null
        private var entries = 0
        /** Rows added since the last {@link #label}; drives the hairline seams. */
        private var rowsInGroup: Int = 0

        /** 关闭时要停掉, 否则动画下一帧还会去碰已经 detach 的窗口。 */
        private var dimAnim: ValueAnimator? = null
        private var onDismiss: (() -> Unit)? = null

        init {
            val p = Theme.p()

            root = DraggableSheetLayout(ctx)
            root.setBackground(topRounded(p.canvasRaised))
            root.clipToOutline = true
            // Without this the dim scrim's tap would fall through to the page.
            root.setClickable(true)

            root.addView(buildHandle())
            root.addView(buildHead(title))

            body = UI.column(ctx)
            val side = UI.dp(UI.MD.toFloat())
            body.setPadding(side, 0, side, UI.dp(20f))

            scroll = ScrollView(ctx)
            scroll.setClipToPadding(false)
            scroll.setVerticalScrollBarEnabled(false)
            scroll.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )

            dialog =
                ComponentDialog(
                    ctx,
                    if (Theme.isDark) R.style.SheetDialog_Dark else R.style.SheetDialog,
                )
            dialog.setContentView(root)
            val window = dialog.getWindow()
            if (window != null) {
                window.setGravity(Gravity.BOTTOM)
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                // A floating dialog window still carries the platform's own
                // inset background; the sheet paints its own.
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
                applyInsets(window, root)
            }
            dialog.setCanceledOnTouchOutside(true)
            root.onDragStarted = {
                dimAnim?.cancel()
                dimAnim = null
            }
            root.onDragFractionChanged = { fraction ->
                if (dialog.isShowing && dialog.window?.decorView?.isAttachedToWindow == true)
                    dialog.window?.setDimAmount(0.45f * (1f - fraction))
            }
            root.onDismissRequest = {
                root.animateDismiss {
                    dialog.window?.setWindowAnimations(0)
                    dismiss()
                }
            }
        }

        private fun buildHandle(): View? {
            val p = Theme.p()
            val wrap = UI.row(ctx)
            wrap.setGravity(Gravity.CENTER)
            wrap.setPadding(0, UI.dp(10f), 0, UI.dp(10f))
            wrap.minimumHeight = UI.dp(28f)
            wrap.contentDescription = "向下拖动关闭面板"
            val handle = View(ctx)
            handle.setBackground(UI.rounded(p.hairlineStrong, UI.RADIUS_PILL.toFloat()))
            wrap.addView(handle, LinearLayout.LayoutParams(UI.dp(36f), UI.dp(4f)))
            return wrap
        }

        private fun buildHead(title: String?): View? {
            val p = Theme.p()
            val head = UI.row(ctx)
            head.setPadding(
                UI.dp(UI.LG.toFloat()),
                UI.dp(UI.MD.toFloat()),
                UI.dp(UI.LG.toFloat()),
                UI.dp(10f),
            )
            head.addView(
                UI.text(ctx, title, 20f, p.ink, Typeface.BOLD),
                LinearLayout.LayoutParams(0, -2, 1f),
            )

            val close = ImageView(ctx)
            close.setImageResource(R.drawable.ic_nav_close)
            close.setColorFilter(p.mute)
            val pad = UI.dp(12f)
            close.setPadding(pad, pad, pad, pad)
            close.setBackground(
                UI.pressable(UI.rounded(p.canvasSoft2, UI.RADIUS_PILL.toFloat()), p.btnGhostHover)
            )
            close.setClickable(true)
            close.setOnClickListener({ v -> dismiss() })
            close.contentDescription = "关闭"
            head.addView(close, LinearLayout.LayoutParams(UI.dp(44f), UI.dp(44f)))
            return head
        }

        /** Keeps the last row clear of the gesture bar when the window is flush. */
        private fun applyInsets(window: Window?, root: View?) {
            window!!
                .getDecorView()
                .setOnApplyWindowInsetsListener({ v, insets ->
                    val bottom = insets.getSystemWindowInsetBottom()
                    if (bottom > 0) {
                        root!!.setPadding(0, 0, 0, bottom)
                    }
                    insets
                })
        }

        // ------------------------------------------------------------- content

        fun size(value: SheetSize): Builder {
            requestedSize = value
            return this
        }

        /** Section heading, matching the web sheet's uppercase group label. */
        fun label(text: String?): Builder {
            val wrap = UI.column(ctx)
            wrap.setPadding(UI.dp(10f), UI.dp(14f), UI.dp(10f), UI.dp(5f))
            wrap.addView(UI.label(ctx, text))
            body!!.addView(wrap)
            rowsInGroup = 0
            return this
        }

        /**
         * A create action: icon tile, title, description, chevron. The web {@code .action-row},
         * used by the "+" sheet.
         */
        fun action(iconRes: Int, title: String?, desc: String?, onClick: Runnable?): Builder {
            val row =
                MiuixPreferenceView(ctx, title.orEmpty(), desc, icon = iconRes) {
                    dismiss()
                    onClick?.run()
                }
            body!!.addView(row, LinearLayout.LayoutParams(-1, -2))
            rowsInGroup++
            entries++
            return this
        }

        /** A page row inside a group, separated by space. */
        fun item(iconRes: Int, title: String?, onClick: Runnable?): Builder {
            if (rowsInGroup > 0) {
                body!!.addView(
                    UI.spacer(ctx, 4),
                    LinearLayout.LayoutParams(-1, UI.dp(4)),
                )
            }
            val row =
                MiuixPreferenceView(ctx, title.orEmpty(), icon = iconRes) {
                    dismiss()
                    onClick?.run()
                }
            body!!.addView(row, LinearLayout.LayoutParams(-1, -2))
            rowsInGroup++
            entries++
            return this
        }

        /** Arbitrary content, for sheets that are not a list of links. */
        fun content(view: View?): Builder {
            body!!.addView(view)
            rowsInGroup++
            entries++
            return this
        }

        /** A list with its own scrolling gets the remaining, bounded height below the header. */
        fun fillContent(view: View): Builder {
            check(entries == 0) { "Fill content must be the sheet's only body" }
            root.removeView(scroll)
            root.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            entries++
            return this
        }

        fun onDismiss(action: () -> Unit): Builder {
            onDismiss = action
            return this
        }

        // ------------------------------------------------------------ lifecycle

        fun dismiss() {
            if (dimAnim != null) {
                dimAnim!!.cancel()
                dimAnim = null
            }
            dialog!!.dismiss()
        }

        fun show() {
            dismissVisible()
            visible = dialog
            dialog!!.setOnDismissListener {
                dimAnim?.cancel()
                dimAnim = null
                if (visible === dialog) visible = null
                onDismiss?.invoke()
            }
            dialog.show()
            root.resetDrag()
            val size = requestedSize ?: if (entries <= 3) SheetSize.COMPACT else SheetSize.MEDIUM
            val viewport =
                (ctx as? android.app.Activity)?.window?.decorView?.height?.takeIf { it > 0 }
                    ?: ctx.resources.displayMetrics.heightPixels
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (viewport * size.fraction).toInt(),
            )
            root.layoutParams =
                (root.layoutParams ?: ViewGroup.LayoutParams(-1, -1)).apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            fadeDim(0f, 0.45f)
        }

        /**
         * The dialog's own window animation can only move the sheet; the scrim has to be animated
         * by hand or it snaps in ahead of the panel.
         */
        private fun fadeDim(from: Float, to: Float) {
            val window = dialog!!.getWindow()
            if (window == null) return
            val anim = ValueAnimator.ofFloat(from, to)
            anim!!.setDuration(220)
            anim.addUpdateListener(
                object : ValueAnimator.AnimatorUpdateListener {
                    public override fun onAnimationUpdate(a: ValueAnimator) {
                        // 弹层可能在这一帧之前就被关掉了。窗口一旦 detach, setDimAmount 会抛
                        // "View not attached to window manager" —— 实测确实崩过。
                        if (dialog.isShowing() && window.getDecorView().isAttachedToWindow()) {
                            window.setDimAmount(a.getAnimatedValue() as Float)
                        }
                    }
                }
            )
            dimAnim = anim
            anim.start()
        }
    }

    /** Tops the corners only; the bottom edge is the screen edge. */
    private fun topRounded(fill: Int): GradientDrawable? {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        val r = UI.dp(28f).toFloat()
        d.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
        d.setColor(fill)
        return d
    }
}
