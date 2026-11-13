package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen layer for choosing a part of the screen. A frame is already there: drag it to
 * move it, drag a corner or an edge to resize it, or drag anywhere outside to draw a new one.
 * Nothing happens when the finger is lifted; the round "translate" icon confirms, the X closes.
 * The result is in screen coordinates.
 */
@SuppressLint("ViewConstructor")
class SelectionOverlayView(
    context: Context,
    hint: String,
    confirmLabel: String,
    confirmIcon: Int,
    private val onSelected: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun dpi(value: Int) = (value * density).toInt()

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val sheet = ContextCompat.getColor(context, R.color.sheet)
    private val ink = ContextCompat.getColor(context, R.color.ink)

    private val frameView = FrameView()

    init {
        addView(frameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val hintView = TextView(context).apply {
            text = hint
            setTextColor(ink)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dpi(18), dpi(10), dpi(18), dpi(10))
            background = GradientDrawable().apply {
                setColor(sheet)
                cornerRadius = dp(22f)
            }
            elevation = dp(4f)
        }
        addView(hintView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpi(64)
        })

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            val size = dpi(64)
            addView(roundIconButton(context, confirmIcon, confirmLabel, primary = true) { confirm() },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = dpi(28) })
            addView(roundIconButton(context, R.drawable.ic_close, "ביטול") { onCancel() },
                LinearLayout.LayoutParams(size, size))
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dpi(20), 0, dpi(20), dpi(56))
        })
    }

    private fun confirm() {
        val box = frameView.box
        if (box.width() < dp(24f) || box.height() < dp(16f)) return
        val location = IntArray(2)
        frameView.getLocationOnScreen(location)
        onSelected(
            Rect(
                box.left.toInt() + location[0],
                box.top.toInt() + location[1],
                box.right.toInt() + location[0],
                box.bottom.toInt() + location[1],
            )
        )
    }

    /** Draws the dimmed screen, the frame and its handles, and handles the dragging. */
    private inner class FrameView : View(context) {
        val box = RectF()

        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
            color = accent
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = accent
        }
        private val hole = Path()

        /** coerceIn that never throws (on a very small screen the limits can cross). */
        private fun Float.clamp(lo: Float, hi: Float) = if (lo > hi) lo else coerceIn(lo, hi)

        // What the current drag changes.
        private var moving = false
        private var drawing = false
        private var dragLeft = false
        private var dragTop = false
        private var dragRight = false
        private var dragBottom = false
        private var lastX = 0f
        private var lastY = 0f
        private var anchorX = 0f
        private var anchorY = 0f

        private val minW get() = dp(56f)
        private val minH get() = dp(36f)
        private val grab get() = dp(28f) // how close to a corner or edge counts as grabbing it

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Start with a frame across the middle of the screen.
            val width = w * 0.84f
            val height = max(h * 0.24f, dp(120f))
            val left = (w - width) / 2f
            val top = h * 0.32f
            box.set(left, top, left + width, top + height)
        }

        override fun onDraw(canvas: Canvas) {
            val radius = dp(12f)
            hole.reset()
            hole.fillType = Path.FillType.EVEN_ODD
            hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            hole.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.drawPath(hole, dim)
            canvas.drawRoundRect(box, radius, radius, outline)

            // Corner handles, and smaller ones in the middle of each edge.
            val big = dp(11f)
            val small = dp(7f)
            for ((x, y) in listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)) {
                canvas.drawCircle(x, y, big, handleFill)
                canvas.drawCircle(x, y, big, handleStroke)
            }
            for ((x, y) in listOf(box.centerX() to box.top, box.centerX() to box.bottom, box.left to box.centerY(), box.right to box.centerY())) {
                canvas.drawCircle(x, y, small, handleFill)
                canvas.drawCircle(x, y, small, handleStroke)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x
                    lastY = y
                    val nearLeft = abs(x - box.left) < grab
                    val nearRight = abs(x - box.right) < grab
                    val nearTop = abs(y - box.top) < grab
                    val nearBottom = abs(y - box.bottom) < grab
                    val withinX = x > box.left - grab && x < box.right + grab
                    val withinY = y > box.top - grab && y < box.bottom + grab
                    dragLeft = nearLeft && withinY
                    dragRight = nearRight && withinY && !dragLeft
                    dragTop = nearTop && withinX
                    dragBottom = nearBottom && withinX && !dragTop
                    val onEdge = dragLeft || dragRight || dragTop || dragBottom
                    moving = !onEdge && box.contains(x, y)
                    drawing = !onEdge && !moving
                    anchorX = x
                    anchorY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    when {
                        drawing -> {
                            if (abs(x - anchorX) > dp(8f) || abs(y - anchorY) > dp(8f)) {
                                box.set(
                                    min(anchorX, x).coerceAtLeast(0f), min(anchorY, y).coerceAtLeast(0f),
                                    max(anchorX, x).coerceAtMost(w), max(anchorY, y).coerceAtMost(h)
                                )
                            }
                        }
                        moving -> {
                            val dx = (x - lastX).clamp(-box.left, w - box.right)
                            val dy = (y - lastY).clamp(-box.top, h - box.bottom)
                            box.offset(dx, dy)
                        }
                        else -> {
                            if (dragLeft) box.left = x.clamp(0f, box.right - minW)
                            if (dragRight) box.right = x.clamp(box.left + minW, w)
                            if (dragTop) box.top = y.clamp(0f, box.bottom - minH)
                            if (dragBottom) box.bottom = y.clamp(box.top + minH, h)
                        }
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drawing && (box.width() < minW || box.height() < minH)) {
                        // A tiny new frame is hard to grab: give it a usable size around the touch.
                        val cx = box.centerX()
                        val cy = box.centerY()
                        box.set(cx - minW, cy - minH, cx + minW, cy + minH)
                        box.offset(
                            (-box.left).coerceAtLeast(0f) - (box.right - width).coerceAtLeast(0f),
                            (-box.top).coerceAtLeast(0f) - (box.bottom - height).coerceAtLeast(0f)
                        )
                        invalidate()
                    }
                    moving = false
                    drawing = false
                    dragLeft = false; dragTop = false; dragRight = false; dragBottom = false
                }
            }
            return true
        }
    }
}
