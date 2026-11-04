package com.screentranslate.he

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max

/**
 * Foreground service that owns the screen capture and every floating window:
 * the "תרגם" button, its menu, the area selector, the translation overlay and the live panel.
 */
class ScreenTranslateService : Service() {

    companion object {
        private const val BRAND = 0xFF0B7A5C.toInt()
        private const val INK = 0xFF12302A.toInt()
        const val ACTION_START = "com.screentranslate.he.START"
        const val ACTION_STOP = "com.screentranslate.he.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 1
        private const val LIVE_INTERVAL_MS = 1200L

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
    private val density get() = resources.displayMetrics.density

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
    private var menu: View? = null
    private var selector: View? = null
    private var livePanel: View? = null
    private var liveText: TextView? = null
    private var liveFrame: View? = null
    private var liveJob: Job? = null
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
        updateTile()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        removeWindow(overlay)
        removeWindow(menu)
        removeWindow(selector)
        removeWindow(livePanel)
        removeWindow(liveFrame)
        removeWindow(bubble)
        overlay = null; menu = null; selector = null; livePanel = null; liveFrame = null; bubble = null
        releaseCapture()
        captureThread.quitSafely()
        updateTile()
        super.onDestroy()
    }

    private fun updateTile() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, TranslateTileService::class.java))
        }
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
            .setContentText("הקש על \"תרגם\" · לחיצה ארוכה לעוד אפשרויות")
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

    private fun ensureCaptureSize() {
        if (screenSize() != (captureWidth to captureHeight)) setupCaptureSurface()
    }

    /** Waits for a frame drawn after our windows were hidden, then copies it. */
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

    /** Crops [full] to [area] (screen coordinates), clamped to the image; recycles [full]. */
    private fun cropAndRecycle(full: Bitmap, area: Rect): Bitmap? {
        val r = Rect(area)
        if (!r.intersect(0, 0, full.width, full.height) || r.width() < 8 || r.height() < 8) {
            full.recycle()
            return null
        }
        val part = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        if (part !== full) full.recycle()
        return part
    }

    // ---------------------------------------------------------------- translate (full / area)

    private fun onBubbleTap() {
        if (overlay != null) {
            removeOverlay()
            return
        }
        translateArea(null)
    }

    private fun translateArea(area: Rect?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                ensureCaptureSize()
                bubble?.visibility = View.INVISIBLE
                val full = captureFreshFrame()
                bubble?.visibility = View.VISIBLE
                if (full == null) {
                    toast("לא התקבלה תמונת מסך, נסה שוב")
                    return@launch
                }
                setBubbleBusy(true)
                val bitmap = if (area == null) full else cropAndRecycle(full, area)
                if (bitmap == null) {
                    toast("האזור שנבחר ריק")
                    return@launch
                }
                val blocks = TranslationEngine.translateImage(
                    this@ScreenTranslateService, bitmap, Prefs.ocrMode(this@ScreenTranslateService),
                    offsetX = area?.left?.coerceAtLeast(0) ?: 0,
                    offsetY = area?.top?.coerceAtLeast(0) ?: 0,
                ) { toast(it) }
                bitmap.recycle()
                if (blocks.isEmpty()) {
                    toast("לא נמצא טקסט ב${Prefs.ocrMode(this@ScreenTranslateService).label}")
                } else {
                    showOverlay(blocks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("שגיאה בתרגום: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                bubble?.visibility = if (liveJob == null) View.VISIBLE else View.GONE
                setBubbleBusy(false)
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- area selection

    private enum class AreaMode { TRANSLATE, LIVE }

    private fun startSelection(mode: AreaMode) {
        removeOverlay()
        removeWindow(selector)
        bubble?.visibility = View.GONE
        val hint = if (mode == AreaMode.LIVE) "סמן את האזור לתרגום חי" else "גרור אצבע כדי לסמן אזור"
        val view = SelectionOverlayView(
            uiContext, hint,
            onSelected = { rect ->
                closeSelector()
                if (mode == AreaMode.LIVE) startLive(rect) else translateArea(rect)
            },
            onCancel = {
                closeSelector()
                bubble?.visibility = View.VISIBLE
            }
        )
        windowManager.addView(view, fullScreenParams(touchable = true))
        selector = view
    }

    private fun closeSelector() {
        removeWindow(selector)
        selector = null
    }

    // ---------------------------------------------------------------- live mode

    private fun startLive(area: Rect) {
        stopLive(showBubble = false)
        bubble?.visibility = View.GONE
        showLiveWindows(area)
        liveJob = scope.launch {
            var lastText: String? = null
            delay(250) // let the selector disappear from the frames
            while (isActive) {
                try {
                    ensureCaptureSize()
                    val full = withContext(Dispatchers.Default) { grabBitmap() }
                    val part = full?.let { cropAndRecycle(it, area) }
                    if (part != null) {
                        val blocks = TranslationEngine.recognize(
                            this@ScreenTranslateService, part, Prefs.ocrMode(this@ScreenTranslateService)
                        )
                        part.recycle()
                        val text = blocks.joinToString("\n") { it.text }
                        if (text != lastText) {
                            lastText = text
                            liveText?.text = if (text.isBlank()) {
                                "ממתין לטקסט…"
                            } else {
                                TranslationEngine.translateText(this@ScreenTranslateService, text) { toast(it) }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    liveText?.text = "שגיאה: ${e.message ?: e.javaClass.simpleName}"
                }
                delay(LIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopLive(showBubble: Boolean = true) {
        liveJob?.cancel()
        liveJob = null
        removeWindow(livePanel)
        removeWindow(liveFrame)
        livePanel = null
        liveFrame = null
        liveText = null
        if (showBubble) bubble?.visibility = View.VISIBLE
    }

    /** A teal frame around the watched area, and the translation panel outside it. */
    private fun showLiveWindows(area: Rect) {
        val (screenW, screenH) = screenSize()
        val inset = (4 * density).toInt()

        val frame = View(uiContext).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 10 * density
                setStroke((2 * density).toInt(), 0xFF2FD3A0.toInt(), 10 * density, 6 * density)
            }
        }
        val frameParams = WindowManager.LayoutParams(
            area.width() + inset * 2, area.height() + inset * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = area.left - inset
            y = area.top - inset
            applyFullScreenInsets()
        }
        windowManager.addView(frame, frameParams)
        liveFrame = frame

        val text = TextView(uiContext).apply {
            text = "מתחיל תרגום חי…"
            setTextColor(INK)
            textSize = 17f
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(3 * density, 1f)
        }
        val stop = Button(uiContext).apply {
            this.text = "עצור"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(BRAND)
                cornerRadius = 20 * density
            }
            setOnClickListener { stopLive() }
        }
        val panel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 20 * density
                setStroke((1.5f * density).toInt(), BRAND)
            }
            elevation = 6 * density
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                stop,
                LinearLayout.LayoutParams((72 * density).toInt(), (44 * density).toInt()).apply {
                    marginStart = (10 * density).toInt()
                }
            )
        }

        // Below the area if there is room, otherwise above it; never over it.
        val margin = (16 * density).toInt()
        val panelSpace = (170 * density).toInt()
        val below = screenH - area.bottom > panelSpace
        val panelParams = WindowManager.LayoutParams(
            screenW - margin * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = margin
            if (below) {
                gravity = Gravity.TOP or Gravity.LEFT
                y = area.bottom + inset * 3
            } else {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                y = max(0, screenH - area.top + inset * 3)
            }
            applyFullScreenInsets()
        }
        windowManager.addView(panel, panelParams)
        livePanel = panel
        liveText = text
    }

    // ---------------------------------------------------------------- floating button + menu

    private fun overlayFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun WindowManager.LayoutParams.applyFullScreenInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun fullScreenParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        overlayFlags() or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        applyFullScreenInsets()
    }

    /** Design C: a teal pill with the translate icon and the word "תרגם". */
    private fun showBubble() {
        if (bubble != null) return
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

    /** Long-press menu next to the button. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        closeMenu()
        removeOverlay()
        fun item(label: String, primary: Boolean, action: () -> Unit) = Button(uiContext).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else INK)
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(if (primary) BRAND else 0xFFF1F7F4.toInt())
                cornerRadius = 22 * density
            }
            setOnClickListener {
                closeMenu()
                action()
            }
        }
        val gap = (8 * density).toInt()
        val itemHeight = (46 * density).toInt()
        val card = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), 0xFFDCE9E3.toInt())
            }
            elevation = 8 * density
            val lp = { LinearLayout.LayoutParams((200 * density).toInt(), itemHeight).apply { bottomMargin = gap } }
            addView(item("תרגום אזור", primary = true) { startSelection(AreaMode.TRANSLATE) }, lp())
            addView(item("תרגום חי", primary = false) { startSelection(AreaMode.LIVE) }, lp())
            addView(item("שפה: ${Prefs.ocrMode(uiContext).label}", primary = false) {
                toast("השפה במסך: ${Prefs.toggleOcrMode(uiContext).label}")
            }, lp())
            addView(item("כיבוי", primary = false) {
                toast("מתרגם המסך כובה")
                stopSelf()
            }, LinearLayout.LayoutParams((200 * density).toInt(), itemHeight))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu()
                false
            }
        }
        val anchor = bubbleParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = anchor?.x ?: 0
            val (_, screenH) = screenSize()
            val menuHeight = (250 * density).toInt()
            val bubbleY = anchor?.y ?: 0
            // Below the button, or above it when near the bottom.
            y = if (bubbleY + (60 * density).toInt() + menuHeight < screenH) {
                bubbleY + (60 * density).toInt()
            } else {
                max(0, bubbleY - menuHeight)
            }
        }
        windowManager.addView(card, params)
        menu = card
    }

    private fun closeMenu() {
        removeWindow(menu)
        menu = null
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
            showMenu()
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
                    mainHandler.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                        closeMenu()
                    }
                    if (dragging) {
                        params.x = startX - dx.toInt() // anchored right: moving right shrinks x
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!dragging && !longPressed) {
                        if (menu != null) closeMenu() else onBubbleTap()
                    }
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
        windowManager.addView(view, fullScreenParams(touchable = true))
        overlay = view
    }

    private fun removeOverlay() {
        removeWindow(overlay)
        overlay = null
    }

    private fun removeWindow(view: View?) {
        view?.let { runCatching { windowManager.removeView(it) } }
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
