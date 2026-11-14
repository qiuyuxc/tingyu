package com.qiuyuxc.tingyu

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.function.IntConsumer
import kotlin.math.roundToInt
import kotlin.math.sin

/** Layout and typography shared by the retained pages; interactive choices use Compose. */
internal object UI {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
    const val XXL = 32
    const val XXXL = 48
    const val RADIUS_CARD = 24
    const val RADIUS_SM = 8
    const val RADIUS_MD = 16
    const val RADIUS_LG = 16
    const val RADIUS_PILL = 999
    const val BTN_PRIMARY = 0
    const val BTN_SECONDARY = 1
    const val BTN_GHOST = 2
    const val BTN_DANGER = 3
    const val ACCENT_SOFT = 0xFF5DAAFF.toInt()
    const val DARK_SHEET_FILL = 0xFF242424.toInt()
    private val density = Resources.getSystem().displayMetrics.density

    @JvmStatic fun dp(value: Int): Int = dp(value.toFloat())

    @JvmStatic fun dp(value: Float): Int = (value * density).roundToInt()

    @JvmStatic fun accent(): Int = Theme.p().accent

    @JvmStatic fun accentInk(): Int = Theme.p().accentInk

    @JvmStatic fun onAccent(): Int = Theme.p().onAccent

    @JvmStatic
    fun pageInset(context: Context?): Int =
        if (requireNotNull(context).resources.configuration.screenWidthDp <= 360) 16 else 20

    @JvmStatic
    fun topBarHeight(context: Context): Int =
        maxOf(64, kotlin.math.ceil(40 * context.resources.configuration.fontScale + 16).toInt())

    // The activity measures the dock and removes it from the page viewport.
    const val CONTENT_BOTTOM = 24

