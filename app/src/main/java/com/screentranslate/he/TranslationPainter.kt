package com.screentranslate.he

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translations on top of the original text — the one routine used by the screen overlay,
 * the photo/shared-image view and the saved image, so all of them follow the same rule:
 *
 * The translation is drawn inside the original text's box. It starts at about the original's
 * size; if the Hebrew is longer, the text is made smaller until it fits the box. Only when even
 * the smallest readable size does not fit does the box grow downwards.
 *
 * [unit] is the size of 1dp in the target's pixels; [minTextPx] the smallest allowed text size.
 */
class TranslationPainter(private val unit: Float, private val minTextPx: Float) {

    class Placed(
        val block: TranslatedBlock,
        val box: RectF,
        val layout: StaticLayout,
        val textLeft: Float,
        val textTop: Float,
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * unit
        color = 0xFF0B7A5C.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF12302A.toInt() }

    /**
     * Places [blocks] (image coordinates) on a target where image point p maps to p * scale + d.
     * [targetWidth] keeps widened boxes inside the target.
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
        val height = r.height() * scale
        val lineHeight = height / lines

        var width = max(r.width() * scale, 36 * unit)
        var left = r.left * scale + dx
        if (left + width > targetWidth) left = max(0f, targetWidth - width)
        width = min(width, targetWidth - left)
        val top = r.top * scale + dy

        val padX = (lineHeight * 0.2f).coerceIn(2 * unit, 8 * unit)
        val padY = (lineHeight * 0.08f).coerceIn(1 * unit, 4 * unit)
        val textWidth = max(1, (width - padX * 2).toInt())
        val available = height - padY * 2

        // About the original size, never larger than 26dp-equivalent.
        val maxSize = (lineHeight * 0.78f).coerceAtMost(26 * unit).coerceAtLeast(minTextPx)
        val minSize = min(minTextPx, maxSize)

        var layout = build(block.translated, maxSize, textWidth)
        if (layout.height > available) {
            // Longer than the original: binary-search the largest size that fits the box.
            var lo = minSize
            var hi = maxSize
            var fitting: StaticLayout? = null
            repeat(9) {
                val mid = (lo + hi) / 2
                val candidate = build(block.translated, mid, textWidth)
                if (candidate.height <= available) {
                    fitting = candidate
                    lo = mid
                } else {
                    hi = mid
                }
            }
            layout = fitting ?: build(block.translated, minSize, textWidth)
        }

        val boxHeight = max(height, layout.height + padY * 2)
        val box = RectF(left, top, left + width, top + boxHeight)
        Placed(block, box, layout, left + padX, top + (boxHeight - layout.height) / 2f)
    }

    private fun build(text: String, size: Float, width: Int): StaticLayout {
        val paint = TextPaint(textPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    fun draw(canvas: Canvas, placed: List<Placed>) {
        for (p in placed) {
            val radius = min(10 * unit, p.box.height() * 0.3f)
            canvas.drawRoundRect(p.box, radius, radius, fill)
            canvas.drawRoundRect(p.box, radius, radius, stroke)
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
            val painter = TranslationPainter(unit, 9 * unit)
            painter.draw(Canvas(out), painter.place(blocks, 1f, 0f, 0f, out.width.toFloat()))
            return out
        }
    }
}
