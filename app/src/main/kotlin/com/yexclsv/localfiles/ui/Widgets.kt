package com.yexclsv.localfiles.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SquareImageView @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : AppCompatImageView(c, a, d) {
    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
    }
}

/** Sizes its single child to the largest square that fits and centers it. */
class SquareFitLayout @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : FrameLayout(c, a, d) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) w else MeasureSpec.getSize(heightSpec)
        val s = max(0, min(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom))
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val cx = (r - l) / 2
            val cy = (b - t) / 2
            v.layout(cx - v.measuredWidth / 2, cy - v.measuredHeight / 2, cx + v.measuredWidth / 2, cy + v.measuredHeight / 2)
        }
    }
}

class MaxHeightScrollView(c: Context) : ScrollView(c) {
    var maxH = Int.MAX_VALUE
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** Three animated bars shown on the playing row. */
class EqView @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : View(c, a, d) {
    var color: Int = Color.WHITE
        set(v) { field = v; paint.color = v; invalidate() }
    var active = false
        set(v) {
            field = v
            updateAnim()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    private fun updateAnim() {
        if (active && isAttachedToWindow) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnim() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w / 5f
        for (i in 0..2) {
            val phase = anim.animatedFraction + i * 0.33f
            val f = if (active) 0.3f + 0.7f * abs(sin(phase * Math.PI)).toFloat() else 0.3f
            val bh = h * f
            val x = bw * (1 + i * 2)
            canvas.drawRoundRect(x - bw / 2, h - bh, x + bw / 2, h, bw / 2, bw / 2, paint)
        }
    }
}

/** Thick pill scrubber that swells under your finger. */
class LiquidSeekBar @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : View(c, a, d) {

    interface Listener {
        fun onStart()
        fun onChange(fraction: Float)
        fun onStop(fraction: Float)
    }

    var listener: Listener? = null
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var accent = Color.WHITE
        set(v) { field = v; invalidate() }

    private var grow = 0f
    private var growAnim: ValueAnimator? = null
    private var dragging = false
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = 14.dp.toFloat()

    private fun animateGrow(to: Float) {
        growAnim?.cancel()
        growAnim = ValueAnimator.ofFloat(grow, to).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val th = (6f + 8f * grow).dp / 1f
        val l = pad
        val r = width - pad
        val half = th / 2f
        canvas.drawRoundRect(l, cy - half, r, cy + half, half, half, trackPaint)
        val x = l + (r - l) * progress
        fillPaint.color = accent
        if (x > l) canvas.drawRoundRect(l, cy - half, max(x, l + th), cy + half, half, half, fillPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val span = max(1f, width - 2 * pad)
        val f = ((e.x - pad) / span).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                animateGrow(1f)
                progress = f
                listener?.onStart()
                listener?.onChange(f)
                tick()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                progress = f
                listener?.onChange(f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateGrow(0f)
                progress = f
                listener?.onStop(f)
            }
        }
        return true
    }
}

/** Full-screen sheet that follows your finger down and springs closed or back. */
class DragDismissLayout @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : FrameLayout(c, a, d) {

    var onRelease: ((dismiss: Boolean, velocity: Float) -> Unit)? = null

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var startY = 0f
    private var startX = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e)
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - startY
                val dx = e.rawX - startX
                if (!dragging && dy > slop && dy > abs(dx) * 1.4f) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    private fun begin(e: MotionEvent) {
        startY = e.rawY
        startX = e.rawX
        dragging = false
        vt?.recycle()
        vt = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vt == null) begin(e)
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - startY
                if (!dragging && dy > slop && dy > abs(e.rawX - startX)) dragging = true
                if (dragging) {
                    val over = max(0f, dy - slop)
                    translationY = over
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val dismiss = e.actionMasked == MotionEvent.ACTION_UP && (translationY > height * 0.22f || vy > 1400f)
                    onRelease?.invoke(dismiss, vy)
                }
                dragging = false
                vt?.recycle()
                vt = null
            }
        }
        return true
    }
}

/** Soft color glow behind everything; tints itself from the current artwork so glass has something to refract. */
class AuroraView @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, d: Int = 0
) : View(c, a, d) {

    private var c1 = 0xFF8F7BFF.toInt()
    private var c2 = 0xFF2A6CFF.toInt()
    private val p1 = Paint()
    private val p2 = Paint()
    private var anim: ValueAnimator? = null

    fun setColors(a: Int, b: Int, animate: Boolean = true) {
        val fromA = c1
        val fromB = c2
        anim?.cancel()
        if (!animate || width == 0) {
            c1 = a; c2 = b; rebuild(); invalidate(); return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener {
                val t = it.animatedValue as Float
                c1 = mix(fromA, a, t)
                c2 = mix(fromB, b, t)
                rebuild()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        p1.shader = RadialGradient(w * 0.9f, h * 0.10f, w * 1.0f, c1.withAlpha(0x66), 0x00000000, Shader.TileMode.CLAMP)
        p2.shader = RadialGradient(w * 0.05f, h * 0.72f, w * 1.1f, c2.withAlpha(0x52), 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p1)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p2)
    }
}