    @JvmStatic
    fun column(context: Context?) =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    @JvmStatic
    fun row(context: Context?) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    @JvmStatic
    fun spacer(context: Context?, height: Int): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(height)) }

    @JvmStatic
    fun text(context: Context?, value: String?, size: Float, color: Int, style: Int): TextView =
        TextView(context).apply {
            text = value.orEmpty()
            textSize = size
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, style)
            includeFontPadding = false
        }

    @JvmStatic
    fun pageTitle(context: Context?, value: String?) =
        text(context, value, 30f, Theme.p().ink, Typeface.BOLD)

    @JvmStatic
    fun strong(context: Context?, value: String?) =
        text(context, value, 14f, Theme.p().ink, Typeface.BOLD)

    @JvmStatic
    fun body(context: Context?, value: String?) =
        text(context, value, 14f, Theme.p().body, Typeface.NORMAL).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    @JvmStatic
    fun muted(context: Context?, value: String?) =
        text(context, value, 13f, Theme.p().mute, Typeface.NORMAL).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    @JvmStatic
    fun label(context: Context?, value: String?) =
        text(context, value, 12f, Theme.p().mute, Typeface.BOLD)

    @JvmStatic
    @JvmOverloads
    fun mono(context: Context?, value: String?, color: Int = Theme.p().mute) =
        text(context, value, 12f, color, Typeface.NORMAL).apply { typeface = Typeface.MONOSPACE }

    @JvmStatic
    fun rounded(fill: Int, radius: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
        }

    @JvmStatic
    fun roundedStroke(fill: Int, radius: Float, line: Int, width: Float) =
        rounded(fill, radius).apply { setStroke(maxOf(1, dp(width)), line) }

    @JvmStatic
    fun circle(fill: Int, line: Int, width: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (line != 0) setStroke(maxOf(1, dp(width)), line)
        }

    @JvmStatic
    fun pressable(content: Drawable?, ripple: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), content, rounded(-1, 999f))

    @JvmStatic fun surface(radius: Float): Drawable = rounded(Theme.p().canvasRaised, radius)

    @JvmStatic fun tintedSurface(radius: Float, tint: Int): Drawable = rounded(tint, radius)

    @JvmStatic
    @JvmOverloads
    fun applySurface(view: View?, radius: Float, shadow: Boolean = false) {
        view?.background = surface(radius)
        view?.clipToOutline = true
        view?.elevation = 0f
    }

    @JvmStatic fun darkSurface(radius: Float): Drawable = rounded(DARK_SHEET_FILL, radius)

    @JvmStatic
    @JvmOverloads
    fun darkSurfaceTop(radius: Float, fill: Int = DARK_SHEET_FILL): Drawable =
        GradientDrawable().apply {
            setColor(fill)
            val r = dp(radius).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

    @JvmStatic
    fun margin(view: View?, left: Int, top: Int, right: Int, bottom: Int) {
        if (view == null) return
        val original = view.layoutParams
        val layout =
            original as? ViewGroup.MarginLayoutParams
                ?: LinearLayout.LayoutParams(original?.width ?: -2, original?.height ?: -2)
        layout.setMargins(dp(left), dp(top), dp(right), dp(bottom))
        view.layoutParams = layout
    }

    @JvmStatic
    fun weight(view: View?, weight: Float) {
        if (view == null) return
        val layout =
            view.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(0, -2)
        layout.width = 0
        layout.weight = weight
        view.layoutParams = layout
    }

    @JvmStatic
    fun restoreScroll(scroll: ScrollView?, position: Int) {
        if (scroll == null || position <= 0) return
        scroll.post { scroll.scrollTo(0, position) }
        scroll.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (scroll.viewTreeObserver.isAlive)
                        scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, position)
                    return true
                }
            }
        )
    }

    @JvmStatic
    fun button(context: Context?, label: String?, kind: Int): TextView {
        val palette = Theme.p()
        val filled = kind == BTN_PRIMARY || kind == BTN_DANGER
        val color = if (kind == BTN_DANGER) palette.error else palette.accent
        return text(
                context,
                label,
                14f,
                if (filled) palette.onAccent else palette.accentInk,
                Typeface.BOLD,
            )
            .apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background =
                    pressable(
                        rounded(if (filled) color else palette.canvasSoft2, 16f),
                        palette.btnGhostHover,
                    )
                isFocusable = true
            }
    }

    @JvmStatic
    fun input(context: Context?, hint: String?): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(Theme.p().ink)
            setHintTextColor(Theme.p().mute)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(48)
            background = rounded(Theme.p().canvasSoft2, 16f)
            isSingleLine = true
        }

    @JvmStatic
    fun tag(context: Context?, label: String?, ok: Boolean): TextView =
        text(
                context,
                label,
                12f,
                if (ok) Theme.p().statusHealthyText else Theme.p().mute,
                Typeface.NORMAL,
            )
            .apply {
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background =
                    rounded(if (ok) Theme.p().statusHealthyBg else Theme.p().canvasSoft, 999f)
            }

    @JvmStatic
    fun chips(
        context: Context?,
        options: Array<out String?>?,
        selected: Int,
        onChange: IntConsumer?,
    ): View =
        Choices(
            requireNotNull(context),
            options.orEmpty().map { it.orEmpty() },
            selected,
            false,
            onChange,
        )

    @JvmStatic
    fun segmented(
        context: Context?,
        options: Array<out String?>?,
        selected: Int,
        onChange: IntConsumer?,
    ): View =
        Choices(
            requireNotNull(context),
            options.orEmpty().map { it.orEmpty() },
            selected,
            true,
            onChange,
        )

    private class Choices(
        context: Context,
        private val options: List<String>,
        selected: Int,
        private val stretch: Boolean,
        private val onChange: IntConsumer?,
    ) : MiuixView(context) {
        private var current by mutableIntStateOf(selected)

        @Composable
        override fun Render() {
            MiuixChoices(
                options = options,
                selected = current.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
                onSelect = {
                    current = it
                    onChange?.accept(it)
                },
                modifier = if (stretch) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
                minWidth = if (stretch) 80.dp else 56.dp,
            )
        }
    }

    class Toggle(context: Context?, on: Boolean) : MiuixView(requireNotNull(context)) {
        private var checked by mutableStateOf(on)
        private var available by mutableStateOf(true)
        val isOn: Boolean
            get() = checked

        fun setOn(value: Boolean) {
            checked = value
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            available = enabled
        }

        @Composable
        override fun Render() {
            MiuixToggle(checked, contentDescription?.toString().orEmpty(), available) {
                performClick()
            }
        }
    }

    class Equalizer @JvmOverloads constructor(context: Context?, color: Int = accent()) :
        View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val start = System.currentTimeMillis()

        override fun onMeasure(width: Int, height: Int) {
            setMeasuredDimension(resolveSize(dp(16), width), resolveSize(dp(14), height))
        }

        override fun onDraw(canvas: Canvas) {
            val time = (System.currentTimeMillis() - start) / 950.0
            for (index in 0..2) {
                val height =
                    height *
                        (0.35f +
                            0.65f * ((sin(time * 2 * Math.PI + index * 1.15) + 1) / 2).toFloat())
                val x = index * dp(6).toFloat()
                canvas.drawRoundRect(
                    x,
                    this.height - height,
                    x + dp(3),
                    this.height.toFloat(),
                    dp(2).toFloat(),
                    dp(2).toFloat(),
                    paint,
                )
            }
            if (isShown) postInvalidateOnAnimation()
        }
    }
}
