package com.screentranslate.he

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

/**
 * Foreground service that owns the screen capture, the floating bubble and the translation overlay.
 */
class ScreenTranslateService : Service() {

    companion object {
        private const val BRAND = 0xFF0B7A5C.toInt()
        const val ACTION_START = "com.screentranslate.he.START"
        const val ACTION_STOP = "com.screentranslate.he.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("ScreenCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager
    private var engine: TranslationEngine? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private val imageLock = Any()
    private var latestImage: Image? = null // guarded by imageLock
    @Volatile private var frameCounter = 0L
    private var captureWidth = 0
    private var captureHeight = 0

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: View? = null
    private var busy = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture (e.g. from the status bar chip).
            mainHandler.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        uiContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        windowManager = uiContext.getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            handleStart(intent)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- start / stop

    private fun handleStart(intent: Intent) {
        // Android 14+ requires the foreground service to be running before getMediaProjection().
        startForegroundWithNotification()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return
        }

        releaseCapture() // Pressing "start" again replaces the old session.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            toast("לא ניתן להתחיל צילום מסך")
            stopSelf()
            return
        }
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        setupCaptureSurface()
        showBubble()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        removeOverlay()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        releaseCapture()
        engine?.close()
        engine = null
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "כפתור תרגום", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("מתרגם המסך פעיל")
            .setContentText("הקש על \"תרגם\" כדי לתרגם את המסך לעברית")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "עצור", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------------------------------------------------------------- screen capture

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    /**
     * Creates the mirror display once (Android 14 forbids creating a second one per projection)
     * and (re)attaches an ImageReader of the current screen size, e.g. after rotation.
     */
    private fun setupCaptureSurface() {
        val mp = projection ?: return
        val (width, height) = screenSize()
        val dpi = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            if (r !== imageReader) return@setOnImageAvailableListener
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = image
            }
            frameCounter++
        }, captureHandler)

        val oldReader = imageReader
        imageReader = reader
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "ScreenTranslator", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, captureHandler
            )
        } else {
            vd.resize(width, height, dpi)
            vd.surface = reader.surface
        }
        captureWidth = width
        captureHeight = height

        // Runs after any callback already queued for the old reader.
        captureHandler.post {
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            oldReader?.close()
        }
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        val reader = imageReader
        imageReader = null
        captureHandler.post {
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = null
            }
            reader?.close()
        }
    }

    /** Waits for a frame drawn after the bubble was hidden, then copies it. */
    private suspend fun captureFreshFrame(): Bitmap? {
        val start = frameCounter
        withTimeoutOrNull(800) {
            while (frameCounter == start) delay(16)
        }
        delay(60)
        return withContext(Dispatchers.Default) { grabBitmap() }
    }

    private fun grabBitmap(): Bitmap? {
        synchronized(imageLock) {
            val image = latestImage ?: return null
            return copyImage(image)
        }
    }

    private fun copyImage(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = (plane.rowStride - pixelStride * image.width) / pixelStride
            val buffer = plane.buffer.apply { rewind() }
            val padded = Bitmap.createBitmap(image.width + rowPadding, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
        } catch (e: IllegalStateException) {
            null // image was closed underneath us (rotation); caller shows a retry message
        }
    }

    // ---------------------------------------------------------------- translate

    private fun onBubbleTap() {
        if (busy) return
        if (overlay != null) {
            removeOverlay()
            return
        }
        val view = bubble ?: return
        busy = true
        scope.launch {
            try {
                if (screenSize() != (captureWidth to captureHeight)) setupCaptureSurface()
                view.visibility = View.INVISIBLE
                val bitmap = captureFreshFrame()
                view.visibility = View.VISIBLE
                if (bitmap == null) {
                    toast("לא התקבלה תמונת מסך, נסה שוב")
                    return@launch
                }
                setBubbleBusy(true)
                val translator = engine ?: TranslationEngine(applicationContext).also { engine = it }
                val blocks = translator.process(bitmap) { toast(it) }
                bitmap.recycle()
                if (blocks.isEmpty()) {
                    toast("לא נמצא טקסט באנגלית על המסך")
                } else {
                    showOverlay(blocks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("שגיאה בתרגום: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                bubble?.visibility = View.VISIBLE
                setBubbleBusy(false)
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- floating bubble

    private fun overlayFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /** Design C: a teal pill with the translate icon and the word "תרגם". */
    private fun showBubble() {
        if (bubble != null) return
        val density = resources.displayMetrics.density
        val icon = ContextCompat.getDrawable(uiContext, R.drawable.ic_translate)?.mutate()?.apply {
            val s = (22 * density).toInt()
            setBounds(0, 0, s, s)
        }
        val view = TextView(uiContext).apply {
            text = "תרגם"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (8 * density).toInt()
            setPadding((14 * density).toInt(), 0, (18 * density).toInt(), 0)
            minHeight = (52 * density).toInt()
            background = GradientDrawable().apply {
                setColor(BRAND)
                cornerRadius = 26 * density
            }
            elevation = 6 * density
        }
        val (_, screenHeight) = screenSize()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            (52 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            // Anchored to the right edge: x is the distance from the right.
            gravity = Gravity.TOP or Gravity.RIGHT
            x = (14 * density).toInt()
            y = (screenHeight * 0.4f).toInt()
        }
        view.setOnTouchListener(BubbleTouchListener())
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    private fun setBubbleBusy(isBusy: Boolean) {
        bubble?.apply {
            text = if (isBusy) "מתרגם…" else "תרגם"
            alpha = if (isBusy) 0.75f else 1f
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false

        private val longPress = Runnable {
            longPressed = true
            bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toast("מתרגם המסך כובה")
            stopSelf()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    mainHandler.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX - dx.toInt() // anchored right: moving right shrinks x
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!dragging && !longPressed) onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- translation overlay

    private fun showOverlay(blocks: List<TranslatedBlock>) {
        removeOverlay()
        val view = TranslationOverlayView(
            uiContext, blocks,
            onDismiss = { removeOverlay() },
            onCopy = { copyToClipboard(it) }
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags() or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("תרגום", text))
        if (Build.VERSION.SDK_INT < 33) toast("התרגום הועתק")
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
