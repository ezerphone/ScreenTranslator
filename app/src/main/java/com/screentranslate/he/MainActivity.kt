package com.screentranslate.he

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen, laid out like Google Translate: type or paste text at the top and it is translated
 * as you type; below it the language pair; at the bottom a picture, the screen button and the camera.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the Quick Settings tile: ask for consent and turn the button on right away. */
        const val EXTRA_AUTO_START = "auto_start"

        private const val TYPING_PAUSE_MS = 500L
    }

    private lateinit var sourceText: EditText
    private lateinit var clearButton: View
    private lateinit var resultBox: View
    private lateinit var translatedText: TextView
    private lateinit var resultStatus: TextView
    private lateinit var resultActions: View
    private lateinit var sourceLanguage: Button
    private lateinit var screenButton: ImageButton
    private lateinit var screenState: TextView

    private var translateJob: Job? = null
    private var screenOn = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenTranslateService::class.java).apply {
                    action = ScreenTranslateService.ACTION_START
                    putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
                setScreenState(true)
                Toast.makeText(this, "הכפתור פעיל – עבור לאפליקציה שתרצה לתרגם", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                setScreenState(false)
                Toast.makeText(this, "צריך לאשר צילום מסך כדי לתרגם", Toast.LENGTH_LONG).show()
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, TranslateActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .setType("image/*")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sourceText = findViewById(R.id.sourceText)
        clearButton = findViewById(R.id.clearButton)
        resultBox = findViewById(R.id.resultBox)
        translatedText = findViewById(R.id.translatedText)
        resultStatus = findViewById(R.id.resultStatus)
        resultActions = findViewById(R.id.resultActions)
        sourceLanguage = findViewById(R.id.sourceLanguage)
        screenButton = findViewById(R.id.screenButton)
        screenState = findViewById(R.id.screenState)

        sourceText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onTextChanged(s?.toString().orEmpty())
        })
        clearButton.setOnClickListener { sourceText.text.clear() }
        findViewById<View>(R.id.copyButton).setOnClickListener { copyTranslation() }
        findViewById<View>(R.id.shareButton).setOnClickListener { shareTranslation() }
        findViewById<View>(R.id.helpButton).setOnClickListener { showHelp() }

        sourceLanguage.setOnClickListener { chooseLanguage() }
        screenButton.setOnClickListener { if (screenOn) stop() else start() }
        findViewById<View>(R.id.cameraButton).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        findViewById<View>(R.id.imageButton).setOnClickListener { pickImage.launch("image/*") }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleAutoStart(intent)
        installBundledLanguages()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoStart(intent)
    }

    override fun onResume() {
        super.onResume()
        setScreenState(ScreenTranslateService.isRunning)
        refreshSourceLanguage()
    }

    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            intent.removeExtra(EXTRA_AUTO_START)
            if (!ScreenTranslateService.isRunning) {
                setScreenState(true)
                start()
            }
        }
    }

    /** The languages packed inside the APK (assets/languages.zip) are installed on first launch. */
    private fun installBundledLanguages() {
        if (!LanguagePack.bundledPending(this)) return
        Toast.makeText(this, "מכין את שפות התרגום (פעם אחת)…", Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            try {
                val recorded = withContext(Dispatchers.IO) { LanguagePack.installBundled(applicationContext) }
                if (recorded) LanguagePack.restart(this@MainActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "הכנת שפות התרגום נכשלה", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- typed text

    private fun onTextChanged(text: String) {
        translateJob?.cancel()
        val trimmed = text.trim()
        clearButton.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (trimmed.isEmpty()) {
            resultBox.visibility = View.GONE
            translatedText.text = ""
            return
        }
        resultBox.visibility = View.VISIBLE
        translateJob = lifecycleScope.launch {
            delay(TYPING_PAUSE_MS) // wait until typing pauses
            resultStatus.text = "מתרגם…"
            try {
                val translation = TranslationEngine.translateText(this@MainActivity, trimmed) { resultStatus.text = it }
                translatedText.text = translation
                resultStatus.text = ""
                resultActions.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resultStatus.text = e.message ?: "התרגום נכשל"
            }
        }
    }

    private fun copyTranslation() {
        val text = translatedText.text?.toString().orEmpty()
        if (text.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("תרגום", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "התרגום הועתק", Toast.LENGTH_SHORT).show()
    }

    private fun shareTranslation() {
        val text = translatedText.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "שתף תרגום"))
    }

    // ---------------------------------------------------------------- language pair

    /** The source language, chosen only here and used everywhere (screen, camera, pictures, text). */
    private fun refreshSourceLanguage() {
        sourceLanguage.text = Prefs.language(this).label
        sourceLanguage.contentDescription = "שפת המקור: ${sourceLanguage.text}. הקש כדי לבחור"
    }

    private fun chooseLanguage() {
        val languages = Language.entries
        val current = languages.indexOf(Prefs.language(this))
        AlertDialog.Builder(this)
            .setTitle("לתרגם מ…")
            .setSingleChoiceItems(languages.map { it.label }.toTypedArray(), current) { dialog, which ->
                Prefs.setLanguage(this, languages[which])
                refreshSourceLanguage()
                dialog.dismiss()
                onTextChanged(sourceText.text?.toString().orEmpty()) // translate again
            }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("איך משתמשים")
            .setMessage(
                "• כתוב או הדבק טקסט למעלה – הוא מתורגם תוך כדי.\n" +
                    "• אנגלית, סינית וערבית לעברית, בלי אינטרנט.\n" +
                    "• את שפת המקור בוחרים כאן, בכפתור השפה – והיא קובעת בכל המקומות: מסך, מצלמה, תמונות וטקסט.\n\n" +
                    "תרגום מסך:\n" +
                    "• בחלון האישור של אנדרואיד בחר \"כל המסך\".\n" +
                    "• הקשה על \"תרגם\" – תרגום כל המסך.\n" +
                    "• לחיצה ארוכה על \"תרגם\" – תרגום אזור, תרגום חי, כיבוי.\n" +
                    "• אחרי תרגום: \"כל הטקסט\" – כל התרגום ברצף, בחלון אחד.\n\n" +
                    "עוד:\n" +
                    "• סימון טקסט בכל אפליקציה ← \"תרגם לעברית\".\n" +
                    "• שיתוף טקסט או תמונה ← \"תרגם לעברית\".\n" +
                    "• אפשר להוסיף כפתור \"תרגום\" להגדרות המהירות."
            )
            .setPositiveButton("הבנתי", null)
            .show()
    }

    // ---------------------------------------------------------------- screen button

    private fun setScreenState(on: Boolean) {
        screenOn = on
        val accent = ContextCompat.getColor(this, R.color.accent)
        // Off: the big white (dark mode) / black (light mode) button. On: filled with the accent.
        screenButton.backgroundTintList = if (on) ColorStateList.valueOf(accent) else null
        screenButton.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_inverse))
        screenState.text = if (on) "תרגום מסך · פעיל" else "תרגום מסך"
        screenState.setTextColor(if (on) accent else ContextCompat.getColor(this, R.color.ink_soft))
        screenButton.contentDescription = if (on) "כבה את תרגום המסך" else "הפעל תרגום מסך"
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            setScreenState(false)
            Toast.makeText(
                this,
                "אפשר ל\"תרגום\" להציג מעל אפליקציות אחרות, ואז חזור והפעל שוב",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCapture.launch(mpm.createScreenCaptureIntent())
    }

    private fun stop() {
        startService(
            Intent(this, ScreenTranslateService::class.java)
                .setAction(ScreenTranslateService.ACTION_STOP)
        )
        setScreenState(false)
    }
}
