package com.yexclsv.localfiles.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.yexclsv.localfiles.R
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Global glass settings; changing them re-renders every live glass surface. */
object GlassConfig {
    var blurPx: Float = 12f.dp
    var refraction: Boolean = true
    var tintScale: Float = 1f
    private val layouts: MutableSet<GlassLayout> = Collections.newSetFromMap(WeakHashMap<GlassLayout, Boolean>())

    fun add(g: GlassLayout) { layouts.add(g) }
    fun remove(g: GlassLayout) { layouts.remove(g) }

    fun update(blurDp: Float, refraction: Boolean, tintPct: Int) {
        blurPx = blurDp.dp
        this.refraction = refraction
        tintScale = 0.2f + tintPct / 100f * 1.6f
        layouts.toList().forEach { it.onConfigChanged() }
    }
}

/**
 * Anything glass looks through. It pings glass surfaces whenever a descendant redraws
 * (scrolling, art loading, animations), so the blur stays live without a render loop.
 */
class BackdropLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var glass: ArrayList<GlassLayout>? = null

    fun register(g: GlassLayout) {
        val l = glass ?: ArrayList<GlassLayout>().also { glass = it }
        if (!l.contains(g)) l.add(g)
    }

    fun unregister(g: GlassLayout) { glass?.remove(g) }

    private fun poke() {
        val l = glass ?: return
        for (i in 0 until l.size) l[i].invalidate()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        poke()
    }

    @Suppress("DEPRECATION")
    override fun invalidateChildInParent(location: IntArray?, dirty: Rect?): ViewParent? {
        poke()
        return super.invalidateChildInParent(location, dirty)
    }

    override fun invalidate() {
        super.invalidate()
        poke()
    }
}

private const val AGSL = """
uniform shader content;
uniform float2 size;
uniform float inset;
uniform float radius;
uniform float strength;

half4 main(float2 fragCoord) {
    float2 p = fragCoord - float2(inset);
    float2 c = p - size * 0.5;
    float2 q = abs(c) - (size * 0.5 - float2(radius));
    float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float depth = clamp(-sd / (radius * 0.85), 0.0, 1.0);
    float bend = pow(1.0 - depth, 3.0);
    float2 g = sign(c) * max(q, float2(0.0));
    float2 n;
    if (length(g) > 0.001) {
        n = normalize(g);
    } else if (q.x > q.y) {
        n = float2(sign(c.x), 0.0);
    } else {
        n = float2(0.0, sign(c.y));
    }
    float2 o = -n * bend * strength;
    half4 cr = content.eval(fragCoord + o * 1.06);
    half4 cg = content.eval(fragCoord + o);
    half4 cb = content.eval(fragCoord + o * 0.94);
    half3 col = half3(cr.r, cg.g, cb.b);
    col += half3(bend * 0.12);
    return half4(col, 1.0);
}
"""

/**
 * A "liquid glass" surface.
 *  - Android 12+: the [backdrop] is re-drawn into a RenderNode, blurred on the GPU, and clipped to the pill.
 *  - Android 13+: an AGSL shader bends and slightly splits colors at the edges (lens refraction).
 *  - Older: a dark translucent tint (graceful fallback).
 * A specular rim and top sheen are drawn on every version.
 */
