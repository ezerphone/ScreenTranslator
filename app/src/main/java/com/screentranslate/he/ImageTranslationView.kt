package com.screentranslate.he

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * A photo or shared image with the Hebrew drawn over the original text, like Google Lens (same
 * rule as the screen overlay, see [TranslationPainter]). Tap toggles original / translation.
 */
class ImageTranslationView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            placed = emptyList()
            requestLayout()
            invalidate()
        }

    var blocks: List<TranslatedBlock> = emptyList()
        set(value) {
            field = value
            placed = emptyList()
            invalidate()
        }

    var showTranslation = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val painter = TranslationPainter(
        density,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 5.5f, resources.displayMetrics)
    )
    private var placed: List<TranslationPainter.Placed> = emptyList()
    private var placedWidth = 0
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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

        if (!showTranslation || blocks.isEmpty()) return
        if (placed.isEmpty() || placedWidth != width) {
            placed = painter.place(blocks, scale, 0f, 0f, width.toFloat())
            placedWidth = width
        }
        painter.draw(canvas, placed)
    }
}
