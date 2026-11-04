package com.screentranslate.he

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.screentranslate.translator.MarianTranslator
import com.screentranslate.translator.ModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/** Text found by OCR, in the coordinates of the image it was found in. */
data class OcrBlock(val rect: Rect, val lineCount: Int, val text: String)

data class TranslatedBlock(
    val rect: Rect,
    val lineCount: Int,
    val original: String,
    val translated: String,
)

/**
 * App-wide engine, shared by the floating button, the camera, sharing and the text-selection menu.
 *
 * OCR: ML Kit's Chinese model (reads Chinese and English) or Tesseract (Arabic).
 * Translation: bundled OPUS-MT models. English goes straight to Hebrew; Chinese and Arabic go
 * through English (zh→en→he, ar→en→he), which tested clearly better than the direct models.
 * All model and Tesseract work runs on one background thread, so nothing is used concurrently.
 */
object TranslationEngine {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "translator") }
        .asCoroutineDispatcher()

    private var mlkit: TextRecognizer? = null
    private var tesseract: TessBaseAPI? = null

    /** At most two models in memory (a pivot needs two at once). */
    private val translators = object : LinkedHashMap<String, MarianTranslator>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarianTranslator>): Boolean {
            if (size > 2) {
                eldest.value.close()
                return true
            }
            return false
        }
    }

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    private val englishBreak = Regex("(?<=[.!?…])\\s+(?=[\"'“(\\[]?[A-Z0-9])")
    private val chineseBreak = Regex("(?<=[。！？；!?])")
    private val arabicBreak = Regex("(?<=[.!?؟…])\\s+")

    // ------------------------------------------------------------------ OCR

    private fun mlkit(): TextRecognizer = synchronized(this) {
        mlkit ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { mlkit = it }
    }

    suspend fun recognize(context: Context, bitmap: Bitmap, mode: OcrMode): List<OcrBlock> =
        when (mode) {
            OcrMode.LATIN_CHINESE -> recognizeLatinChinese(bitmap)
            OcrMode.ARABIC -> withContext(worker) { recognizeArabic(context, bitmap) }
        }

    private suspend fun recognizeLatinChinese(bitmap: Bitmap): List<OcrBlock> {
        val result = mlkit().process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.map { it.text.trim() }
            // OCR breaks sentences into lines; rejoin them (Chinese without spaces).
            val joiner = if (Scripts.isMostlyChinese(block.text)) "" else " "
            val text = lines.joinToString(joiner).trim()
            if (Scripts.detect(text) == SourceLang.NONE) null else OcrBlock(box, lines.size, text)
        }
    }

    private fun tesseract(context: Context): TessBaseAPI {
        tesseract?.let { return it }
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata").apply { mkdirs() }
        val file = File(data, "ara.traineddata")
        val assetSize = context.assets.openFd("tessdata/ara.traineddata").use { it.length }
        if (file.length() != assetSize) { // first run, or a new version of the data
            context.assets.open("tessdata/ara.traineddata").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        val api = TessBaseAPI()
        check(api.init(root.absolutePath, "ara", TessBaseAPI.OEM_LSTM_ONLY)) { "Arabic OCR failed to start" }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        tesseract = api
        return api
    }

    private fun recognizeArabic(context: Context, bitmap: Bitmap): List<OcrBlock> {
        val api = tesseract(context)
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        api.setImage(input)
        api.getUTF8Text() // runs recognition
        val blocks = ArrayList<OcrBlock>()
        val level = TessBaseAPI.PageIteratorLevel.RIL_PARA
        val iterator = api.getResultIterator()
        if (iterator != null) {
            try {
                iterator.begin()
                do {
                    val raw = iterator.getUTF8Text(level) ?: continue
                    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val text = lines.joinToString(" ")
                    val box = iterator.getBoundingRect(level)
                    if (iterator.confidence(level) < 45f || box == null || box.isEmpty) continue
                    if (Scripts.detect(text) == SourceLang.NONE) continue
                    blocks += OcrBlock(Rect(box), lines.size.coerceAtLeast(1), text)
                } while (iterator.next(level))
            } finally {
                iterator.delete()
            }
        }
        api.clear()
        if (input !== bitmap) input.recycle()
        return blocks
    }

    // ------------------------------------------------------------------ translation

    private class AssetModelSource(private val assets: AssetManager, private val dir: String) : ModelSource {
        override fun open(name: String): InputStream = assets.open("$dir/$name")

        override fun map(name: String): ByteBuffer =
            assets.openFd("$dir/$name").use { fd ->
                fd.createInputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
            }
    }

    /** Must run on [worker]. */
    private fun translator(context: Context, name: String): MarianTranslator =
        translators[name] ?: MarianTranslator(AssetModelSource(context.assets, "translator/$name"))
            .also { translators[name] = it }

    private fun modelsFor(lang: SourceLang): List<String> = when (lang) {
        SourceLang.ENGLISH -> listOf("en-he")
        SourceLang.CHINESE -> listOf("zh-en", "en-he")
        SourceLang.ARABIC -> listOf("ar-en", "en-he")
        SourceLang.HEBREW, SourceLang.NONE -> emptyList()
    }

    private fun langName(lang: SourceLang) = when (lang) {
        SourceLang.CHINESE -> "סינית"
        SourceLang.ARABIC -> "ערבית"
        else -> "אנגלית"
    }

    /** Loads the models [texts] need, telling the user when that takes a moment. */
    private suspend fun prepare(context: Context, texts: List<String>, onStatus: (String) -> Unit) {
        val langs = texts.map { Scripts.detect(it) }.distinct()
        for (lang in langs) {
            val missing = withContext(worker) { modelsFor(lang).filter { it !in translators } }
            if (missing.isEmpty()) continue
            withContext(Dispatchers.Main) { onStatus("טוען את מנוע התרגום מ${langName(lang)}…") }
            withContext(worker) { missing.forEach { translator(context, it) } }
        }
    }

    /** OCR + translation; boxes are shifted by ([offsetX], [offsetY]) into screen coordinates. */
    suspend fun translateImage(
        context: Context,
        bitmap: Bitmap,
        mode: OcrMode,
        offsetX: Int = 0,
        offsetY: Int = 0,
        onStatus: (String) -> Unit = {},
    ): List<TranslatedBlock> {
        val blocks = recognize(context, bitmap, mode)
        if (blocks.isEmpty()) return emptyList()
        prepare(context, blocks.map { it.text }, onStatus)
        return withContext(worker) {
            blocks.mapNotNull { b ->
                if (Scripts.detect(b.text) == SourceLang.HEBREW) return@mapNotNull null // already Hebrew
                val rect = Rect(b.rect).apply { offset(offsetX, offsetY) }
                TranslatedBlock(rect, b.lineCount, b.text, translateParagraphs(context, b.text))
            }
        }
    }

    /** Free text (shared or selected): any of the supported languages, paragraphs kept. */
    suspend fun translateText(context: Context, text: String, onStatus: (String) -> Unit = {}): String {
        prepare(context, text.split('\n'), onStatus)
        return withContext(worker) { translateParagraphs(context, text) }
    }

    private fun translateParagraphs(context: Context, text: String): String =
        text.split('\n').joinToString("\n") { paragraph ->
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) return@joinToString ""
            val lang = Scripts.detect(trimmed)
            val splitter = when (lang) {
                SourceLang.CHINESE -> chineseBreak
                SourceLang.ARABIC -> arabicBreak
                else -> englishBreak
            }
            splitter.split(trimmed).map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ") { translateSentence(context, it, lang) }
        }

    private fun translateSentence(context: Context, sentence: String, lang: SourceLang): String {
        val models = modelsFor(lang)
        if (models.isEmpty() || sentence.count { it.isLetter() } < 2 && lang != SourceLang.CHINESE) return sentence
        synchronized(cache) { cache[sentence] }?.let { return it }
        var current = sentence
        for (name in models) {
            val input = if (name == "en-he") fixCaps(current) else current
            current = translator(context, name).translate(input).ifEmpty { current }
        }
        synchronized(cache) { cache[sentence] = current }
        return current
    }

    /** "SERVICE UPDATE" translates poorly; "Service update" translates well. */
    private fun fixCaps(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length > 3 && letters.all { it.isUpperCase() }) {
            return text.lowercase().replaceFirstChar { it.uppercase() }
        }
        return text
    }
}