open class GlassLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var backdrop: View? = null
        set(value) {
            (field as? BackdropLayout)?.unregister(this)
            field = value
            if (isAttachedToWindow) (value as? BackdropLayout)?.register(this)
            invalidate()
        }

    var cornerRadius: Float = 28f.dp
        set(value) { field = value; rebuild(); invalidate() }

    var tint: Int = 0x4A14141C
        set(value) { field = value; invalidate() }

    var allowRefraction = true
    var refractionStrength: Float = 9f.dp

    private val node: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("glass") else null
    private var agsl: RuntimeShader? = null
    private var effectDirty = true
    private var margin = 0

    private val path = Path()
    private val rimPath = Path()
    private val rect = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dp
    }
    private val loc = IntArray(2)
    private val bloc = IntArray(2)

    init {
        setWillNotDraw(false)
        clipChildren = false
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassLayout)
        cornerRadius = a.getDimension(R.styleable.GlassLayout_glass_cornerRadius, cornerRadius)
        tint = a.getColor(R.styleable.GlassLayout_glass_tint, tint)
        allowRefraction = a.getBoolean(R.styleable.GlassLayout_glass_refraction, true)
        a.recycle()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = min(cornerRadius, min(view.width, view.height) / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
                outline.alpha = 0.5f
            }
        }
    }

    fun onConfigChanged() {
        effectDirty = true
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassConfig.add(this)
        (backdrop as? BackdropLayout)?.register(this)
    }

    override fun onDetachedFromWindow() {
        GlassConfig.remove(this)
        (backdrop as? BackdropLayout)?.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(cornerRadius, min(w, h) / 2f)
        rect.set(0f, 0f, w, h)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        val half = rimPaint.strokeWidth / 2f
        rimPath.reset()
        rimPath.addRoundRect(RectF(half, half, w - half, h - half), r - half, r - half, Path.Direction.CW)

        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xB3FFFFFF.toInt(), 0x14FFFFFF, 0x0DFFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        effectDirty = true
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.clipPath(path)
        val live = drawBackdrop(canvas)
        tintPaint.color = if (live) {
            val a = (Color.alpha(tint) * GlassConfig.tintScale).toInt().coerceIn(0, 255)
            (tint and 0x00FFFFFF) or (a shl 24)
        } else 0xE6121218.toInt()
        canvas.drawRect(rect, tintPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.55f, sheenPaint)
        canvas.restore()
        canvas.drawPath(rimPath, rimPaint)
    }

    private var broken = false

    private fun drawBackdrop(canvas: Canvas): Boolean {
        if (broken) return false
        return try {
            drawBackdropUnsafe(canvas)
        } catch (e: Throwable) {
            broken = true
            android.util.Log.e("GlassLayout", "backdrop draw failed, falling back to tint", e)
            false
        }
    }

    private fun drawBackdropUnsafe(canvas: Canvas): Boolean {
        val bd = backdrop ?: return false
        val n = node ?: return false
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return false
        if (!bd.isAttachedToWindow || bd.width == 0 || bd.height == 0) return false

        if (effectDirty) buildEffect(n)
        val m = margin
        n.setPosition(-m, -m, width + m, height + m)
        getLocationInWindow(loc)
        bd.getLocationInWindow(bloc)
        val rc = n.beginRecording(width + 2 * m, height + 2 * m)
        try {
            rc.translate((bloc[0] - loc[0] + m).toFloat(), (bloc[1] - loc[1] + m).toFloat())
            bd.draw(rc)
        } finally {
            n.endRecording()
        }
        canvas.drawRenderNode(n)
        return true
    }

    private fun buildEffect(n: RenderNode) {
        if (Build.VERSION.SDK_INT < 31) return
        val sigma = max(0.5f, GlassConfig.blurPx)
        margin = ceil(sigma * 1.5f).toInt()
        val blur = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
        var effect: RenderEffect = blur
        if (Build.VERSION.SDK_INT >= 33 && GlassConfig.refraction && allowRefraction) {
            effect = refractEffect(blur)
        }
        n.setRenderEffect(effect)
        effectDirty = false
    }

    @androidx.annotation.RequiresApi(33)
    private fun refractEffect(blur: RenderEffect): RenderEffect {
        val s = agsl ?: RuntimeShader(AGSL).also { agsl = it }
        s.setFloatUniform("size", width.toFloat(), height.toFloat())
        s.setFloatUniform("inset", margin.toFloat())
        s.setFloatUniform("radius", min(cornerRadius, min(width, height) / 2f))
        s.setFloatUniform("strength", refractionStrength)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "content"), blur)
    }
}

/** Glass tab bar with a sliding glass "lens" that stretches while it moves between tabs. */
class GlassTabBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : GlassLayout(context, attrs, defStyleAttr) {

    private val indicator = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 200f.dp
            setColor(0x2EFFFFFF)
            setStroke(1.dp, 0x40FFFFFF)
        }
    }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val items = ArrayList<Pair<ImageView, TextView>>()

    var onSelected: ((Int) -> Unit)? = null
    var selected = 0
        private set
    var accent: Int = Color.WHITE
        set(v) { field = v; applyColors() }

    init {
        val pad = 6.dp
        setPadding(pad, pad, pad, pad)
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setTabs(defs: List<Pair<Int, String>>) {
        row.removeAllViews()
        items.clear()
        defs.forEachIndexed { i, (icon, label) ->
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            }
            val iv = ImageView(context).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp)
            }
            val tv = TextView(context).apply {
                text = label
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 2.dp, 0, 0)
            }
            col.addView(iv, LinearLayout.LayoutParams(24.dp, 24.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
            col.addView(tv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            col.setOnClickListener {
                if (i != selected) {
                    it.tick()
                    select(i, true)
                    onSelected?.invoke(i)
                }
            }
            col.pressBounce(0.92f)
            row.addView(col)
            items.add(iv to tv)
        }
        applyColors()
        moveIndicator(false)
    }

    fun select(i: Int, animate: Boolean) {
        selected = i
        applyColors()
        moveIndicator(animate)
    }

    private fun applyColors() {
        items.forEachIndexed { i, (iv, tv) ->
            val c = if (i == selected) accent else 0xFFB4B4C2.toInt()
            iv.imageTintList = tintList(c)
            tv.setTextColor(c)
        }
    }

    private fun tabWidth() = (width - paddingLeft - paddingRight) / max(1, items.size)

    private fun moveIndicator(animate: Boolean) {
        val tw = tabWidth()
        if (tw <= 0) return
        val target = (selected * tw).toFloat()
        if (!animate) {
            indicator.translationX = target
            return
        }
        indicator.spring(
            DynamicAnimation.TRANSLATION_X, target, 520f, 0.72f,
            onUpdate = { value ->
                val d = min(1f, abs(target - value) / tw)
                indicator.scaleX = 1f + 0.26f * d
                indicator.scaleY = 1f - 0.10f * d
            },
            onEnd = {
                indicator.scaleX = 1f
                indicator.scaleY = 1f
            }
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val tw = tabWidth()
        indicator.layout(paddingLeft, paddingTop, paddingLeft + tw, (bottom - top) - paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moveIndicator(false)
    }
}
