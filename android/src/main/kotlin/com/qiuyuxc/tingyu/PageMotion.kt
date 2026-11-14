package com.qiuyuxc.tingyu

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator

/** Short page transitions share the system's animation preference. */
internal object PageMotion {
    val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun enabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    fun enter(view: View, direction: Int = 0) {
        reset(view)
        if (!view.isAttachedToWindow || !enabled(view.context)) return
        view.alpha = 0f
        if (direction == 0) view.translationY = UI.dp(10).toFloat()
        else view.translationX = UI.dp(18).toFloat() * direction.coerceIn(-1, 1)
        view
            .animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(easing)
            .start()
    }
}
