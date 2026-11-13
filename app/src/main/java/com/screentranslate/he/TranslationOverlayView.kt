package com.screentranslate.he

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay: each translation replaces the original text in place, in its colors and
 * sized to the room it had (see [TranslationPainter]). The bottom bar opens "all the text" — every
 * translation from the screen in reading order, in one scrollable window — copies all, or closes.
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
    private fun dp(value: Int) = (value * density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val bottomBar: View
    private var allTextSheet: View? = null

    /** The translations in reading order: rows top to bottom, each row in its writing direction. */
    private val readingOrder: List<TranslatedBlock> by lazy {
        val rtl = blocks.count { Scripts.detect(it.original) == SourceLang.ARABIC } > blocks.size / 2
        val byTop = blocks.sortedBy { it.rect.top }
        val rows = ArrayList<MutableList<TranslatedBlock>>()
        for (b in byTop) {
            val row = rows.lastOrNull()
            // Same row when it starts before the row's first block is half over.
            if (row != null && b.rect.top < row[0].rect.top + max(1, min(row[0].rect.height(), b.rect.height())) / 2) {
                row.add(b)
            } else {
                rows.add(mutableListOf(b))
            }
        }
        rows.flatMap { row -> if (rtl) row.sortedByDescending { it.rect.right } else row.sortedBy { it.rect.left } }
    }

    private val allText: String get() = readingOrder.joinToString("\n\n") { it.translated }

    init {
        // No dimming: the translations replace the original text in place, like Google Lens.
        addView(BlocksLayer(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bottomBar = makeBottomBar()
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val side = dp(16)
                setMargins(side, 0, side, dp(48))
            }
        )
    }

    private fun icons(vararg buttons: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER
        for (button in buttons) {
            addView(button, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            })
        }
    }

    private fun makeBottomBar(): View = icons(
        roundIconButton(context, R.drawable.ic_all_text, "כל הטקסט", primary = true) { showAllText() },
        roundIconButton(context, R.drawable.ic_copy, "העתק הכול") {
            onCopy(allText)
            onDismiss()
        },
        roundIconButton(context, R.drawable.ic_close, "סגור") { onDismiss() },
    )

    // ---------------------------------------------------------------- all the text, in one window

    private fun showAllText() {
        if (allTextSheet != null) return
        bottomBar.visibility = View.GONE
        val ink = color(R.color.ink)

        val title = TextView(context).apply {
            text = "כל הטקסט מהמסך"
            setTextColor(ink)
            textSize = 18f
        }
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.ink_soft))
            background = null
            contentDescription = "סגור"
            setOnClickListener { hideAllText() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        val body = TextView(context).apply {
            text = allText
            setTextColor(ink)
            textSize = 18f
            setLineSpacing(4 * density, 1f)
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(context).apply {
            addView(body)
            isVerticalScrollBarEnabled = true
        }

        val actions = icons(
            roundIconButton(context, R.drawable.ic_copy, "העתק", primary = true) { onCopy(allText) },
            roundIconButton(context, R.drawable.ic_share, "שתף") { share(allText) },
        )

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(12), dp(16))
            background = GradientDrawable().apply {
                setColor(color(R.color.sheet))
                cornerRadii = floatArrayOf(28 * density, 28 * density, 28 * density, 28 * density, 0f, 0f, 0f, 0f)
            }
            elevation = 12 * density
            isClickable = true // touches on the window do not close the overlay
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
                marginEnd = dp(8)
            })
            addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(40)
                marginEnd = dp(8)
            })
        }
        val height = (resources.displayMetrics.heightPixels * 0.68f).toInt()
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
        allTextSheet = panel
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(Intent.createChooser(send, "שתף תרגום").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        onDismiss()
    }

    private fun hideAllText() {
        allTextSheet?.let { removeView(it) }
        allTextSheet = null
        bottomBar.visibility = View.VISIBLE
    }

    /** Draws the translations; OCR boxes are screen coordinates, shifted into this window. */
    private inner class BlocksLayer : View(this@TranslationOverlayView.context) {
        private val painter = TranslationPainter(
            density,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 5.5f, resources.displayMetrics)
        )
        private var placed: List<TranslationPainter.Placed> = emptyList()
        private val screenLocation = IntArray(2)

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (allTextSheet != null) hideAllText() else onDismiss()
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
