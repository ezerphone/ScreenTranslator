package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

private const val TEAL = 0xFF0B7A5C.toInt()
private const val INK = 0xFF12302A.toInt()

/**
 * Full-screen overlay (design C): each translation sits on top of the original text in a
 * white rounded card with a teal outline, plus a bottom bar with "copy all" and "close".
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
        setOnClickListener { onDismiss() }
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

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

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

    private fun makeLabel(block: TranslatedBlock) = TextView(context).apply {
        text = block.translated
        setTextColor(INK)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 14 * density
            setStroke(max(1, (1.5f * density).toInt()), TEAL)
        }
        elevation = 3 * density
        // Size the text like the original line height, but keep it readable.
        val lineHeight = block.rect.height().toFloat() / max(block.lineCount, 1)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineHeight * 0.68f).coerceIn(sp(12f), sp(24f)))
        setOnClickListener { onDismiss() }
        setOnLongClickListener {
            onCopy(block.translated)
            true
        }
    }

    /** Places labels at the OCR boxes (screen coordinates -> this window's coordinates). */
    private inner class BlocksLayer : ViewGroup(this@TranslationOverlayView.context) {
        private val screenLocation = IntArray(2)

        init {
            clipChildren = false
            blocks.forEach { addView(makeLabel(it)) }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
            val minWidth = dp(56)
            for (i in 0 until childCount) {
                val rect = blocks[i].rect
                val child = getChildAt(i)
                val width = max(rect.width(), minWidth)
                child.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                // At least cover the original text; grow downwards if the Hebrew needs more room.
                if (child.measuredHeight < rect.height()) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY)
                    )
                }
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            getLocationOnScreen(screenLocation)
            for (i in 0 until childCount) {
                val rect = blocks[i].rect
                val child = getChildAt(i)
                var left = rect.left - screenLocation[0]
                val top = rect.top - screenLocation[1]
                if (left + child.measuredWidth > width) left = max(0, width - child.measuredWidth)
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            }
        }
    }
}
