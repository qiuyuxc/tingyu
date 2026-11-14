package com.qiuyuxc.tingyu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

/** The handle and header drag the panel; children retain ordinary taps and list scrolling. */
internal class DraggableSheetLayout(context: Context) : LinearLayout(context) {
    var onDismissRequest: () -> Unit = {}
    var onDragStarted: () -> Unit = {}
    var onDragFractionChanged: (Float) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var initialOffset = 0f
    private var eligible = false
    private var dragging = false
    private var closing = false

    init {
        orientation = VERTICAL
        isClickable = true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseTracker()
                eligible = !closing && event.y <= (getChildAt(1)?.bottom ?: 0)
                dragging = false
                if (eligible) {
                    animator?.cancel()
                    animator = null
                    pointer = event.getPointerId(0)
                    startX = event.rawX
                    startY = event.rawY
                    initialOffset =
                        translationY.coerceAtLeast(0f) /
                            (1f - translationY / height.coerceAtLeast(1)).coerceAtLeast(0.01f)
                    tracker = VelocityTracker.obtain()
                    track(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (eligible && !dragging) {
                    track(event)
                    if (beginDrag(event)) return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    if (eligible && translationY != 0f) animateOffset(0f, springBack = true)
                    eligible = false
                    releaseTracker()
                }
            }
        }
        return dragging || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!eligible || closing) return super.onTouchEvent(event)
        track(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // With a non-clickable handle, the panel itself receives subsequent moves.
                if (!dragging) beginDrag(event)
                if (dragging) {
                    val distance = (initialOffset + event.rawY - startY - slop).coerceAtLeast(0f)
                    setOffset(dampedSheetOffset(distance, height.toFloat()))
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) {
                    // Cancelling a multi-finger drag never closes a panel accidentally.
                    finish(cancelled = true)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    finish(cancelled = false)
                    return true
                }
                if (translationY != 0f) animateOffset(0f, springBack = true)
                eligible = false
                releaseTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    finish(cancelled = true)
                    return true
                }
                if (translationY != 0f) animateOffset(0f, springBack = true)
                eligible = false
                releaseTracker()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginDrag(event: MotionEvent): Boolean {
        val distance = event.rawY - startY
        if (distance <= slop || distance <= abs(event.rawX - startX)) return false
        dragging = true
        isPressed = false
        cancelLongPress()
        parent?.requestDisallowInterceptTouchEvent(true)
        onDragStarted()
        return true
    }

    private fun track(event: MotionEvent) {
        val velocity = tracker ?: return
        val raw = MotionEvent.obtain(event)
        raw.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        velocity.addMovement(raw)
        raw.recycle()
    }

    private fun finish(cancelled: Boolean) {
        tracker?.computeCurrentVelocity(
            1000,
            ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat(),
        )
        val velocity = tracker?.getYVelocity(pointer) ?: 0f
        val dismiss =
            !cancelled &&
                shouldDismissSheet(translationY, height.toFloat(), velocity, UI.dp(1000).toFloat())
        dragging = false
        eligible = false
        parent?.requestDisallowInterceptTouchEvent(false)
        releaseTracker()
        if (dismiss) onDismissRequest() else animateOffset(0f, springBack = true)
    }

    fun animateDismiss(onComplete: () -> Unit) {
        if (closing) return
        closing = true
        animateOffset(height.toFloat(), springBack = false, onComplete)
    }

    fun resetDrag() {
        animator?.cancel()
        animator = null
        releaseTracker()
        closing = false
        dragging = false
        eligible = false
        setOffset(0f)
    }

    private fun animateOffset(target: Float, springBack: Boolean, complete: (() -> Unit)? = null) {
        animator?.cancel()
        if (!isAttachedToWindow || !PageMotion.enabled(context)) {
            setOffset(target)
            complete?.invoke()
            return
        }
        var cancelled = false
        animator =
            ValueAnimator.ofFloat(translationY, target).apply {
                duration = if (springBack) 320 else 180
                interpolator = if (springBack) OvershootInterpolator(0.6f) else PageMotion.easing
                addUpdateListener { setOffset(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) complete?.invoke()
                        }
                    }
                )
                start()
            }
    }

    private fun setOffset(value: Float) {
        translationY = value
        onDragFractionChanged((value / height.coerceAtLeast(1)).coerceIn(0f, 1f))
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
        pointer = MotionEvent.INVALID_POINTER_ID
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        releaseTracker()
        super.onDetachedFromWindow()
    }
}

/** Resistance increases with travel while small movements stay close to the finger. */
internal fun dampedSheetOffset(distance: Float, height: Float): Float =
    distance.coerceAtLeast(0f).let { it / (1f + it / height.coerceAtLeast(1f)) }

internal fun shouldDismissSheet(
    offset: Float,
    height: Float,
    velocity: Float,
    flingVelocity: Float,
): Boolean =
    height > 0f &&
        velocity > -flingVelocity &&
        (offset >= height * 0.25f || (offset > 0f && velocity >= flingVelocity))
