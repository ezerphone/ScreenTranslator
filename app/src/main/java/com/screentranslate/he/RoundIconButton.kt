package com.screentranslate.he

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * A round icon button for the floating windows, in the app's light/dark palette.
 * [primary] uses the main-button color; the [label] is read aloud and shown on long press.
 */
fun roundIconButton(
    context: Context,
    icon: Int,
    label: String,
    primary: Boolean = false,
    action: () -> Unit,
): ImageButton = ImageButton(context).apply {
    val density = context.resources.displayMetrics.density
    fun color(id: Int) = ContextCompat.getColor(context, id)
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(color(if (primary) R.color.on_inverse else R.color.on_circle))
    val pad = (14 * density).toInt()
    setPadding(pad, pad, pad, pad)
    scaleType = ImageView.ScaleType.FIT_CENTER
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(if (primary) R.color.inverse else R.color.circle))
    }
    elevation = 4 * density
    contentDescription = label
    tooltipText = label
    setOnClickListener { action() }
}
