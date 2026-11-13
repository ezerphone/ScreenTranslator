package com.screentranslate.he

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Translation screen for:
 *  - "תרגם לעברית" in the text-selection menu of any app (ACTION_PROCESS_TEXT)
 *  - text or images shared to the app (ACTION_SEND)
 *  - photos from the in-app camera (ACTION_CAMERA; the photo lives only in memory)
 * Images show the translation drawn on the image and can be saved with it.
 */
class TranslateActivity : ComponentActivity() {

    companion object {
        const val ACTION_CAMERA = "com.screentranslate.he.TRANSLATE_CAMERA"
        private const val MAX_TEXT = 6000
        private const val MAX_IMAGE_SIDE = 2400
    }

    private lateinit var progressRow: View
    private lateinit var statusText: TextView
    private lateinit var translatedText: TextView
    private lateinit var originalText: TextView
    private var translation = ""

    private var image: Bitmap? = null
    private var imageView: ImageTranslationView? = null
    private var blocks: List<TranslatedBlock> = emptyList()
    private var imageJob: Job? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveImage() else toast("צריך הרשאת אחסון כדי לשמור")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        progressRow = findViewById(R.id.progressRow)
        statusText = findViewById(R.id.statusText)
        translatedText = findViewById(R.id.translatedText)
        originalText = findViewById(R.id.originalText)

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<View>(R.id.copyButton).setOnClickListener { copy() }
        findViewById<View>(R.id.shareButton).setOnClickListener { share() }
        findViewById<View>(R.id.saveButton).setOnClickListener { onSaveClicked() }

        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                if (!readOnly) {
                    findViewById<View>(R.id.replaceButton).apply {
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
                    loadSharedImage(stream)
                } else {
                    translateText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
                }
            }
            ACTION_CAMERA -> {
                findViewById<View>(R.id.retakeButton).apply {
                    visibility = View.VISIBLE
                    setOnClickListener { finish() } // back to the camera
                }
                val photo = CapturedImage.take()
                if (photo == null) showError("התמונה לא נמצאה, צלם שוב") else showImage(photo)
            }
            else -> showError("אין מה לתרגם")
        }
    }

    // ------------------------------------------------------------------ text

    private fun translateText(raw: String) {
        var text = raw.trim()
        if (text.isEmpty()) {
            showError("אין טקסט לתרגום")
            return
        }
        if (text.length > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT)
            toast("הטקסט ארוך – מתורגמים רק $MAX_TEXT התווים הראשונים")
        }
        showOriginal(text)
        if (Scripts.detect(text) == SourceLang.NONE) {
            showError("לא נמצא טקסט לתרגום")
            return
        }
        lifecycleScope.launch {
            try {
                showTranslation(TranslationEngine.translateText(this@TranslateActivity, text) { statusText.text = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("שגיאה בתרגום: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ images

    private fun loadSharedImage(uri: Uri) {
        statusText.text = "פותח את התמונה…"
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(uri) }
            if (bitmap == null) showError("לא הצלחתי לפתוח את התמונה") else showImage(bitmap)
        }
    }

    private fun showImage(bitmap: Bitmap) {
        image = bitmap
        val view = ImageTranslationView(this).apply {
            this.bitmap = bitmap
            setOnClickListener { showTranslation = !showTranslation }
            contentDescription = "התמונה עם התרגום"
        }
        imageView = view
        findViewById<FrameLayout>(R.id.imageHolder).apply {
            visibility = View.VISIBLE
            removeAllViews()
            addView(view)
        }
        translateImage()
    }

    private fun translateImage() {
        val bitmap = image ?: return
        imageJob?.cancel()
        blocks = emptyList()
        imageView?.blocks = emptyList()
        findViewById<View>(R.id.imageHint).visibility = View.GONE
        findViewById<View>(R.id.actions).visibility = View.GONE
        findViewById<View>(R.id.translationCard).visibility = View.GONE
        findViewById<View>(R.id.originalCard).visibility = View.GONE
        progressRow.visibility = View.VISIBLE
        (progressRow as ViewGroup).getChildAt(0).visibility = View.VISIBLE
        statusText.text = "מזהה טקסט בתמונה…"

        val language = Prefs.language(this)
        imageJob = lifecycleScope.launch {
            try {
                val found = TranslationEngine.translateImage(this@TranslateActivity, bitmap) {
                    statusText.text = it
                }
                if (found.isEmpty()) {
                    showError("לא נמצא טקסט ב${language.label}. את שפת המקור בוחרים במסך הבית.")
                    return@launch
                }
                blocks = found
                imageView?.blocks = found
                findViewById<View>(R.id.imageHint).visibility = View.VISIBLE
                findViewById<View>(R.id.saveButton).visibility = View.VISIBLE
                showOriginal(found.joinToString("\n") { it.original })
                showTranslation(found.joinToString("\n") { it.translated })
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

    private fun onSaveClicked() {
        if (ImageSaver.needsPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveImage()
        }
    }

    private fun saveImage() {
        val bitmap = image ?: return
        val found = blocks
        if (found.isEmpty()) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rendered = TranslationPainter.render(bitmap, found)
                    try {
                        ImageSaver.save(this@TranslateActivity, rendered)
                    } finally {
                        rendered.recycle()
                    }
                }
                toast("התמונה המתורגמת נשמרה בגלריה")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("השמירה נכשלה: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ shared UI

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
        (progressRow as ViewGroup).getChildAt(0).visibility = View.GONE // hide spinner
        statusText.text = message
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("תרגום", translation))
        if (Build.VERSION.SDK_INT < 33) toast("התרגום הועתק")
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, translation)
        }
        startActivity(Intent.createChooser(send, "שתף תרגום"))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        // Nothing is kept: the photo exists only while this screen is open.
        imageView?.bitmap = null
        image = null
    }
}
