package com.yexclsv.localfiles.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.yexclsv.localfiles.R
import com.yexclsv.localfiles.data.ArtLoader
import com.yexclsv.localfiles.data.ArtPlaceholder
import com.yexclsv.localfiles.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a shl 24)
fun mix(a: Int, b: Int, t: Float): Int = ColorUtils.blendARGB(a, b, t)
fun tintList(c: Int): ColorStateList = ColorStateList.valueOf(c)

object Accents {
    val colors = intArrayOf(
        0xFF8F7BFF.toInt(), // violet
        0xFF5EE6C0.toInt(), // mint
        0xFFFF7A8A.toInt(), // coral
        0xFFFFC857.toInt(), // amber
        0xFF5BB8FF.toInt()  // sky
    )
    val names = listOf("Violet", "Mint", "Coral", "Amber", "Sky", "Custom")
    const val CUSTOM = 5

    fun resolve(index: Int, hue: Float): Int =
        if (index == CUSTOM) android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 1f))
        else colors[index.coerceIn(0, colors.lastIndex)]
}

object Backgrounds {
    val colors = intArrayOf(0xFF09090D.toInt(), 0xFF000000.toInt(), 0xFF17171C.toInt(), 0xFF0A1226.toInt())
    val names = listOf("Midnight", "AMOLED", "Graphite", "Navy")
}

object Haptics {
    var enabled = true
}

fun View.tick() {
    if (Haptics.enabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

fun View.roundCorners(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}

fun Bitmap.isUsable() = !isRecycled && width > 0

/** Starts (or retargets) a spring on a view property. One spring per view+property, retargeting keeps velocity smooth. */
fun View.spring(
    prop: DynamicAnimation.ViewProperty,
    target: Float,
    stiffness: Float = 450f,
    damping: Float = 0.88f,
    velocity: Float = 0f,
    onUpdate: ((Float) -> Unit)? = null,
    onEnd: (() -> Unit)? = null
): SpringAnimation {
    @Suppress("UNCHECKED_CAST")
    val map = (getTag(R.id.spring_map) as? HashMap<Any, SpringAnimation>)
        ?: HashMap<Any, SpringAnimation>().also { setTag(R.id.spring_map, it) }
    map[prop]?.cancel()
    val a = SpringAnimation(this, prop)
    a.setSpring(SpringForce(target).setStiffness(stiffness).setDampingRatio(damping))
    a.setStartVelocity(velocity)
    if (onUpdate != null) a.addUpdateListener { _, value, _ -> onUpdate(value) }
    if (onEnd != null) a.addEndListener { _, canceled, _, _ -> if (!canceled) onEnd() }
    map[prop] = a
    a.start()
    return a
}

/** Squishy press feedback: scales down on touch, springs back with a little overshoot. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressBounce(scale: Float = 0.9f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.spring(DynamicAnimation.SCALE_X, scale, 900f, 0.65f)
                v.spring(DynamicAnimation.SCALE_Y, scale, 900f, 0.65f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.spring(DynamicAnimation.SCALE_X, 1f, 420f, 0.45f)
                v.spring(DynamicAnimation.SCALE_Y, 1f, 420f, 0.45f)
            }
        }
        false
    }
}

/** Loads art into an ImageView, showing a per-track gradient first. Safe inside RecyclerView binds. */
fun ImageView.loadArt(scope: CoroutineScope, track: Track?, sizePx: Int, onLoaded: ((Bitmap?) -> Unit)? = null) {
    (getTag(R.id.art_job) as? Job)?.cancel()
    if (track == null) {
        setImageDrawable(ArtPlaceholder.make("none"))
        return
    }
    setImageDrawable(ArtPlaceholder.make(track.key))
    val job = scope.launch {
        val b = ArtLoader.load(context, track, sizePx)
        if (b != null) setImageBitmap(b)
        onLoaded?.invoke(b)
    }
    setTag(R.id.art_job, job)
}
