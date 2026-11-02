package com.yexclsv.localfiles.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.yexclsv.localfiles.NativeBridge
import com.yexclsv.localfiles.R
import com.yexclsv.localfiles.data.Settings
import java.util.Date

/** Settings page, built in code: hero card, quick actions, then grouped cards with icon rows. */
class SettingsUi(private val container: LinearLayout, private val cb: Callbacks) {

    interface Callbacks {
        fun settings(): Settings
        fun update(block: (Settings) -> Settings)
        fun trackCount(): Int
        fun folderCount(): Int
        fun sleepUntil(): Long
        fun rescan()
        fun addFolder()
        fun addFiles()
        fun clearExtras()
        fun sleepMenu()
        fun version(): String
        fun checkUpdates()
        fun openEqualizer()
        fun backup()
        fun restore()
        fun stats(): Pair<String, String>
        fun resetStats()
        fun openRepo()
        fun hiddenCount(): Int
        fun unhideAll()
    }

    private val ctx: Context get() = container.context
    private val accent: Int get() = cb.settings().let { Accents.resolve(it.accentIndex, it.customHue) }
    private val text2 = 0xFF9C9CAC.toInt()
    private val ripple get() = tintList(0x22FFFFFF)

    fun render() {
        container.removeAllViews()
        container.setPadding(16.dp, 0, 16.dp, 0)
        val s = cb.settings()

        hero()
        tiles()

        section("Appearance", R.drawable.ic_palette)
        card {
            backgroundPicker(s.bgIndex)
            swatches(s.accentIndex)
            if (s.accentIndex == Accents.CUSTOM) {
                sliderRow(R.drawable.ic_tune, "Custom hue", s.customHue, 0f, 360f, { "${it.toInt()}°" }) { v ->
                    cb.update { it.copy(customHue = v) }
                }
            }
            switchRow(R.drawable.ic_music_note, "Artwork colors", "Tint the player from the cover", s.dynamicColor, true) { v ->
                cb.update { it.copy(dynamicColor = v) }
            }
        }

        section("Liquid glass", R.drawable.ic_tune)
        card {
            sliderRow(R.drawable.ic_tune, "Blur", s.blurDp, 2f, 32f, { "${it.toInt()}" }) { v ->
                cb.update { it.copy(blurDp = v) }
            }
            sliderRow(R.drawable.ic_tune, "Tint strength", s.tintPct.toFloat(), 0f, 100f, { "${it.toInt()}%" }) { v ->
                cb.update { it.copy(tintPct = v.toInt()) }
            }
            val can = Build.VERSION.SDK_INT >= 33
            switchRow(
                R.drawable.ic_shuffle, "Lens refraction",
                if (can) "Bends light at the glass edges" else "Needs Android 13 or newer",
                s.refraction && can, can
            ) { v -> cb.update { it.copy(refraction = v) } }
        }

        section("Sound", R.drawable.ic_music_note)
        card {
            actionRow(R.drawable.ic_tune, "Equalizer", if (s.eqOn) "On" else "Off") { cb.openEqualizer() }
            sliderRow(R.drawable.ic_play, "Bass boost", s.bassBoost.toFloat(), 0f, 100f, { "${it.toInt()}%" }) { v ->
                cb.update { it.copy(bassBoost = v.toInt()) }
            }
            sliderRow(R.drawable.ic_next, "Loudness boost", s.loudness.toFloat(), 0f, 100f, { "${it.toInt()}%" }) { v ->
                cb.update { it.copy(loudness = v.toInt()) }
            }
            switchRow(R.drawable.ic_timer, "Skip silence", "Trims silent gaps in songs", s.skipSilence, true) { v ->
                cb.update { it.copy(skipSilence = v) }
            }
        }

        section("Playback", R.drawable.ic_play)
        card {
            val until = cb.sleepUntil()
            actionRow(
                R.drawable.ic_timer, "Sleep timer",
                when {
                    until == Long.MAX_VALUE -> "Pauses when this song ends"
                    until > 0 -> "Pauses at " + DateFormat.getTimeFormat(ctx).format(Date(until))
                    else -> "Off"
                }
            ) { cb.sleepMenu() }
            switchRow(R.drawable.ic_more, "Haptic feedback", "Ticks on taps and scrubbing", s.haptics, true) { v ->
                cb.update { it.copy(haptics = v) }
            }
            chipRow(R.drawable.ic_music_note, "Skip clips shorter than",
                listOf("Off" to 0, "30 s" to 30, "60 s" to 60), s.minSeconds) { v ->
                cb.update { it.copy(minSeconds = v) }
                cb.rescan()
            }
        }

        section("Updates", R.drawable.ic_refresh)
        card {
            switchRow(R.drawable.ic_refresh, "Check automatically", "Looks for new releases on GitHub", s.autoUpdate, true) { v ->
                cb.update { it.copy(autoUpdate = v) }
            }
            actionRow(R.drawable.ic_info, "Check for updates now", "Installed: v${cb.version()}") { cb.checkUpdates() }
        }

        section("Your data", R.drawable.ic_heart)
        card {
            val (plays, top) = cb.stats()
            infoRow(R.drawable.ic_music_note, plays, top)
            actionRow(R.drawable.ic_folder, "Back up", "Favorites, playlists, edits, stats and settings") { cb.backup() }
            actionRow(R.drawable.ic_refresh, "Restore from backup", "Load a Lumen backup file") { cb.restore() }
            actionRow(R.drawable.ic_delete, "Reset listening stats", "Clears play counts and history") { cb.resetStats() }
        }

        section("Library", R.drawable.ic_folder)
        card {
            val hid = cb.hiddenCount()
            if (hid > 0) actionRow(R.drawable.ic_refresh, "Hidden songs", "$hid hidden. Tap to restore all") { cb.unhideAll() }
            actionRow(R.drawable.ic_delete, "Remove added files and folders", "Nothing is deleted from storage") { cb.clearExtras() }
        }

        section("About", R.drawable.ic_info)
        aboutCard()
        container.addView(View(ctx), LinearLayout.LayoutParams(1, 28.dp))
    }

