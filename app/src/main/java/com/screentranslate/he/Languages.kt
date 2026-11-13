package com.screentranslate.he

import android.content.Context

/** Writing system of a text: decides the OCR joiner and is the fallback when detection is unsure. */
enum class SourceLang { ENGLISH, CHINESE, ARABIC, HEBREW, OTHER, NONE }

/** Which text recognizer reads screens and photos. */
enum class OcrMode {
    /** ML Kit Chinese model: reads Chinese and Latin (English) text. */
    LATIN_CHINESE,

    /** Tesseract: reads Arabic. */
    ARABIC,
}

/**
 * The source language, chosen once on the home screen and used everywhere: the screen button,
 * area and live translation, the camera, pictures and typed or shared text.
 */
enum class Language(val code: String, val label: String, val ocr: OcrMode) {
    ENGLISH("en", "אנגלית", OcrMode.LATIN_CHINESE),
    CHINESE("zh", "סינית", OcrMode.LATIN_CHINESE),
    ARABIC("ar", "ערבית", OcrMode.ARABIC),
}

/** The language code for a writing system ("en", "zh", "ar", "he"), or null when unclear. */
fun SourceLang.code(): String? = when (this) {
    SourceLang.ENGLISH -> "en"
    SourceLang.CHINESE -> "zh"
    SourceLang.ARABIC -> "ar"
    SourceLang.HEBREW -> "he"
    SourceLang.OTHER, SourceLang.NONE -> null
}

object Scripts {
    /** The language of [text], decided by which writing system most of its letters use. */
    fun detect(text: CharSequence): SourceLang {
        var han = 0
        var arabic = 0
        var hebrew = 0
        var latin = 0
        var other = 0
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
                else -> other++
            }
        }
        // A Chinese character carries about as much as a short English word.
        val scores = listOf(
            SourceLang.CHINESE to han * 3,
            SourceLang.ARABIC to arabic,
            SourceLang.HEBREW to hebrew,
            SourceLang.ENGLISH to latin,
            SourceLang.OTHER to other,
        )
        val best = scores.maxByOrNull { it.second } ?: return SourceLang.NONE
        return if (best.second < 2) SourceLang.NONE else best.first
    }

    fun isMostlyChinese(text: CharSequence) = detect(text) == SourceLang.CHINESE
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_LANGUAGE = "source_language"
    private const val KEY_OLD_OCR = "ocr_mode" // before v6: LATIN_CHINESE or ARABIC

    fun language(context: Context): Language {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        Language.entries.firstOrNull { it.name == saved }?.let { return it }
        return if (prefs.getString(KEY_OLD_OCR, null) == "ARABIC") Language.ARABIC else Language.ENGLISH
    }

    fun setLanguage(context: Context, language: Language) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language.name).apply()
    }
}
