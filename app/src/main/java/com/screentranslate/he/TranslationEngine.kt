package com.screentranslate.he

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Text found by OCR, in the coordinates of the image it was found in. */
data class OcrBlock(val rect: Rect, val lineCount: Int, val text: String)

data class TranslatedBlock(
    val rect: Rect,
    val lineCount: Int,
    val original: String,
    val translated: String,
    /** Colors and weight of the original text, so the translation can be drawn the same way. */
    val style: TextStyle = TextStyle.DEFAULT,
)

/**
 * App-wide engine, shared by the floating button, the camera, sharing and the text-selection menu.
 *
 * The source language is the one chosen on the home screen ([Prefs.language]); it decides the OCR:
 * ML Kit's Chinese model (reads Chinese and English) or Tesseract (Arabic).
 * Translation: [Backend] (Google ML Kit, models inside the APK, nothing is downloaded).
 * All translation and Tesseract work runs on one background thread, so nothing runs concurrently.
 */
object TranslationEngine {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "translator") }
        .asCoroutineDispatcher()

    private var mlkit: TextRecognizer? = null
    private var tesseract: TessBaseAPI? = null

    private val main = Handler(Looper.getMainLooper())

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

    /** Text in [bitmap], read with the recognizer for the chosen source language. */
    suspend fun recognize(context: Context, bitmap: Bitmap): List<OcrBlock> =
        when (Prefs.language(context).ocr) {
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

    private fun poster(onStatus: (String) -> Unit): (String) -> Unit = { msg -> main.post { onStatus(msg) } }

    /** OCR + translation; boxes are shifted by ([offsetX], [offsetY]) into screen coordinates. */
    suspend fun translateImage(
        context: Context,
        bitmap: Bitmap,
        offsetX: Int = 0,
        offsetY: Int = 0,
        onStatus: (String) -> Unit = {},
    ): List<TranslatedBlock> {
        val blocks = recognize(context, bitmap)
        if (blocks.isEmpty()) return emptyList()
        val status = poster(onStatus)
        val chosen = Prefs.language(context).code
        return withContext(worker) {
            blocks.mapNotNull { b ->
                if (Scripts.detect(b.text) == SourceLang.HEBREW) return@mapNotNull null // already Hebrew
                val style = TextColors.sample(bitmap, b.rect) // before the bitmap is recycled
                val rect = Rect(b.rect).apply { offset(offsetX, offsetY) }
                TranslatedBlock(rect, b.lineCount, b.text, translateParagraphs(b.text, chosen, status), style)
            }
        }
    }

    /** Free text (typed, shared or selected): paragraphs kept. */
    suspend fun translateText(context: Context, text: String, onStatus: (String) -> Unit = {}): String {
        val status = poster(onStatus)
        val chosen = Prefs.language(context).code
        return withContext(worker) { translateParagraphs(text, chosen, status) }
    }

    /**
     * Language of [text]: the chosen source language, unless the writing system clearly says
     * otherwise (an English button in a Chinese app is translated from English). English, Chinese
     * and Arabic each use their own script, so the script is reliable even for one word.
     */
    private fun languageOf(text: String, chosen: String): String = Scripts.detect(text).code() ?: chosen

    @Suppress("UNUSED_PARAMETER") // kept for status messages from a slow first load
    private fun translateParagraphs(
        text: String,
        chosen: String,
        status: (String) -> Unit,
    ): String = text.split('\n').joinToString("\n") { paragraph ->
        val trimmed = paragraph.trim()
        if (trimmed.isEmpty()) return@joinToString ""
        val lang = languageOf(trimmed, chosen)
        if (lang == "he") return@joinToString trimmed
        Backend.prepare(lang)
        val splitter = when (lang) {
            "zh" -> chineseBreak
            "ar" -> arabicBreak
            else -> englishBreak
        }
        splitter.split(trimmed).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { translateSentence(it, lang) }
    }

    private fun translateSentence(sentence: String, lang: String): String {
        if (lang != "zh" && sentence.count { it.isLetter() } < 2) return sentence
        val key = "$lang|$sentence"
        synchronized(cache) { cache[key] }?.let { return it }
        val input = if (lang == "en") fixCaps(sentence) else sentence
        val translated = Backend.translate(input, lang).ifEmpty { sentence }
        synchronized(cache) { cache[key] = translated }
        return translated
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
