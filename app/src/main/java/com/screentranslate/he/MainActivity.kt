package com.screentranslate.he

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView

    private val scriptIds = mapOf(
        Script.LATIN to R.id.scriptLatin,
        Script.CHINESE to R.id.scriptChinese,
        Script.JAPANESE to R.id.scriptJapanese,
        Script.KOREAN to R.id.scriptKorean,
        Script.DEVANAGARI to R.id.scriptDevanagari,
    )

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
                Toast.makeText(this, "הבועה פעילה – עבור לאפליקציה שתרצה לתרגם", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "צריך לאשר הקלטת מסך כדי לתרגם", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        val group = findViewById<RadioGroup>(R.id.scriptGroup)
        group.check(scriptIds.getValue(Prefs.getScript(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            scriptIds.entries.firstOrNull { it.value == checkedId }?.let { Prefs.setScript(this, it.key) }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { start() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(
                Intent(this, ScreenTranslateService::class.java)
                    .setAction(ScreenTranslateService.ACTION_STOP)
            )
            statusText.postDelayed({ updateStatus() }, 300)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "אפשר ל\"מתרגם מסך\" להציג מעל אפליקציות אחרות, ואז חזור ולחץ שוב הפעל",
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

    private fun updateStatus() {
        statusText.text = when {
            !Settings.canDrawOverlays(this) -> "חסרה הרשאה: הצגה מעל אפליקציות אחרות"
            ScreenTranslateService.isRunning -> "● הבועה פעילה"
            else -> "הבועה כבויה"
        }
    }
}
