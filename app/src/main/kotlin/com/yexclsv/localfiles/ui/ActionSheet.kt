package com.yexclsv.localfiles.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.yexclsv.localfiles.R

/** Bottom sheet made of glass. Rows spring in; scrim tap or back dismisses. */
class ActionSheet(
    private val host: FrameLayout,
    private val accent: () -> Int,
    private val bottomInset: () -> Int
) {
    class Item(
        val label: String,
        val selected: Boolean = false,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private var scrim: FrameLayout? = null
    val isShown: Boolean get() = scrim != null

    fun show(title: String, items: List<Item>, backdrop: View?) {
        dismiss(false)
        val ctx = host.context
        val screenH = host.resources.displayMetrics.heightPixels

        val sc = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            isClickable = true
            setOnClickListener { dismiss(true) }
        }

        val panel = GlassLayout(ctx).apply {
            cornerRadius = 32f.dp
            this.backdrop = backdrop
            tint = 0x80101016.toInt()
            elevation = 12f.dp
            isClickable = true
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 10.dp)
        }
        col.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 10f.dp; setColor(0x55FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(36.dp, 5.dp).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp
            }
        })
        col.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(14.dp, 4.dp, 14.dp, 10.dp)
        })

        items.forEach { item ->
            val tv = TextView(ctx).apply {
                text = item.label
                textSize = 16.5f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = 52.dp
                setPadding(14.dp, 0, 14.dp, 0)
                val c = when {
                    item.destructive -> 0xFFFF6B6B.toInt()
                    item.selected -> accent()
                    else -> Color.WHITE
                }
                setTextColor(c)
                if (item.selected) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0)
                    compoundDrawableTintList = tintList(accent())
                }
                val mask = GradientDrawable().apply { cornerRadius = 18f.dp; setColor(Color.WHITE) }
                background = RippleDrawable(tintList(0x33FFFFFF), null, mask)
                setOnClickListener {
                    tick()
                    dismiss(true)
                    item.onClick()
                }
                pressBounce(0.97f)
            }
            col.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val scroll = MaxHeightScrollView(ctx).apply {
            maxH = (screenH * 0.62f).toInt()
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
        }
        panel.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sc.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(12.dp, 0, 12.dp, bottomInset() + 12.dp)
        })
        host.addView(sc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrim = sc

        sc.animate().alpha(1f).setDuration(220).start()
        panel.translationY = screenH.toFloat()
        panel.spring(DynamicAnimation.TRANSLATION_Y, 0f, 380f, 0.86f)
    }

    fun dismiss(animate: Boolean = true) {
        val sc = scrim ?: return
        scrim = null
        if (!animate) {
            host.removeView(sc)
            return
        }
        sc.isClickable = false
        sc.animate().alpha(0f).setDuration(200).withEndAction { host.removeView(sc) }.start()
        (sc.getChildAt(0))?.spring(DynamicAnimation.TRANSLATION_Y, host.height.toFloat(), 420f, 1f)
    }
}
