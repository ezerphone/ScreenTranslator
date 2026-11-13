package com.screentranslate.he

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translations over a picture the way Google Lens does — the one routine used by the screen
 * overlay, the photo/shared-image view and the saved image:
 *
 *  - The original text is erased with the background color read around it ([TextStyle]),
 *    as a gradient when the color above and below it differ.
 *  - The Hebrew is written in the original text's color and weight, inside the original's box.
 *  - The size follows the room: as large as fits (it grows when the Hebrew is shorter, shrinks
 *    when it is longer), without splitting a word in the middle. Only when even the smallest
 *    allowed size ([minTextPx]) does not fit does the patch grow downwards.
 *
 * [unit] is the size of 1dp in the target's pixels.
 */
class TranslationPainter(private val unit: Float, private val minTextPx: Float) {

    class Placed(
        val block: TranslatedBlock,
        val box: RectF,
        val layout: StaticLayout,
        val textLeft: Float,
        val textTop: Float,
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun typeface(bold: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.SANS_SERIF, if (bold) 700 else 500, false)
        } else {
            Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /**
     * Places [blocks] (image coordinates) on a target where image point p maps to p * scale + d.
     * [targetWidth] keeps the patches inside the target.
     */
    fun place(
        blocks: List<TranslatedBlock>,
        scale: Float,
        dx: Float,
        dy: Float,
        targetWidth: Float,
    ): List<Placed> = blocks.map { block ->
        val r = block.rect
        val lines = max(block.lineCount, 1)
        val left = r.left * scale + dx
        val top = r.top * scale + dy
        val width = max(r.width() * scale, 4 * unit)
        val height = max(r.height() * scale, 4 * unit)
        val lineHeight = height / lines

        // The patch covers a little more than the OCR box, so no edge of the original shows.
        val cover = (lineHeight * 0.14f).coerceIn(1f * unit, 6f * unit)
        val textWidth = max(1, width.toInt())
        val available = height + cover

        val base = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = block.style.foreground
            typeface = typeface(block.style.bold)
        }
        val align = if (lines == 1) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        val text = block.translated
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // From the original letter size upwards when there is room, downwards when there is not.
        val maxSize = min(height * 0.82f, lineHeight * 1.15f).coerceAtLeast(minTextPx)
        val minSize = min(minTextPx, maxSize)

        fun fits(layout: StaticLayout, size: Float, wholeWords: Boolean): Boolean {
            if (layout.height > available) return false
            if (!wholeWords) return true
            val paint = TextPaint(base).apply { textSize = size }
            return words.all { paint.measureText(it) <= textWidth }
        }

        fun largest(wholeWords: Boolean): StaticLayout? {
            var lo = minSize
            var hi = maxSize
            var best: StaticLayout? = null
            build(text, base, maxSize, textWidth, align).let { if (fits(it, maxSize, wholeWords)) return it }
            repeat(10) {
                val mid = (lo + hi) / 2
                val candidate = build(text, base, mid, textWidth, align)
                if (fits(candidate, mid, wholeWords)) {
                    best = candidate
                    lo = mid
                } else {
                    hi = mid
                }
            }
            if (best == null) {
                val smallest = build(text, base, minSize, textWidth, align)
                if (fits(smallest, minSize, wholeWords)) best = smallest
            }
            return best
        }

        val layout = largest(wholeWords = true)
            ?: largest(wholeWords = false) // a single word wider than the box
            ?: build(text, base, minSize, textWidth, align) // last resort: the patch grows

        var boxLeft = left - cover
        var boxRight = left + width + cover
        if (boxRight > targetWidth) boxRight = targetWidth
        if (boxLeft < 0f) boxLeft = 0f
        val boxBottom = max(top + height + cover, top + layout.height + cover)
        val box = RectF(boxLeft, top - cover, boxRight, boxBottom)
        val textTop = if (layout.height <= height) top + (height - layout.height) / 2f else top
        Placed(block, box, layout, left, textTop)
    }

    private fun build(text: String, base: TextPaint, size: Float, width: Int, align: Layout.Alignment): StaticLayout {
        val paint = TextPaint(base).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .build()
    }

    fun draw(canvas: Canvas, placed: List<Placed>) {
        for (p in placed) {
            val style = p.block.style
            if (style.backgroundTop == style.backgroundBottom) {
                fill.shader = null
                fill.color = style.backgroundTop
            } else {
                fill.shader = LinearGradient(
                    0f, p.box.top, 0f, p.box.bottom,
                    style.backgroundTop, style.backgroundBottom, Shader.TileMode.CLAMP
                )
            }
            val radius = min(4 * unit, p.box.height() * 0.25f)
            canvas.drawRoundRect(p.box, radius, radius, fill)
            canvas.save()
            canvas.translate(p.textLeft, p.textTop)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        /** A new image: [source] with the translations drawn on it (for saving). */
        fun render(source: Bitmap, blocks: List<TranslatedBlock>): Bitmap {
            val out = source.copy(Bitmap.Config.ARGB_8888, true)
            // Treat the image as if shown 360dp wide, so it looks like it does on screen.
            val unit = out.width / 360f
            val painter = TranslationPainter(unit, 5.5f * unit)
            painter.draw(Canvas(out), painter.place(blocks, 1f, 0f, 0f, out.width.toFloat()))
            return out
        }
    }
}
