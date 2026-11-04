package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

private const val TEAL = 0xFF0B7A5C.toInt()
private const val INK = 0xFF12302A.toInt()

/**
 * Full-screen overlay: each translation sits on top of the original text (shrunk to fit its
 * box, see [TranslationPainter]), plus a bottom bar with "copy all" and "close".
 * Tap closes; long-press on a translation copies it.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val blocks: List<TranslatedBlock>,
    private val onDismiss: () -> Unit,
    private val onCopy: (String) -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(0x40000000)
        addView(BlocksLayer(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            makeBottomBar(),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val side = dp(20)
                setMargins(side, 0, side, dp(56))
            }
        )
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun pill(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 25 * density
    }

    private fun makeBottomBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val copyAll = Button(context).apply {
            text = "העתק הכול"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            background = pill(TEAL)
            stateListAnimator = null
            setOnClickListener {
                onCopy(blocks.joinToString("\n") { it.translated })
                onDismiss()
            }
        }
        val close = Button(context).apply {
            text = "סגור"
            isAllCaps = false
            setTextColor(INK)
            textSize = 15f
            background = pill(Color.WHITE)
            stateListAnimator = null
            setOnClickListener { onDismiss() }
        }
        addView(copyAll, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
        addView(close, LinearLayout.LayoutParams(0, dp(50), 1f))
    }

    /** Draws the translations; OCR boxes are screen coordinates, shifted into this window. */
    private inner class BlocksLayer : View(this@TranslationOverlayView.context) {
        private val painter = TranslationPainter(
            density,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
        )
        private var placed: List<TranslationPainter.Placed> = emptyList()
        private val screenLocation = IntArray(2)

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onDismiss()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                placed.lastOrNull { it.box.contains(e.x, e.y) }?.let {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onCopy(it.block.translated)
                }
            }
        })

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            getLocationOnScreen(screenLocation)
            placed = painter.place(
                blocks, 1f, -screenLocation[0].toFloat(), -screenLocation[1].toFloat(), width.toFloat()
            )
            invalidate()
        }

        override fun onDraw(canvas: Canvas) = painter.draw(canvas, placed)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)
    }
}
