package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale

/**
 * Test wybranego modelu na narysowanym pytaniu w stylu Kahoot: czas odpowiedzi, poprawnosc
 * i trafienie klikniecia, raz bez wyszukiwania i raz z wyszukiwaniem.
 */
object SpeedTest {
    private const val WIDTH = 1080
    private const val HEIGHT = 2340
    private const val QUESTION = "W którym roku w Japonii wyszła gra Super Mario Bros.?"
    private const val CORRECT = "1985"

    private class Option(val shape: Shape, val color: Int, val text: String, val box: RectF)

    // uklad jak w Kahoot; prostokaty w ulamkach szerokosci i wysokosci ekranu
    private val options = listOf(
        Option(Shape.TRIANGLE, 0xFFE21B3C.toInt(), "1983", RectF(0.03f, 0.55f, 0.49f, 0.74f)),
        Option(Shape.DIAMOND, 0xFF1368CE.toInt(), "1990", RectF(0.51f, 0.55f, 0.97f, 0.74f)),
        Option(Shape.CIRCLE, 0xFFD89E00.toInt(), CORRECT, RectF(0.03f, 0.76f, 0.49f, 0.95f)),
        Option(Shape.SQUARE, 0xFF26890C.toInt(), "1996", RectF(0.51f, 0.76f, 0.97f, 0.95f)),
    )

    /** Trwa kilka sekund i laczy sie z API, wiec wolac poza watkiem glownym. */
    fun run(settings: Settings): String {
        val bitmap = drawQuiz()
        val image = Answerer.toJpegBase64(bitmap)
        bitmap.recycle()
        return Answerer.create(settings).use { answerer ->
            // jak w dymku: polaczenie otwarte zawczasu
            answerer.warmUp()
            listOf(
                settings.model.label,
                measure("bez wyszukiwania") { answerer.ask(image, search = false, guess = "") },
                measure("z wyszukiwaniem") { answerer.ask(image, search = true, guess = CORRECT) },
            ).joinToString("\n")
        }
    }

    private fun measure(name: String, ask: () -> Answer): String {
        val start = SystemClock.elapsedRealtime()
        val answer = ask()
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        val result = if (answer.error) {
            answer.text
        } else {
            val good = options.first { it.text == CORRECT }
            val correct = answer.text.contains(CORRECT) && answer.shape == good.shape
            val hit = answer.tap?.let { good.box.contains(it.x, it.y) } == true
            listOfNotNull(answer.shape?.emoji, answer.text).joinToString(" ") +
                (if (correct) ", poprawna ✓" else ", błędna ✗") +
                (if (answer.sure) ", pewna" else ", niepewna") +
                (if (hit) ", kliknięcie trafia ✓" else ", kliknięcie pudłuje ✗")
        }
        return String.format(Locale("pl"), "• %s: %.1f s, %s", name, seconds, result)
    }

    private fun drawQuiz(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF46178F.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("1 z 10", 60f, 180f, text)
        canvas.drawText("20", WIDTH - 140f, 180f, text)

        // karta z pytaniem
        canvas.drawRoundRect(RectF(40f, 260f, WIDTH - 40f, 760f), 24f, 24f, paint)
        text.color = 0xFF333333.toInt()
        text.textSize = 64f
        val question = StaticLayout.Builder.obtain(QUESTION, 0, QUESTION.length, text, WIDTH - 160)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(80f, 510f - question.height / 2f)
        question.draw(canvas)
        canvas.restore()

        text.color = Color.WHITE
        text.textSize = 72f
        for (option in options) {
            val box = RectF(option.box.left * WIDTH, option.box.top * HEIGHT, option.box.right * WIDTH, option.box.bottom * HEIGHT)
            paint.color = option.color
            canvas.drawRoundRect(box, 16f, 16f, paint)
            drawShape(canvas, option.shape, box.left + 90f, box.centerY(), 44f)
            canvas.drawText(option.text, box.left + 170f, box.centerY() + 26f, text)
        }
        return bitmap
    }

    private fun drawShape(canvas: Canvas, shape: Shape, cx: Float, cy: Float, r: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (shape) {
            Shape.CIRCLE -> canvas.drawCircle(cx, cy, r, paint)
            Shape.SQUARE -> canvas.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, paint)
            Shape.TRIANGLE, Shape.DIAMOND -> {
                val path = Path().apply {
                    moveTo(cx, cy - r)
                    if (shape == Shape.TRIANGLE) {
                        lineTo(cx + r, cy + r * 0.8f)
                        lineTo(cx - r, cy + r * 0.8f)
                    } else {
                        lineTo(cx + r, cy)
                        lineTo(cx, cy + r)
                        lineTo(cx - r, cy)
                    }
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}
