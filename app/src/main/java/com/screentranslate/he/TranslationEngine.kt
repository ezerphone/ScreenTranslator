package com.screentranslate.he

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.screentranslate.translator.MarianTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** Text found by OCR, in the coordinates of the image it was found in. */
data class OcrBlock(val rect: Rect, val lineCount: Int, val text: String)

data class TranslatedBlock(
    val rect: Rect,
    val lineCount: Int,
    val original: String,
    val translated: String,
)

/**
 * App-wide translation engine: OCR (ML Kit, Latin) + bundled English->Hebrew model.
 * Shared by the floating button, the share screen and the text-selection menu, so the
 * model is loaded only once per process. Everything runs on the phone.
 */
object TranslationEngine {

    private var recognizer: TextRecognizer? = null
    private var translator: MarianTranslator? = null
    private val loadLock = Mutex()

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    private val sentenceBreak = Regex("(?<=[.!?…])\\s+(?=[\"'“(\\[]?[A-Z0-9])")

    private fun recognizer(): TextRecognizer = synchronized(this) {
        recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    }

    private suspend fun translator(context: Context, onStatus: (String) -> Unit): MarianTranslator {
        translator?.let { return it }
        return loadLock.withLock {
            translator ?: run {
                if (!ModelFiles.isInstalled(context)) onStatus("מכין את מנוע התרגום (פעם אחת בלבד)…")
                withContext(Dispatchers.IO) {
                    MarianTranslator(ModelFiles.ensureInstalled(context.applicationContext))
                }.also { translator = it }
            }
        }
    }

    /** OCR. Blocks with fewer than two letters (numbers, icons) are dropped. */
    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val result = recognizer().process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            // OCR breaks sentences into lines; rejoin them so the translator sees whole sentences.
            val text = block.lines.joinToString(" ") { it.text.trim() }.trim()
            if (text.count { it.isLetter() } < 2) null else OcrBlock(box, block.lines.size, text)
        }
    }

    /** OCR + translation; boxes are shifted by ([offsetX], [offsetY]) into screen coordinates. */
    suspend fun translateImage(
        context: Context,
        bitmap: Bitmap,
        offsetX: Int = 0,
        offsetY: Int = 0,
        onStatus: (String) -> Unit = {},
    ): List<TranslatedBlock> {
        val blocks = recognize(bitmap)
        if (blocks.isEmpty()) return emptyList()
        val engine = translator(context, onStatus)
        return withContext(Dispatchers.Default) {
            blocks.map { b ->
                val rect = Rect(b.rect).apply { offset(offsetX, offsetY) }
                TranslatedBlock(rect, b.lineCount, b.text, translateWith(engine, b.text))
            }
        }
    }

    /** Free text (shared or selected): keeps paragraphs, translates sentence by sentence. */
    suspend fun translateText(context: Context, text: String, onStatus: (String) -> Unit = {}): String {
        val engine = translator(context, onStatus)
        return withContext(Dispatchers.Default) { translateWith(engine, text) }
    }

    private fun translateWith(engine: MarianTranslator, text: String): String =
        text.split('\n').joinToString("\n") { paragraph ->
            if (paragraph.isBlank()) {
                ""
            } else {
                sentenceBreak.split(paragraph.trim()).joinToString(" ") { sentence ->
                    translateSentence(engine, sentence)
                }
            }
        }

    private fun translateSentence(engine: MarianTranslator, sentence: String): String {
        if (sentence.count { it.isLetter() } < 2) return sentence
        synchronized(cache) { cache[sentence] }?.let { return it }
        val translated = engine.translate(prepare(sentence)).ifEmpty { sentence }
        synchronized(cache) { cache[sentence] = translated }
        return translated
    }

    /** "SERVICE UPDATE" translates poorly; "Service update" translates well. */
    private fun prepare(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length > 3 && letters.all { it.isUpperCase() }) {
            return text.lowercase().replaceFirstChar { it.uppercase() }
        }
        return text
    }
}

/** Copies the bundled model from the APK to internal storage once (ONNX Runtime needs real files). */
object ModelFiles {
    private const val ASSET_DIR = "translator"

    private fun targetDir(context: Context): File {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return File(context.filesDir, "model-${info.lastUpdateTime}")
    }

    fun isInstalled(context: Context) = File(targetDir(context), ".complete").exists()

    @Synchronized
    fun ensureInstalled(context: Context): File {
        val dir = targetDir(context)
        if (File(dir, ".complete").exists()) return dir

        // Remove copies from older app versions.
        context.filesDir.listFiles()?.filter { it.name.startsWith("model-") }?.forEach { it.deleteRecursively() }

        val tmp = File(context.filesDir, "model-tmp").apply { deleteRecursively(); mkdirs() }
        val names = context.assets.list(ASSET_DIR).orEmpty()
        check(names.isNotEmpty()) { "מודל התרגום חסר באפליקציה" }
        for (name in names) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(tmp, name).outputStream().use { input.copyTo(it, 1 shl 16) }
            }
        }
        File(tmp, ".complete").writeText("ok")
        check(tmp.renameTo(dir)) { "Could not install the translation model" }
        return dir
    }
}
