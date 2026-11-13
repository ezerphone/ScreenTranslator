package com.screentranslate.he

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Translation to Hebrew with Google ML Kit, for English, Chinese and Arabic. The language models
 * come inside the APK and are put in place on first launch ([LanguagePack]); nothing is ever
 * downloaded. English is built into ML Kit. Runs on the engine's single background thread.
 */
object Backend {

    private val SUPPORTED = setOf(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE, TranslateLanguage.ARABIC)

    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val models = RemoteModelManager.getInstance()

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    private fun translator(lang: String): Translator = translators.getOrPut(lang) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang)
                .setTargetLanguage(TranslateLanguage.HEBREW)
                .build()
        )
    }

    /** Makes [lang] ready; fails when its model is missing (the language pack was not installed). */
    fun prepare(lang: String) {
        if (lang !in SUPPORTED || lang in ready) return
        val needed = listOf(lang, TranslateLanguage.HEBREW).filter { it != TranslateLanguage.ENGLISH }
        val missing = needed.filterNot { Tasks.await(models.isModelDownloaded(model(it))) }
        check(missing.isEmpty()) {
            "שפות התרגום עוד לא הוכנו. סגור את האפליקציה ופתח אותה שוב."
        }
        ready += lang
    }

    /** Translates one sentence in [lang] to Hebrew (unchanged when [lang] is not supported). */
    fun translate(sentence: String, lang: String): String =
        if (lang !in SUPPORTED) sentence else Tasks.await(translator(lang).translate(sentence))
}
