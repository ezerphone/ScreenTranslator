package com.screentranslate.he

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class TranslatedBlock(
    val rect: Rect,
    val lineCount: Int,
    val original: String,
    val translated: String,
)

/**
 * Screenshot -> OCR -> language detection -> on-device translation to Hebrew.
 * Must be used from the main thread (ML Kit callbacks and the caches are not synchronized).
 */
class TranslationEngine {

    private val languageId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = HashMap<String, Translator>()
    private val readyModels = HashSet<String>()

    private var recognizer: TextRecognizer? = null
    private var recognizerScript: Script? = null

    private fun recognizerFor(script: Script): TextRecognizer {
        recognizer?.let { if (recognizerScript == script) return it }
        recognizer?.close()
        val created = when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
        recognizer = created
        recognizerScript = script
        return created
    }

    /** Language to assume when detection fails, based on the chosen script. */
    private fun fallbackLanguage(script: Script): String = when (script) {
        Script.LATIN -> TranslateLanguage.ENGLISH
        Script.CHINESE -> TranslateLanguage.CHINESE
        Script.JAPANESE -> TranslateLanguage.JAPANESE
        Script.KOREAN -> TranslateLanguage.KOREAN
        Script.DEVANAGARI -> TranslateLanguage.HINDI
    }

    private suspend fun detect(text: String): String? {
        val tag = languageId.identifyLanguage(text).await()
        if (tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        if (tag == "iw") return TranslateLanguage.HEBREW
        return TranslateLanguage.fromLanguageTag(tag)
    }

    suspend fun process(
        bitmap: Bitmap,
        script: Script,
        onStatus: (String) -> Unit,
    ): List<TranslatedBlock> {
        val result = recognizerFor(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        val joiner = if (script == Script.CHINESE || script == Script.JAPANESE) "" else " "

        class Found(val box: Rect, val lines: Int, val text: String)

        val blocks = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            // OCR breaks sentences into lines; rejoin them so the translator sees whole sentences.
            val text = block.lines.joinToString(joiner) { it.text.trim() }.trim()
            if (text.none { it.isLetter() }) null else Found(box, block.lines.size, text)
        }
        if (blocks.isEmpty()) return emptyList()

        val pageLanguage = detect(blocks.joinToString("\n") { it.text }) ?: fallbackLanguage(script)

        val out = ArrayList<TranslatedBlock>(blocks.size)
        for (found in blocks) {
            val text = found.text
            // Short snippets ("OK", "Menu") are detected unreliably, so they inherit the page language.
            val language = if (text.length >= 20) detect(text) ?: pageLanguage else pageLanguage
            if (language == TranslateLanguage.HEBREW) continue // already Hebrew
            val translated = translatorFor(language, onStatus).translate(text).await()
            out += TranslatedBlock(found.box, found.lines, text, translated)
        }
        return out
    }

    private suspend fun translatorFor(source: String, onStatus: (String) -> Unit): Translator {
        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.HEBREW)
                    .build()
            )
        }
        if (source !in readyModels) {
            val sourceReady = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(source).build()).await()
            val hebrewReady = modelManager.isModelDownloaded(
                TranslateRemoteModel.Builder(TranslateLanguage.HEBREW).build()
            ).await()
            if (!sourceReady || !hebrewReady) {
                onStatus("מוריד מודל תרגום (פעם אחת בלבד)…")
            }
            translator.downloadModelIfNeeded().await()
            readyModels += source
        }
        return translator
    }

    fun close() {
        recognizer?.close()
        languageId.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
