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
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the Quick Settings tile: ask for consent and turn the button on right away. */
        const val EXTRA_AUTO_START = "auto_start"
    }

    private lateinit var bubbleSwitch: Switch
    private lateinit var toggleState: TextView
    private lateinit var statusText: TextView
    private var updatingSwitch = false
    private var modeChips: ModeChips? = null

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
                setSwitch(true)
                Toast.makeText(this, "הכפתור פעיל – עבור לאפליקציה שתרצה לתרגם", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                setSwitch(false)
                Toast.makeText(this, "צריך לאשר צילום מסך כדי לתרגם", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bubbleSwitch = findViewById(R.id.bubbleSwitch)
        toggleState = findViewById(R.id.toggleState)
        statusText = findViewById(R.id.statusText)

        bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) start() else stop()
        }
        findViewById<View>(R.id.toggleRow).setOnClickListener { bubbleSwitch.toggle() }
        findViewById<View>(R.id.cameraButton).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        modeChips = ModeChips(this, findViewById(R.id.modeLatin), findViewById(R.id.modeArabic))

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoStart(intent)
    }

    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            intent.removeExtra(EXTRA_AUTO_START)
            if (!ScreenTranslateService.isRunning) {
                setSwitch(true)
                start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun setSwitch(on: Boolean) {
        updatingSwitch = true
        bubbleSwitch.isChecked = on
        updatingSwitch = false
        toggleState.text = if (on) "פעיל" else "כבוי"
    }

    private fun refresh() {
        setSwitch(ScreenTranslateService.isRunning)
        modeChips?.refresh()
        if (!Settings.canDrawOverlays(this)) {
            statusText.visibility = View.VISIBLE
            statusText.text = "צריך לאשר \"הצגה מעל אפליקציות אחרות\" – זה יתבקש כשתפעיל"
        } else {
            statusText.visibility = View.GONE
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            setSwitch(false)
            Toast.makeText(
                this,
                "אפשר ל\"מתרגם מסך\" להציג מעל אפליקציות אחרות, ואז חזור והפעל שוב",
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
        setSwitch(false)
    }
}
