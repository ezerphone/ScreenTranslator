package com.screentranslate.he

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The look of a piece of text in a picture, so the translation can be drawn the way Google Lens
 * does: the original text erased with the colors around it, and the Hebrew written in the
 * original text's color and weight.
 */
data class TextStyle(
    /** Background just above and just below the text (a gradient when they differ). */
    val backgroundTop: Int,
    val backgroundBottom: Int,
    /** Color of the original letters. */
    val foreground: Int,
    /** Thick letters: draw the translation in bold. */
    val bold: Boolean,
) {
    companion object {
        val DEFAULT = TextStyle(Color.WHITE, Color.WHITE, 0xFF1F1F1F.toInt(), false)
    }
}

object TextColors {

    /** Reads the style of the text in [rect] of [bitmap]. Never fails: falls back to black on white. */
    fun sample(bitmap: Bitmap, rect: Rect): TextStyle = try {
        sampleOrThrow(bitmap, rect)
    } catch (e: Exception) {
        TextStyle.DEFAULT // e.g. a hardware bitmap that cannot be read
    }

    private fun sampleOrThrow(bitmap: Bitmap, rect: Rect): TextStyle {
        val w = bitmap.width
        val h = bitmap.height
        val r = Rect(rect)
        if (!r.intersect(0, 0, w, h) || r.width() < 2 || r.height() < 2) return TextStyle.DEFAULT

        // A ring just outside the text: that is the background.
        val pad = max(2, r.height() / 6)
        val outer = Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        outer.intersect(0, 0, w, h)
        val top = ArrayList<Int>()
        val bottom = ArrayList<Int>()
        val stepX = max(1, outer.width() / 48)
        var x = outer.left
        while (x < outer.right) {
            top += bitmap.getPixel(x, outer.top)
            bottom += bitmap.getPixel(x, outer.bottom - 1)
            x += stepX
        }
        val stepY = max(1, outer.height() / 16)
        var y = outer.top
        while (y < outer.bottom) {
            val side = if (y < outer.centerY()) top else bottom
            side += bitmap.getPixel(outer.left, y)
            side += bitmap.getPixel(outer.right - 1, y)
            y += stepY
        }
        val bgTop = dominant(top)
        val bgBottom = dominant(bottom)
        val bg = dominant(top + bottom)

        // Inside: the pixels farthest from the background are the letters.
        val inside = ArrayList<Int>()
        val gx = max(1, r.width() / 64)
        val gy = max(1, r.height() / 20)
        y = r.top
        while (y < r.bottom) {
            x = r.left
            while (x < r.right) {
                inside += bitmap.getPixel(x, y)
                x += gx
            }
            y += gy
        }
        val distances = inside.map { distance(it, bg) }
        val farthest = distances.maxOrNull() ?: 0.0
        var foreground: Int
        var bold = false
        if (farthest < 60) {
            foreground = contrasting(bg) // letters barely differ from the background
        } else {
            val threshold = farthest * 0.6
            var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
            var ink = 0
            for (i in inside.indices) {
                if (distances[i] >= farthest * 0.45) ink++
                if (distances[i] >= threshold) {
                    val c = inside[i]
                    rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
                }
            }
            foreground = Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
            // Regular letters cover roughly a fifth of their box; bold ones much more.
            bold = ink.toFloat() / inside.size > 0.34f
            if (contrast(foreground, bg) < 3.0) foreground = contrasting(bg)
        }
        return TextStyle(bgTop, bgBottom, foreground, bold)
    }

    /** The most common color (in coarse buckets), averaged within its bucket. */
    private fun dominant(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        val counts = HashMap<Int, Int>()
        for (c in pixels) {
            val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }!!.key
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (c in pixels) {
            val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            if (key == best) {
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
            }
        }
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** WCAG contrast ratio (1–21). */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Near-black on light backgrounds, white on dark ones. */
    private fun contrasting(bg: Int): Int = if (luminance(bg) > 0.4) 0xFF1F1F1F.toInt() else Color.WHITE
}
