package com.screentranslate.he

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.screentranslate.translator.MarianTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

data class TranslatedBlock(
    val rect: Rect,
    val lineCount: Int,
    val original: String,
    val translated: String,
)

/**
 * Screenshot -> OCR (ML Kit, Latin) -> English->Hebrew translation (bundled model).
 * Everything runs on the phone; no internet is used.
 * Call from the main thread; heavy work is moved to background dispatchers.
 */
class TranslationEngine(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var translator: MarianTranslator? = null

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    private suspend fun translator(onStatus: (String) -> Unit): MarianTranslator {
        translator?.let { return it }
        if (!ModelFiles.isInstalled(context)) onStatus("מכין את מנוע התרגום (פעם אחת בלבד)…")
        val created = withContext(Dispatchers.IO) {
            MarianTranslator(ModelFiles.ensureInstalled(context))
        }
        translator = created
        return created
    }

    suspend fun process(bitmap: Bitmap, onStatus: (String) -> Unit): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

        class Found(val box: Rect, val lines: Int, val text: String)

        val blocks = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            // OCR breaks sentences into lines; rejoin them so the translator sees whole sentences.
            val text = block.lines.joinToString(" ") { it.text.trim() }.trim()
            if (text.count { it.isLetter() } < 2) null else Found(box, block.lines.size, text)
        }
        if (blocks.isEmpty()) return emptyList()

        val engine = translator(onStatus)
        return withContext(Dispatchers.Default) {
            blocks.map { found ->
                val translated = cache.getOrPut(found.text) {
                    engine.translate(prepare(found.text)).ifEmpty { found.text }
                }
                TranslatedBlock(found.box, found.lines, found.text, translated)
            }
        }
    }

    /** "SERVICE UPDATE" translates poorly; "Service update" translates well. */
    private fun prepare(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length > 3 && letters.all { it.isUpperCase() }) {
            val lower = text.lowercase()
            return lower.replaceFirstChar { it.uppercase() }
        }
        return text
    }

    fun close() {
        recognizer.close()
        translator?.close()
        translator = null
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
