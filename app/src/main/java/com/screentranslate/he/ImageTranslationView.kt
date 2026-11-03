package com.screentranslate.he

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.max

/**
 * Shows a shared image with the Hebrew translations drawn over the original English
 * (design C: white rounded cards with a teal outline). Tap toggles original / translation.
 */
class ImageTranslationView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var blocks: List<TranslatedBlock> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var showTranslation = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF0B7A5C.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF12302A.toInt() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bmp = bitmap
        val height = if (bmp == null || bmp.width == 0) 0 else width * bmp.height / bmp.width
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val scale = width.toFloat() / bmp.width

        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()

        if (!showTranslation) return
        val pad = 6 * density
        val radius = 10 * density
        for (block in blocks) {
            val left = block.rect.left * scale
            val top = block.rect.top * scale
            val boxWidth = max(block.rect.width() * scale, 48 * density).coerceAtMost(width - left)
            val lineHeight = block.rect.height() * scale / max(block.lineCount, 1)
            textPaint.textSize = (lineHeight * 0.68f).coerceIn(sp(10f), sp(22f))

            val textWidth = max(1, (boxWidth - pad * 2).toInt())
            val layout = StaticLayout.Builder
                .obtain(block.translated, 0, block.translated.length, textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()
            val boxHeight = max(block.rect.height() * scale, layout.height + pad * 2)

            box.set(left, top, left + boxWidth, top + boxHeight)
            canvas.drawRoundRect(box, radius, radius, fill)
            canvas.drawRoundRect(box, radius, radius, stroke)
            canvas.save()
            canvas.translate(left + pad, top + (boxHeight - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
