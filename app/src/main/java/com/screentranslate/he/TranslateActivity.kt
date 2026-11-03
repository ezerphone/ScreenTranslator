package com.screentranslate.he

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Translation screen for:
 *  - "תרגם לעברית" in the text-selection menu of any app (ACTION_PROCESS_TEXT)
 *  - text shared to the app (ACTION_SEND text/plain)
 *  - images shared to the app (ACTION_SEND image/...), translated in place on the image
 */
class TranslateActivity : ComponentActivity() {

    companion object {
        private const val MAX_TEXT = 6000
        private const val MAX_IMAGE_SIDE = 2400
    }

    private lateinit var progressRow: View
    private lateinit var statusText: TextView
    private lateinit var translatedText: TextView
    private lateinit var originalText: TextView
    private var translation = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        progressRow = findViewById(R.id.progressRow)
        statusText = findViewById(R.id.statusText)
        translatedText = findViewById(R.id.translatedText)
        originalText = findViewById(R.id.originalText)

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copy() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { share() }

        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                if (!readOnly) {
                    findViewById<Button>(R.id.replaceButton).apply {
                        visibility = View.VISIBLE
                        setOnClickListener {
                            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, translation))
                            finish()
                        }
                    }
                }
                translateText(text)
            }
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (type.startsWith("image/") && stream != null) {
                    translateImage(stream)
                } else {
                    translateText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
                }
            }
            else -> showError("אין מה לתרגם")
        }
    }

    private fun translateText(raw: String) {
        var text = raw.trim()
        if (text.isEmpty()) {
            showError("אין טקסט לתרגום")
            return
        }
        if (text.length > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT)
            Toast.makeText(this, "הטקסט ארוך – מתורגמים רק $MAX_TEXT התווים הראשונים", Toast.LENGTH_LONG).show()
        }
        showOriginal(text)
        lifecycleScope.launch {
            try {
                val result = TranslationEngine.translateText(this@TranslateActivity, text) { statusText.text = it }
                showTranslation(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("שגיאה בתרגום: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun translateImage(uri: Uri) {
        statusText.text = "מזהה טקסט בתמונה…"
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decode(uri) }
                if (bitmap == null) {
                    showError("לא הצלחתי לפתוח את התמונה")
                    return@launch
                }
                val imageView = ImageTranslationView(this@TranslateActivity).apply {
                    this.bitmap = bitmap
                    setOnClickListener { showTranslation = !showTranslation }
                    contentDescription = "התמונה עם התרגום"
                }
                findViewById<FrameLayout>(R.id.imageHolder).apply {
                    visibility = View.VISIBLE
                    addView(imageView)
                }

                val blocks = TranslationEngine.translateImage(this@TranslateActivity, bitmap) {
                    statusText.text = it
                }
                if (blocks.isEmpty()) {
                    showError("לא נמצא טקסט באנגלית בתמונה")
                    return@launch
                }
                imageView.blocks = blocks
                findViewById<View>(R.id.imageHint).visibility = View.VISIBLE
                showOriginal(blocks.joinToString("\n") { it.original })
                showTranslation(blocks.joinToString("\n") { it.translated })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("שגיאה בתרגום: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Decodes a software (OCR-readable) bitmap, scaled down if very large. */
    private fun decode(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_IMAGE_SIDE) {
                    val f = MAX_IMAGE_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        null
    }

    private fun showOriginal(text: String) {
        originalText.text = text
        findViewById<View>(R.id.originalCard).visibility = View.VISIBLE
    }

    private fun showTranslation(text: String) {
        translation = text
        progressRow.visibility = View.GONE
        translatedText.text = text
        findViewById<View>(R.id.translationCard).visibility = View.VISIBLE
        findViewById<View>(R.id.actions).visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        progressRow.visibility = View.VISIBLE
        (progressRow as? android.view.ViewGroup)?.getChildAt(0)?.visibility = View.GONE // hide spinner
        statusText.text = message
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("תרגום", translation))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "התרגום הועתק", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, translation)
        }
        startActivity(Intent.createChooser(send, "שתף תרגום"))
    }
}
