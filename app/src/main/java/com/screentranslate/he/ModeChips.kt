package com.screentranslate.he

import android.content.Context
import android.widget.Button

/** Two pill buttons choosing the OCR language (English/Chinese or Arabic), saved in [Prefs]. */
class ModeChips(
    private val context: Context,
    private val latin: Button,
    private val arabic: Button,
    private val onChanged: (OcrMode) -> Unit = {},
) {
    init {
        latin.setOnClickListener { select(OcrMode.LATIN_CHINESE) }
        arabic.setOnClickListener { select(OcrMode.ARABIC) }
        refresh()
    }

    private fun select(mode: OcrMode) {
        if (Prefs.ocrMode(context) == mode) return
        Prefs.setOcrMode(context, mode)
        refresh()
        onChanged(mode)
    }

    fun refresh() {
        val mode = Prefs.ocrMode(context)
        style(latin, mode == OcrMode.LATIN_CHINESE)
        style(arabic, mode == OcrMode.ARABIC)
    }

    private fun style(button: Button, on: Boolean) {
        button.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        button.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF12302A.toInt())
        button.isSelected = on
        button.contentDescription = button.text.toString() + if (on) ", נבחר" else ""
    }
}
