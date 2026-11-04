package com.screentranslate.he

import android.content.Context

/** Languages the app translates from (always into Hebrew). */
enum class SourceLang { ENGLISH, CHINESE, ARABIC, HEBREW, NONE }

/** Which text recognizer reads screens and photos. */
enum class OcrMode(val label: String) {
    /** ML Kit Chinese model: reads Chinese and Latin (English) text. */
    LATIN_CHINESE("אנגלית / סינית"),

    /** Tesseract: reads Arabic. */
    ARABIC("ערבית"),
}

object Scripts {
    /** The language of [text], decided by which writing system most of its letters use. */
    fun detect(text: CharSequence): SourceLang {
        var han = 0
        var arabic = 0
        var hebrew = 0
        var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.HEBREW -> hebrew++
                Character.UnicodeScript.LATIN -> latin++
                else -> Unit
            }
        }
        // A Chinese character carries about as much as a short English word.
        val scores = listOf(
            SourceLang.CHINESE to han * 3,
            SourceLang.ARABIC to arabic,
            SourceLang.HEBREW to hebrew,
            SourceLang.ENGLISH to latin,
        )
        val best = scores.maxByOrNull { it.second } ?: return SourceLang.NONE
        return if (best.second < 2) SourceLang.NONE else best.first
    }

    fun isMostlyChinese(text: CharSequence) = detect(text) == SourceLang.CHINESE
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_OCR = "ocr_mode"

    fun ocrMode(context: Context): OcrMode {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_OCR, OcrMode.LATIN_CHINESE.name)
        return OcrMode.entries.firstOrNull { it.name == name } ?: OcrMode.LATIN_CHINESE
    }

    fun setOcrMode(context: Context, mode: OcrMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_OCR, mode.name).apply()
    }

    fun toggleOcrMode(context: Context): OcrMode {
        val next = if (ocrMode(context) == OcrMode.ARABIC) OcrMode.LATIN_CHINESE else OcrMode.ARABIC
        setOcrMode(context, next)
        return next
    }
}
