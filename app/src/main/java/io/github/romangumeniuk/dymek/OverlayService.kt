package io.github.romangumeniuk.dymek

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
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
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Plywajacy przycisk nad innymi aplikacjami. Stukniecie: chowa przycisk, bierze ostatnia klatke ekranu
 * z MediaProjection, wysyla ja do AI i pokazuje pewna odpowiedz w dymku albo klika ja przez [TapService].
 */
class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var uiContext: Context
    private lateinit var wm: WindowManager
    private lateinit var settings: Settings
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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

    // Rozgrzewka polaczenia na osobnym watku, zeby nie blokowala pytania
    private val warmer: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastWarmAt = 0L
    private val keepWarm = object : Runnable {
        override fun run() {
            warmUp()
            main.postDelayed(this, KEEP_WARM_MS)
        }
    }

    private var button: TextView? = null
    private lateinit var buttonParams: WindowManager.LayoutParams
    private var bubble: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleShown = false
    private val targets = mutableListOf<Target>()
    private lateinit var zones: DropZones
    private var bubbleVersion = 0
    private var tapMark: View? = null
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
        settings = Settings(this)
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

        answerer = Answerer.create(settings)
        main.post(keepWarm)
        showButton()
        // wyglad przycisku zmienia sie od razu po zaznaczeniu opcji w ustawieniach
        settingsListener = settings.listen { styleButton() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        settingsListener?.let { settings.unlisten(it) }
        settingsListener = null
        button?.let { wm.removeView(it) }
        button = null
        hideBubble()
        hideTargets()
        tapMark?.let { wm.removeView(it) }
        tapMark = null
        worker.shutdownNow()
        warmer.shutdownNow()
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
            .setContentText("Stuknij przycisk ?, żeby zapytać AI o pytanie z ekranu.")
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

    /** Otwiera polaczenie z API, chyba ze zrobilismy to przed chwila. */
    private fun warmUp() {
        val a = answerer ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWarmAt < WARM_AGAIN_AFTER_MS) return
        lastWarmAt = now
        warmer.execute { a.warmUp() }
    }

    private fun onTap() {
        if (busy) return
        busy = true
        val startedAt = SystemClock.elapsedRealtime()
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
                val mode = settings.mode
                worker.execute {
                    val answer = try {
                        answerer?.solve(bitmap, mode) {
                            main.post { if (button != null) showBubble(if (settings.slickBubble) "🔍 Szukam…" else "Szukam w internecie…") }
                        } ?: Answer.error("Dymek nie jest gotowy.")
                    } catch (e: RuntimeException) {
                        Answer.error("Błąd: ${e.message}")
                    } finally {
                        bitmap.recycle()
                    }
                    // czas od stukniecia, zeby bylo widac, ktory model jest najszybszy
                    val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    val time = String.format(Locale("pl"), "%.1f s", seconds)
                    main.post {
                        if (button != null) deliver(answer, time, mode) else busy = false
                    }
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    /** Pewna odpowiedz klika autoclicker albo pokazuje dymek; niepewnej nie pokazujemy wcale. */
    private fun deliver(answer: Answer, seconds: String, mode: Mode) {
        // lupa z liczba wyszukiwan: odpowiedz wymagala sprawdzenia w internecie, stad dluzszy czas
        val meta = if (answer.searched) "🔍${answer.searches} $seconds" else seconds
        val tapper = TapService.instance
        when {
            answer.error -> showBubble(answer.text, meta)
            answer.text.isEmpty() -> showBubble(unknownLabel(), meta)
            // pewnosc w procentach mowi, jak daleko bylo do progu
            !answer.sureIn(mode) -> showBubble(unknownLabel(), "${answer.confidence}%  ·  $meta")
            !settings.autoClick -> showBubble(answerLabel(answer), meta)
            tapper == null -> showBubble(answerLabel(answer), "$meta\nWłącz „Dymek: autoclicker” w Ułatwieniach dostępu.")
            else -> {
                autoClick(tapper, answer, meta)
                return
            }
        }
        busy = false
    }

    /** Klika po losowym opoznieniu, zeby czas odpowiedzi nie byl za kazdym razem taki sam. */
    private fun autoClick(tapper: TapService, answer: Answer, meta: String) {
        hideBubble()
        val delay = (settings.clickDelaySeconds * 1000 * Random.nextDouble(0.5, 1.5)).toLong()
        main.postDelayed({
            val screen = screenSize()
            // punkty od AI sa w ulamkach ekranu, a usluga klika w pikselach
            fun pixels(p: PointF?) = p?.let { PointF(it.x * screen.x, it.y * screen.y) }
            passThrough(true)
            tapper.clickOption(answer.text, pixels(answer.tap)) { at ->
                showTapMark(at)
                // button == null: dymek zostal wylaczony w trakcie klikania
                if (at != null && answer.confirm != null && button != null) {
                    // np. Mentimeter: po wybraniu opcji trzeba jeszcze nacisnac "Wyslij"
                    main.postDelayed({
                        tapper.clickSubmit(pixels(answer.confirm)) { sent ->
                            showTapMark(sent)
                            afterClick(sent != null, answer, meta)
                        }
                    }, Random.nextLong(250, 600))
                } else {
                    afterClick(at != null, answer, meta)
                }
            }
        }, delay)
    }

    private fun afterClick(clicked: Boolean, answer: Answer, meta: String) {
        passThrough(false)
        if (button != null) {
            val label = answerLabel(answer)
            val text = when {
                !clicked -> SpannableStringBuilder("Nie udało się kliknąć: ").append(label)
                settings.slickBubble -> SpannableStringBuilder("✓ ").append(label)
                else -> SpannableStringBuilder("👆 ").append(label)
            }
            showBubble(text, meta)
        }
        busy = false
    }

    /** Odpowiedz w dymku: w Kahoot sam ksztalt, w nowym stylu jako kolorowy znak zamiast emotki. */
    private fun answerLabel(answer: Answer): CharSequence {
        val shape = answer.shape ?: return answer.text
        if (!settings.slickBubble) return shape.emoji
        return SpannableString(shape.glyph).apply {
            setSpan(ForegroundColorSpan(shape.color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun unknownLabel() = if (settings.slickBubble) "Nie wiem" else "🤷"

    /** Krotki pierscien w miejscu klikniecia autoclickera, zeby bylo widac, gdzie trafil. */
    private fun showTapMark(at: PointF?) {
        if (at == null || button == null) return
        tapMark?.let { wm.removeView(it) }
        val size = dp(TAP_MARK_DP)
        val view = View(uiContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke(dp(2), if (settings.slickBubble) 0xFFFFFFFF.toInt() else ACCENT)
            }
        }
        val params = overlayParams(size, size).apply {
            x = (at.x - size / 2f).toInt()
            y = (at.y - size / 2f).toInt()
            // wspolrzedne calego ekranu, jak przy kliknieciu; dotyk przechodzi dalej
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        wm.addView(view, params)
        tapMark = view
        main.postDelayed({
            if (tapMark === view) {
                wm.removeView(view)
                tapMark = null
            }
        }, TAP_MARK_MS)
    }

    /** Na czas klikniecia przycisk znika i przepuszcza dotyk do aplikacji pod spodem. */
    private fun passThrough(on: Boolean) {
        val view = button ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        buttonParams.flags = if (on) buttonParams.flags or notTouchable else buttonParams.flags and notTouchable.inv()
        // Android przepuszcza dotyk tylko przez okna nakladki z przezroczystoscia do 0,8
        buttonParams.alpha = if (on) 0f else 1f
        wm.updateViewLayout(view, buttonParams)
    }

    private fun showButton() {
        val size = dp(BUTTON_DP)
        val screen = screenSize()
        val view = TextView(uiContext).apply {
            text = "?"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = "Zapytaj AI o pytanie z ekranu"
        }
        buttonParams = overlayParams(size, size).apply {
            x = screen.x - size - dp(12)
            y = screen.y / 3
        }
        view.setOnTouchListener(DragTouch())
        wm.addView(view, buttonParams)
        button = view
        styleButton()
    }

    /** Zwykly przycisk jest pomaranczowy z obwodka trybu, mniej widoczny: maly, szary i polprzezroczysty. */
    private fun styleButton() {
        val view = button ?: return
        val quiet = settings.quietButton
        val size = dp(if (quiet) QUIET_BUTTON_DP else BUTTON_DP)
        view.textSize = if (quiet) 14f else 20f
        view.alpha = if (quiet) QUIET_ALPHA else 1f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (quiet) QUIET_COLOR else ACCENT)
            // obwodka w kolorze trybu: zolta Szybki, biala Zbalansowany, niebieska Dokladny
            if (!quiet) setStroke(dp(2), settings.mode.color)
        }
        // srodek przycisku zostaje tam, gdzie byl
        val shift = (buttonParams.width - size) / 2
        buttonParams.x += shift
        buttonParams.y += shift
        buttonParams.width = size
        buttonParams.height = size
        keepButtonOnScreen()
    }

    private fun keepButtonOnScreen() {
        val view = button ?: return
        val screen = screenSize()
        buttonParams.x = buttonParams.x.coerceIn(0, max(0, screen.x - buttonParams.width))
        buttonParams.y = buttonParams.y.coerceIn(0, max(0, screen.y - buttonParams.height))
        wm.updateViewLayout(view, buttonParams)
        if (bubbleShown) positionBubble()
    }

    /**
     * Dymek z odpowiedzia [main] i dopiskiem [meta] (czas, lupa). Nowy styl: ciemny i polprzezroczysty,
     * dopisek mniejszy i przygaszony; klasyczny: bialy z pomaranczowa ramka.
     */
    private fun showBubble(main: CharSequence, meta: String? = null) {
        val view = bubble ?: TextView(uiContext).apply {
            maxLines = 10
            ellipsize = TextUtils.TruncateAt.END
            // stukniecie zamyka dymek
            setOnClickListener { hideBubble() }
        }.also { bubble = it }
        bubbleVersion++
        val slick = settings.slickBubble
        val text = SpannableStringBuilder(main)
        if (slick) {
            view.textSize = 15f
            view.setTextColor(0xFFFFFFFF.toInt())
            view.setPadding(dp(12), dp(7), dp(12), dp(7))
            view.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xB3121212.toInt())
                setStroke(1, 0x33FFFFFF)
            }
            text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (meta != null) {
                val from = text.length
                text.append("   ").append(meta)
                text.setSpan(RelativeSizeSpan(0.8f), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            view.textSize = 16f
            view.setTextColor(0xFF1F1F1F.toInt())
            view.setPadding(dp(14), dp(10), dp(14), dp(10))
            view.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF7FFFFFF.toInt())
                setStroke(dp(2), ACCENT)
            }
            if (meta != null) text.append("  ·  ").append(meta)
        }
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

    /**
     * Miejsca do upuszczenia przycisku, widoczne tylko w czasie przeciagania (jak w Messengerze): tryby w rogach
     * u gory i na dole, a na srodku krzyzyk, ktory zamyka aplikacje.
     */
    private fun showTargets() {
        if (targets.isNotEmpty()) return
        val screen = screenSize()
        zones = DropZones(screen.x, screen.y, resources.displayMetrics.density, withQuit = settings.centerQuits)
        for ((drop, center) in zones.centers) {
            val view = TextView(uiContext).apply {
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                contentDescription = drop.mode?.let { "Upuść tu przycisk: tryb ${it.label}" }
                    ?: "Upuść tu przycisk, żeby zamknąć Dymek"
            }
            // te same wspolrzedne okna co przycisk, wiec odleglosci licza sie dla obu tak samo
            val params = overlayParams(0, 0).apply {
                // dotyk idzie dalej, do przeciaganego przycisku
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            val target = Target(drop, view, params, center)
            styleTarget(target, active = false)
            wm.addView(view, params)
            targets += target
        }
    }

    /** Przyciagniete miejsce robi sie wieksze; krzyzyk zmienia sie w czerwony pierscien wokol przycisku. */
    private fun styleTarget(target: Target, active: Boolean) {
        val size = dp(if (active) DropZones.TARGET_ACTIVE_DP else DropZones.TARGET_DP)
        target.params.width = size
        target.params.height = size
        target.params.x = target.center.x - size / 2
        target.params.y = target.center.y - size / 2
        val mode = target.drop.mode
        val current = mode != null && mode == settings.mode
        target.view.text = mode?.short ?: if (active) "" else "✕"
        target.view.textSize = if (mode != null) 12f else 22f
        target.view.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
        target.view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                mode == null && active -> {
                    setColor(0x55D93025)
                    setStroke(dp(4), 0xFFD93025.toInt())
                }
                mode != null && active -> {
                    setColor(0xCC000000.toInt())
                    setStroke(dp(4), mode.color)
                }
                else -> {
                    setColor(0x99000000.toInt())
                    // obecny tryb ma obwodke w swoim kolorze
                    setStroke(dp(2), if (current) mode!!.color else 0xFFFFFFFF.toInt())
                }
            }
        }
    }

    /** Miejsce, do ktorego przyciaga przycisk w punkcie (x, y) okna, albo null. */
    private fun targetNear(x: Int, y: Int): Target? {
        if (targets.isEmpty()) return null
        val drop = zones.nearest(x + buttonParams.width / 2f, y + buttonParams.height / 2f) ?: return null
        return targets.firstOrNull { it.drop == drop }
    }

    private fun hideTargets() {
        targets.forEach { wm.removeView(it.view) }
        targets.clear()
    }

    /** Tryb z przeciagniecia: przycisk dostaje kolor trybu, a dymek na chwile mowi, ktory jest wlaczony. */
    private fun switchMode(mode: Mode) {
        // obwodke przycisku zmienia sluchacz ustawien (styleButton)
        settings.mode = mode
        showBubble("Tryb: ${mode.label}")
        val version = bubbleVersion
        main.postDelayed({ if (bubbleVersion == version) hideBubble() }, NOTICE_MS)
    }

    /** Wylacza dymek i zamyka cala aplikacje, razem z ekranem ustawien na liscie ostatnich aplikacji. */
    private fun quitApp() {
        getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        stopSelf()
    }

    /**
     * Przeciaganie przycisku; krotkie stukniecie pyta AI, przytrzymanie wylacza dymek, upuszczenie w rogu u gory
     * albo na dole zmienia tryb, a upuszczenie na srodku ekranu zamyka cala aplikacje.
     */
    private inner class DragTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(uiContext).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private var snapped: Target? = null
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
                    snapped = null
                    // polaczenie zestawia sie, zanim palec sie podniesie
                    warmUp()
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout() * 2L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                        showTargets()
                    }
                    if (moved) {
                        val x = startX + dx.toInt()
                        val y = startY + dy.toInt()
                        val near = targetNear(x, y)
                        if (near !== snapped) {
                            snapped?.let { restyle(it, active = false) }
                            near?.let { restyle(it, active = true) }
                            snapped = near
                            if (near != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        if (near != null) {
                            // jak w Messengerze: blisko miejsca przycisk sam do niego przeskakuje
                            buttonParams.x = near.center.x - buttonParams.width / 2
                            buttonParams.y = near.center.y - buttonParams.height / 2
                        } else {
                            buttonParams.x = x
                            buttonParams.y = y
                        }
                        wm.updateViewLayout(v, buttonParams)
                        if (bubbleShown) positionBubble()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    val drop = snapped?.drop
                    hideTargets()
                    val mode = drop?.mode
                    when {
                        drop == Drop.QUIT -> {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            quitApp()
                        }
                        mode != null -> {
                            // przycisk wraca na swoje miejsce, zeby nie zostal w rogu
                            buttonParams.x = startX
                            buttonParams.y = startY
                            wm.updateViewLayout(v, buttonParams)
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            switchMode(mode)
                        }
                        !moved && !longPressed -> {
                            v.performClick()
                            onTap()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    hideTargets()
                }
            }
            return true
        }

        private fun restyle(target: Target, active: Boolean) {
            styleTarget(target, active)
            wm.updateViewLayout(target.view, target.params)
        }
    }

    private class Target(
        val drop: Drop,
        val view: TextView,
        val params: WindowManager.LayoutParams,
        val center: DropZones.Center,
    )

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
        // tyle wystarcza, zeby na klatce nie bylo juz przycisku
        private const val CAPTURE_DELAY_MS = 150L
        private const val KEEP_WARM_MS = 45_000L
        private const val WARM_AGAIN_AFTER_MS = 20_000L
        private const val ACCENT = 0xFFD97757.toInt()
        private const val BUTTON_DP = 44
        private const val QUIET_BUTTON_DP = 32
        private const val QUIET_COLOR = 0xFF808080.toInt()
        private const val QUIET_ALPHA = 0.35f
        private const val NOTICE_MS = 1500L
        private const val TAP_MARK_DP = 28
        private const val TAP_MARK_MS = 450L
    }
}
