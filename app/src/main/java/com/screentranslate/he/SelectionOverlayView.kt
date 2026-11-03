package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen layer for choosing a part of the screen: drag a rectangle, lift to confirm.
 * A tap without dragging cancels. The result is in screen coordinates.
 */
@SuppressLint("ViewConstructor")
class SelectionOverlayView(
    context: Context,
    private val hint: String,
    private val onSelected: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x8C000000.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0xFF2FD3A0.toInt()
    }
    private val hintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF12302A.toInt()
        textSize = 15 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var startX = 0f
    private var startY = 0f
    private val selection = RectF()
    private var dragging = false
    private val screenLocation = IntArray(2)
    private val hintBox = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!dragging) {
            canvas.drawRect(0f, 0f, w, h, dim)
        } else {
            canvas.drawRect(0f, 0f, w, selection.top, dim)
            canvas.drawRect(0f, selection.bottom, w, h, dim)
            canvas.drawRect(0f, selection.top, selection.left, selection.bottom, dim)
            canvas.drawRect(selection.right, selection.top, w, selection.bottom, dim)
            canvas.drawRoundRect(selection, 8 * density, 8 * density, outline)
        }

        // Hint pill near the top.
        val textWidth = hintText.measureText(hint)
        val top = 72 * density
        hintBox.set(
            w / 2 - textWidth / 2 - 18 * density, top,
            w / 2 + textWidth / 2 + 18 * density, top + 44 * density
        )
        canvas.drawRoundRect(hintBox, 22 * density, 22 * density, hintBackground)
        val baseline = hintBox.centerY() - (hintText.descent() + hintText.ascent()) / 2
        canvas.drawText(hint, w / 2, baseline, hintText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                selection.set(
                    min(startX, event.x), min(startY, event.y),
                    max(startX, event.x), max(startY, event.y)
                )
                dragging = selection.width() > 12 * density || selection.height() > 12 * density
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val big = selection.width() > 40 * density && selection.height() > 24 * density
                if (dragging && big) {
                    getLocationOnScreen(screenLocation)
                    onSelected(
                        Rect(
                            selection.left.toInt() + screenLocation[0],
                            selection.top.toInt() + screenLocation[1],
                            selection.right.toInt() + screenLocation[0],
                            selection.bottom.toInt() + screenLocation[1],
                        )
                    )
                } else {
                    onCancel()
                }
            }
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }
}
