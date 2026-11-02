package com.yexclsv.localfiles.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yexclsv.localfiles.data.Lyrics
import kotlin.math.max

/** Scrolling lyrics list; for time-stamped lyrics it highlights and centers the current line. */
class LyricsPanel(
    private val scroll: ScrollView,
    private val box: LinearLayout,
    private val onSeek: (Long) -> Unit
) {
    private var lyrics: Lyrics? = null
    private val views = ArrayList<TextView>()
    private var current = -1
    private var accent = Color.WHITE
    private val dim = 0x66FFFFFF
    private val soft = 0xE6FFFFFF.toInt()

    fun setAccent(a: Int) {
        accent = a
        if (current in views.indices) views[current].setTextColor(accent)
    }

    fun setLyrics(l: Lyrics?, accent: Int) {
        this.accent = accent
        lyrics = l
        current = -1
        views.clear()
        box.removeAllViews()
        scroll.scrollTo(0, 0)
        val ctx = box.context
        if (l == null || l.lines.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = "No lyrics yet\n\nOpen the \u22EF menu, choose Edit info & cover, then add a .lrc file or paste the lyrics."
                setTextColor(dim)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 70.dp, 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        box.addView(View(ctx), LinearLayout.LayoutParams(1, 70.dp))
        l.lines.forEach { line ->
            val tv = TextView(ctx).apply {
                text = if (line.text.isEmpty()) "\u266A" else line.text
                textSize = if (l.synced) 24f else 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (l.synced) dim else soft)
                setPadding(0, 9.dp, 0, 9.dp)
                pivotX = 0f
                if (l.synced) setOnClickListener { onSeek(line.timeMs) }
            }
            views.add(tv)
            box.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(View(ctx), LinearLayout.LayoutParams(1, 170.dp))
    }

    fun update(posMs: Long) {
        val l = lyrics ?: return
        if (!l.synced) return
        var lo = 0
        var hi = l.lines.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (l.lines[mid].timeMs <= posMs + 150) {
                idx = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (idx == current) return
        if (current in views.indices) style(views[current], false)
        current = idx
        if (idx in views.indices) {
            style(views[idx], true)
            val tv = views[idx]
            scroll.smoothScrollTo(0, max(0, tv.top - (scroll.height - tv.height) / 2))
        }
    }

    private fun style(tv: TextView, on: Boolean) {
        tv.setTextColor(if (on) accent else dim)
        val s = if (on) 1.05f else 1f
        tv.animate().scaleX(s).scaleY(s).setDuration(220).start()
    }
}
