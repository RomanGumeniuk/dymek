package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Odpowiada na pytanie ze zrzutu ekranu krotkim tekstem do dymka. */
interface Answerer : AutoCloseable {
    /** Nigdy nie rzuca bledow API: zwraca komunikat do pokazania w dymku. */
    fun answer(screen: Bitmap): String

    companion object {
        fun create(settings: Settings): Answerer {
            val model = settings.model
            return when (model.provider) {
                Provider.CLAUDE -> ClaudeAnswerer(settings.claudeKey, model)
                Provider.GEMINI -> GeminiAnswerer(settings.geminiKey, model)
            }
        }

        const val USER_PROMPT = "Odpowiedz na pytanie z tego ekranu."

        val SYSTEM_PROMPT = """
            Dostajesz zrzut ekranu telefonu. Zwykle jest na nim pytanie, np. z quizu, testu wiedzy albo zagadki.
            Odpowiedz na nie jak najkrócej, bo odpowiedź pokaże się w małym dymku na ekranie:
            - przy pytaniu z opcjami podaj literę lub numer i treść poprawnej opcji,
            - w innym wypadku samą odpowiedź (słowo, liczbę, nazwę) i najwyżej jedno krótkie zdanie wyjaśnienia,
            - bez wstępów i bez powtarzania pytania.
            Pisz w języku, w którym jest pytanie. Jeśli na ekranie nie ma żadnego pytania, napisz w kilku słowach, co widzisz.
        """.trimIndent()

        private const val MAX_SIDE = 1568

        /** JPEG w base64, dluzszy bok do 1568 px: tyle modele i tak widza, a wysylka jest szybsza. */
        fun toJpegBase64(src: Bitmap): String {
            val longSide = max(src.width, src.height)
            val scaled = if (longSide > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / longSide
                Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
            } else {
                src
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== src) scaled.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
