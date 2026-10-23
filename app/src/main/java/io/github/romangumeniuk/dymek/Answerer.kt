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

    /** Otwiera polaczenie z API zawczasu (TLS), zeby samo pytanie poszlo szybciej. Bledy ignoruje. */
    fun warmUp() {}

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
            Dostajesz zrzut ekranu telefonu z pytaniem z quizu na czas. Pytania dotyczą najczęściej gier
            (gry wideo, ich postacie, serie, studia, daty premier, mechaniki, e-sport), ale mogą być o czymkolwiek.
            Liczy się szybkość, więc odpowiedz natychmiast i jak najkrócej:
            - przy pytaniu z opcjami podaj tylko literę lub numer i treść poprawnej opcji,
            - w innym wypadku samą odpowiedź (słowo, liczbę, nazwę),
            - bez wyjaśnień, wstępów i powtarzania pytania.
            Pisz w języku pytania. Jeśli na ekranie nie ma pytania, napisz w kilku słowach, co widzisz.
        """.trimIndent()

        // tekst quizu jest duzy, wiec mniejszy obraz wystarcza, a mniej tokenow = szybsza odpowiedz
        private const val MAX_SIDE = 1200

        /** JPEG w base64, dluzszy bok do 1200 px. */
        fun toJpegBase64(src: Bitmap): String {
            val longSide = max(src.width, src.height)
            val scaled = if (longSide > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / longSide
                Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
            } else {
                src
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (scaled !== src) scaled.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
