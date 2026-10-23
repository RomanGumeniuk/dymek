package io.github.romangumeniuk.dymek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
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
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * Plywajacy przycisk nad innymi aplikacjami. Stukniecie: chowa przycisk, bierze ostatnia klatke ekranu
 * z MediaProjection, wysyla ja do Claude i pokazuje odpowiedz w dymku.
 */
class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var uiContext: Context
    private lateinit var wm: WindowManager

    // Zrzut ekranu: wszystko ponizej tylko na watku captureHandler
    private val captureThread = HandlerThread("dymek-zrzut").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var answerer: Answerer? = null

    private var button: TextView? = null
    private lateinit var buttonParams: WindowManager.LayoutParams
    private var bubble: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleShown = false
    private var busy = false

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
        wm = uiContext.getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startInForeground()
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = when {
            intent == null -> null
            Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else -> @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val callback = object : MediaProjection.Callback() {
            // uzytkownik zatrzymal udostepnianie ekranu z paska systemowego
            override fun onStop() = stopSelf()
        }
        mp.registerCallback(callback, main)
        projection = mp
        projectionCallback = callback

        val size = screenSize()
        val dpi = resources.displayMetrics.densityDpi
        captureHandler.post {
            val r = newReader(size)
            reader = r
            virtualDisplay = mp.createVirtualDisplay(
                "dymek", size.x, size.y, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler,
            )
        }

        answerer = Answerer.create(Settings(this))
        showButton()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        button?.let { wm.removeView(it) }
        button = null
        hideBubble()
        worker.shutdownNow()
        answerer?.close()
        answerer = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        captureHandler.post {
            latest?.close()
            latest = null
            virtualDisplay?.release()
            reader?.close()
        }
        captureThread.quitSafely()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // po obrocie ekranu zrzut ma miec nowe wymiary, a przycisk zostac na ekranie
        captureHandler.postDelayed({ resizeCapture() }, 300)
        main.postDelayed({ keepButtonOnScreen() }, 300)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dymek", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Dymek działa")
            .setContentText("Stuknij pomarańczowy przycisk, żeby zapytać Claude.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Wyłącz", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // --- zrzut ekranu (watek captureHandler) ---

    private fun newReader(size: Point): ImageReader =
        ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 3).apply {
            // trzymamy zawsze najnowsza klatke, starsze od razu zwalniamy
            setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                }
                if (img != null) {
                    latest?.close()
                    latest = img
                }
            }, captureHandler)
        }

    private fun resizeCapture() {
        val vd = virtualDisplay ?: return
        val old = reader ?: return
        val size = screenSize()
        if (old.width == size.x && old.height == size.y) return
        val r = newReader(size)
        vd.resize(size.x, size.y, resources.displayMetrics.densityDpi)
        vd.surface = r.surface
        latest?.close()
        latest = null
        reader = r
        old.close()
    }

    private fun grabBitmap(): Bitmap? {
        val img = latest ?: return null
        val plane = img.planes[0]
        val width = img.width
        val height = img.height
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (padded.width == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    // --- przycisk i dymek (watek glowny) ---

    private fun onTap() {
        if (busy) return
        busy = true
        hideBubble()
        // przycisk nie moze trafic na zrzut
        button?.visibility = View.INVISIBLE
        captureHandler.postDelayed({
            val bitmap = try {
                grabBitmap()
            } catch (e: RuntimeException) {
                null
            }
            main.post {
                button?.visibility = View.VISIBLE
                if (bitmap == null) {
                    showBubble("Nie udało się zrobić zrzutu ekranu.")
                    busy = false
                    return@post
                }
                showBubble("Myślę…")
                worker.execute {
                    val text = try {
                        answerer?.answer(bitmap) ?: "Dymek nie jest gotowy."
                    } catch (e: RuntimeException) {
                        "Błąd: ${e.message}"
                    } finally {
                        bitmap.recycle()
                    }
                    main.post {
                        if (button != null) showBubble(text)
                        busy = false
                    }
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun showButton() {
        val size = dp(56)
        val screen = screenSize()
        val view = TextView(uiContext).apply {
            text = "?"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = "Zapytaj Claude o pytanie z ekranu"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
        }
        buttonParams = overlayParams(size, size).apply {
            x = screen.x - size - dp(12)
            y = screen.y / 3
        }
        view.setOnTouchListener(DragTouch())
        wm.addView(view, buttonParams)
        button = view
    }

    private fun keepButtonOnScreen() {
        val view = button ?: return
        val screen = screenSize()
        buttonParams.x = buttonParams.x.coerceIn(0, max(0, screen.x - buttonParams.width))
        buttonParams.y = buttonParams.y.coerceIn(0, max(0, screen.y - buttonParams.height))
        wm.updateViewLayout(view, buttonParams)
        if (bubbleShown) positionBubble()
    }

    private fun showBubble(text: String) {
        val view = bubble ?: TextView(uiContext).apply {
            textSize = 16f
            setTextColor(0xFF1F1F1F.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF7FFFFFF.toInt())
                setStroke(dp(2), ACCENT)
            }
            // stukniecie zamyka dymek
            setOnClickListener { hideBubble() }
        }.also { bubble = it }
        view.maxWidth = (screenSize().x * 0.85f).toInt()
        view.text = text
        if (!bubbleShown) {
            bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            positionBubble()
            wm.addView(view, bubbleParams)
            bubbleShown = true
        } else {
            positionBubble()
        }
    }

    /** Dymek pod przyciskiem, a gdy sie nie miesci, nad nim. */
    private fun positionBubble() {
        val view = bubble ?: return
        val screen = screenSize()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val gap = dp(8)
        val below = buttonParams.y + buttonParams.height + gap
        bubbleParams.y = if (below + view.measuredHeight < screen.y - dp(48)) {
            below
        } else {
            max(0, buttonParams.y - gap - view.measuredHeight)
        }
        if (bubbleShown) wm.updateViewLayout(view, bubbleParams)
    }

    private fun hideBubble() {
        val view = bubble ?: return
        if (bubbleShown) {
            wm.removeView(view)
            bubbleShown = false
        }
    }

    /** Przeciaganie przycisku; krotkie stukniecie pyta Claude, przytrzymanie wylacza dymek. */
    private inner class DragTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            button?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            stopSelf()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = buttonParams.x
                    startY = buttonParams.y
                    moved = false
                    longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout() * 2L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        wm.updateViewLayout(v, buttonParams)
                        if (bubbleShown) positionBubble()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        v.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // --- pomocnicze ---

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Point(metrics.widthPixels, metrics.heightPixels)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "io.github.romangumeniuk.dymek.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL = "dymek"
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_DELAY_MS = 250L
        private const val ACCENT = 0xFFD97757.toInt()
    }
}
