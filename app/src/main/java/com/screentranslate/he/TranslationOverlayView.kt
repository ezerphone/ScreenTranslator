package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max

/**
 * Full-screen overlay that draws each translation on top of the original text,
 * at the same screen position the OCR found it.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    private val blocks: List<TranslatedBlock>,
    private val onDismiss: () -> Unit,
    private val onCopy: (String) -> Unit,
) : ViewGroup(context) {

    private val density = resources.displayMetrics.density
    private val screenLocation = IntArray(2)

    init {
        setBackgroundColor(0x33000000)
        setOnClickListener { onDismiss() }
        blocks.forEach { addView(makeLabel(it)) }
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun makeLabel(block: TranslatedBlock) = TextView(context).apply {
        text = block.translated
        setTextColor(Color.WHITE)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        val pad = (3 * density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        background = GradientDrawable().apply {
            setColor(0xF2202124.toInt())
            cornerRadius = 6 * density
        }
        // Size the text like the original line height, but keep it readable.
        val lineHeight = block.rect.height().toFloat() / max(block.lineCount, 1)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (lineHeight * 0.68f).coerceIn(sp(12f), sp(24f)))
        setOnClickListener { onDismiss() }
        setOnLongClickListener {
            onCopy(block.translated)
            true
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        val minWidth = (56 * density).toInt()
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
        // OCR boxes are in screen coordinates; convert to this window's coordinates.
        getLocationOnScreen(screenLocation)
        for (i in 0 until childCount) {
            val rect = blocks[i].rect
            val child = getChildAt(i)
            var left = rect.left - screenLocation[0]
            val top = rect.top - screenLocation[1]
            // Keep labels that were widened to the minimum width inside the screen.
            if (left + child.measuredWidth > width) left = max(0, width - child.measuredWidth)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }
}