    private fun aboutCard() {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        card {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp, 18.dp, 18.dp, 18.dp)
            }
            head.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.logo)
                roundCorners(16f.dp)
            }, LinearLayout.LayoutParams(64.dp, 64.dp))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = "Lumen"
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            })
            col.addView(TextView(ctx).apply { text = "Version $version"; setTextColor(text2); textSize = 12.5f })
            col.addView(TextView(ctx).apply {
                text = "Made by yexclsv"
                setTextColor(accent)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 4.dp, 0, 0)
            })
            head.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 16.dp })
            addView(head, LinearLayout.LayoutParams(-1, -2))
            actionRow(R.drawable.ic_info, "GitHub", "github.com/Yexclsv25/Lumen-Music") { cb.openRepo() }
            infoRow(R.drawable.ic_info, "Open-source credits", "AndroidX Media3, Material Components, AndroidX")
            infoRow(R.drawable.ic_music_note, "© 2026 yexclsv", NativeBridge.stringFromJNI())
        }
    }

    // ---------- top ----------
    private fun hero() {
        val a = accent
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 20.dp, 22.dp, 20.dp)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(mix(0xFF15151C.toInt(), a, 0.38f), 0xFF15151C.toInt())
            ).apply {
                cornerRadius = 26f.dp
                setStroke(1.dp, 0x14FFFFFF)
            }
        }
        box.addView(TextView(ctx).apply {
            text = cb.trackCount().toString()
            setTextColor(Color.WHITE)
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(ctx).apply {
            text = if (cb.trackCount() == 1) "song in your library" else "songs in your library"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
        })
        val folders = cb.folderCount()
        box.addView(TextView(ctx).apply {
            text = if (folders == 0) "Using device music" else "+ $folders added folder${if (folders == 1) "" else "s"}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12.dp, 5.dp, 12.dp, 5.dp)
            background = GradientDrawable().apply { cornerRadius = 100f.dp; setColor(0x26FFFFFF) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.dp })
        container.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp; bottomMargin = 12.dp })
    }

    private fun tiles() {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(icon: Int, label: String, last: Boolean, onClick: () -> Unit) {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, 16.dp, 0, 14.dp)
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener { it.tick(); onClick() }
                pressBounce(0.94f)
            }
            t.addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = tintList(accent)
            }, LinearLayout.LayoutParams(26.dp, 26.dp))
            t.addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12.5f
                setPadding(0, 8.dp, 0, 0)
            })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) marginEnd = 10.dp })
        }
        tile(R.drawable.ic_refresh, "Rescan", false) { cb.rescan() }
        tile(R.drawable.ic_folder, "Add folder", false) { cb.addFolder() }
        tile(R.drawable.ic_add, "Add files", true) { cb.addFiles() }
        container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6.dp })
    }

    // ---------- structure ----------
    private fun section(t: String, icon: Int) {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 20.dp, 8.dp, 8.dp)
        }
        r.addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = tintList(text2) }, LinearLayout.LayoutParams(14.dp, 14.dp))
        r.addView(TextView(ctx).apply {
            text = t.uppercase()
            setTextColor(text2)
            textSize = 11.5f
            letterSpacing = 0.1f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(8.dp, 0, 0, 0)
        })
        container.addView(r)
    }

    private fun card(body: LinearLayout.() -> Unit) {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
        }
        c.body()
        container.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.divider() {
        if (childCount == 0) return
        addView(View(ctx).apply { setBackgroundColor(0x12FFFFFF) },
            LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = 66.dp })
    }

    private fun iconBadge(icon: Int): View = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = tintList(accent)
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent.withAlpha(0x26)) }
    }

    private fun texts(title: String, sub: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 15f })
        if (sub != null) addView(TextView(ctx).apply {
            text = sub; setTextColor(text2); textSize = 12.5f; setPadding(0, 1.dp, 0, 0)
        })
    }

    private fun LinearLayout.baseRow(icon: Int, title: String, sub: String?, trailing: View?): LinearLayout {
        divider()
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        }
        r.addView(iconBadge(icon), LinearLayout.LayoutParams(36.dp, 36.dp))
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 14.dp; marginEnd = 8.dp })
        if (trailing != null) r.addView(trailing)
        addView(r, LinearLayout.LayoutParams(-1, -2))
        return r
    }

    // ---------- rows ----------
    private fun LinearLayout.infoRow(icon: Int, title: String, sub: String) { baseRow(icon, title, sub, null) }

    private fun LinearLayout.actionRow(icon: Int, title: String, sub: String, onClick: () -> Unit) {
        val chev = ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = tintList(text2) }
        val r = baseRow(icon, title, sub, chev)
        r.background = RippleDrawable(ripple, null, GradientDrawable().apply { setColor(Color.WHITE) })
        r.setOnClickListener { it.tick(); onClick() }
    }

    private fun LinearLayout.switchRow(icon: Int, title: String, sub: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(ctx).apply {
            isChecked = on
            isEnabled = enabled
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0x33FFFFFF))
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF101014.toInt(), 0xFFB4B4C2.toInt()))
            setOnCheckedChangeListener { v, c -> v.tick(); onChange(c) }
        }
        baseRow(icon, title, sub, sw)
    }

    private fun LinearLayout.sliderRow(icon: Int, title: String, value: Float, from: Float, to: Float, fmt: (Float) -> String, onChange: (Float) -> Unit) {
        divider()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 2.dp) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconBadge(icon), LinearLayout.LayoutParams(36.dp, 36.dp))
        head.addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 15f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 14.dp })
        val val0 = Math.round(value).toFloat().coerceIn(from, to)
        val valueText = TextView(ctx).apply { text = fmt(val0); setTextColor(accent); textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
        head.addView(valueText)
        r.addView(head)
        val sl = Slider(ctx).apply {
            valueFrom = from
            valueTo = to
            stepSize = 1f
            this.value = val0
            trackActiveTintList = tintList(accent)
            thumbTintList = tintList(accent)
            haloTintList = tintList(accent.withAlpha(0x33))
            trackInactiveTintList = tintList(0x26FFFFFF)
            addOnChangeListener { _, v, fromUser ->
                valueText.text = fmt(v)
                if (fromUser) onChange(v)
            }
        }
        r.addView(sl, LinearLayout.LayoutParams(-1, -2))
        addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.chipRow(icon: Int, title: String, opts: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit) {
        divider()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 14.dp) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconBadge(icon), LinearLayout.LayoutParams(36.dp, 36.dp))
        head.addView(TextView(ctx).apply { text = title; setTextColor(Color.WHITE); textSize = 15f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 14.dp })
        r.addView(head)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(50.dp, 10.dp, 0, 0) }
        opts.forEach { (label, v) ->
            val on = v == selected
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (on) 0xFF101014.toInt() else Color.WHITE)
                setPadding(16.dp, 7.dp, 16.dp, 7.dp)
                background = GradientDrawable().apply { cornerRadius = 100f.dp; setColor(if (on) accent else 0x1AFFFFFF) }
                setOnClickListener { it.tick(); onPick(v); render() }
                pressBounce(0.92f)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        }
        r.addView(row)
        addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.backgroundPicker(selected: Int) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 14.dp, 16.dp, 12.dp) }
        r.addView(TextView(ctx).apply { text = "Background"; setTextColor(Color.WHITE); textSize = 15f })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10.dp, 0, 0) }
        Backgrounds.names.forEachIndexed { i, n ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 16f.dp
                    setColor(Backgrounds.colors[i])
                    setStroke(if (i == selected) 2.dp else 1.dp, if (i == selected) accent else 0x33FFFFFF)
                }
                setOnClickListener { it.tick(); cb.update { s -> s.copy(bgIndex = i) }; render() }
                pressBounce(0.92f)
            }, LinearLayout.LayoutParams(-1, 52.dp))
            col.addView(TextView(ctx).apply {
                text = n
                textSize = 11.5f
                setTextColor(if (i == selected) Color.WHITE else text2)
                setPadding(0, 6.dp, 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < Backgrounds.names.lastIndex) marginEnd = 10.dp })
        }
        r.addView(row)
        addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.swatches(selected: Int) {
        divider()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 14.dp) }
        r.addView(TextView(ctx).apply { text = "Accent color"; setTextColor(Color.WHITE); textSize = 15f })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10.dp, 0, 0) }
        Accents.names.indices.forEach { i ->
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (i == Accents.CUSTOM) {
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        colors = intArrayOf(0xFFFF5F5F.toInt(), 0xFFFFD35F.toInt(), 0xFF5FFF8A.toInt(), 0xFF5FD0FF.toInt(), 0xFF8F5FFF.toInt(), 0xFFFF5FD0.toInt(), 0xFFFF5F5F.toInt())
                    } else setColor(Accents.colors[i])
                    if (i == selected) setStroke(3.dp, Color.WHITE)
                }
                contentDescription = Accents.names[i]
                setOnClickListener { it.tick(); cb.update { s -> s.copy(accentIndex = i) }; render() }
                pressBounce(0.85f)
            }, LinearLayout.LayoutParams(0, 38.dp, 1f).apply { if (i < Accents.names.lastIndex) marginEnd = 10.dp })
        }
        r.addView(row)
        addView(r, LinearLayout.LayoutParams(-1, -2))
    }
}
