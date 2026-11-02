package com.yexclsv.localfiles.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.yexclsv.localfiles.data.Settings

data class EqInfo(
    val bands: Int,
    val minMb: Int,
    val maxMb: Int,
    val freqsHz: List<Int>,
    val presets: List<String>
)

/** Equalizer, bass boost and loudness boost attached to the player's audio session. */
object AudioFx {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
    private var session = 0
    private var cached: Settings = Settings()
    private var info: EqInfo? = null

    val attached: Boolean get() = eq != null

    @Synchronized
    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return
        session = sessionId
        try { eq = Equalizer(0, sessionId) } catch (_: Throwable) {}
        try { bass = BassBoost(0, sessionId) } catch (_: Throwable) {}
        try { loud = LoudnessEnhancer(sessionId) } catch (_: Throwable) {}
        apply(cached)
    }

    @Synchronized
    fun release() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        try { loud?.release() } catch (_: Throwable) {}
        eq = null
        bass = null
        loud = null
    }

    @Synchronized
    fun apply(s: Settings) {
        cached = s
        try {
            eq?.let { e ->
                e.setEnabled(s.eqOn)
                if (s.eqOn) {
                    val levels = s.eqLevels.split(",").mapNotNull { it.trim().toIntOrNull() }
                    val n = e.numberOfBands.toInt()
                    for (b in 0 until minOf(n, levels.size)) {
                        e.setBandLevel(b.toShort(), levels[b].toShort())
                    }
                }
            }
        } catch (_: Throwable) {}
        try {
            bass?.let { b ->
                b.setEnabled(s.bassBoost > 0)
                if (b.strengthSupported) b.setStrength((s.bassBoost * 10).coerceIn(0, 1000).toShort())
            }
        } catch (_: Throwable) {}
        try {
            loud?.let { l ->
                l.setEnabled(s.loudness > 0)
                l.setTargetGain(s.loudness * 15)
            }
        } catch (_: Throwable) {}
    }

    /** Band layout of this device's equalizer, or null if no equalizer can be created yet. */
    @Synchronized
    fun info(): EqInfo? {
        info?.let { return it }
        val e = eq ?: return null
        return try {
            val range = e.bandLevelRange
            val n = e.numberOfBands.toInt()
            EqInfo(
                n, range[0].toInt(), range[1].toInt(),
                List(n) { e.getCenterFreq(it.toShort()) / 1000 },
                List(e.numberOfPresets.toInt()) { e.getPresetName(it.toShort()) }
            ).also { info = it }
        } catch (_: Throwable) {
            null
        }
    }

    /** Band levels (millibels) produced by a built-in preset. */
    @Synchronized
    fun presetLevels(preset: Int): List<Int>? {
        val e = eq ?: return null
        return try {
            e.usePreset(preset.toShort())
            List(e.numberOfBands.toInt()) { e.getBandLevel(it.toShort()).toInt() }
        } catch (_: Throwable) {
            null
        }
    }
}
