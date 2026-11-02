package com.yexclsv.localfiles.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Cached lookups return immediately without suspending, so list rows never flash a placeholder twice. */
    suspend fun load(ctx: Context, t: Track, size: Int): Bitmap? {
        val k = "${t.key}|${t.coverPath}@$size"
        cache.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { decode(ctx.applicationContext, t, size) } ?: return null
        cache.put(k, bmp)
        return bmp
    }

    private fun decodeFile(path: String, size: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var s = 1
        while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) {
        null
    }

    private fun decode(ctx: Context, t: Track, size: Int): Bitmap? {
        t.coverPath?.let { p -> decodeFile(p, size)?.let { return it } }
        if (t.fromMediaStore && Build.VERSION.SDK_INT >= 29) {
            try {
                return ctx.contentResolver.loadThumbnail(t.uri, Size(size, size), null)
            } catch (_: Exception) {
            }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, t.uri)
            val bytes = r.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var s = 1
            while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }
}

fun Bitmap.soft(): Bitmap =
    if (Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

/** Each track without art gets its own gradient, derived from its key. */
object ArtPlaceholder {
    fun make(key: String): Drawable {
        val h = key.hashCode() and 0x7fffffff
        val hue = (h % 360).toFloat()
        val c1 = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f))
        val c2 = Color.HSVToColor(floatArrayOf((hue + 48f) % 360f, 0.70f, 0.28f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
    }
}

data class ArtColors(val accent: Int, val c1: Int, val c2: Int)

object ArtPalette {
    suspend fun extract(bmp: Bitmap): ArtColors = withContext(Dispatchers.Default) {
        val p = Palette.from(bmp.soft()).maximumColorCount(16).generate()
        val vib = p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(0xFF8F7BFF.toInt())))
        val dark = p.getDarkVibrantColor(p.getMutedColor(ColorUtils.blendARGB(vib, Color.BLACK, 0.5f)))
        var acc = vib
        var guard = 0
        while (ColorUtils.calculateLuminance(acc) < 0.35 && guard++ < 10) acc = ColorUtils.blendARGB(acc, Color.WHITE, 0.2f)
        ArtColors(acc, vib, dark)
    }
}
