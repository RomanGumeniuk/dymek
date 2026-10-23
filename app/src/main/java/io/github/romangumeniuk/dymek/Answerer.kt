package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Odpowiada na pytanie ze zrzutu ekranu: tekst do dymka i miejsce do klikniecia. */
interface Answerer : AutoCloseable {
    /**
     * Jedno zapytanie do modelu o zrzut (JPEG w base64); z [search] model moze szukac w internecie,
     * a [guess] to jego wczesniejszy strzal. Nigdy nie rzuca bledow API: zwraca [Answer.error].
     */
    fun ask(image: String, search: Boolean, guess: String): Answer

    /** Otwiera polaczenie z API zawczasu (TLS), zeby samo pytanie poszlo szybciej. Bledy ignoruje. */
    fun warmUp() {}

    /** Szybka odpowiedz, a gdy model nie jest pewien, druga proba z wyszukiwaniem w internecie. */
    fun solve(screen: Bitmap, searchWhenUnsure: Boolean): Answer {
        val image = toJpegBase64(screen)
        val first = ask(image, search = false, guess = "")
        // pusta odpowiedz to brak pytania na ekranie, wtedy nie ma czego szukac
        if (first.error || first.sure || first.text.isEmpty() || !searchWhenUnsure) return first
        return ask(image, search = true, guess = first.text)
    }

    companion object {
        fun create(settings: Settings): Answerer {
            val model = settings.model
            return when (model.provider) {
                Provider.CLAUDE -> ClaudeAnswerer(settings.claudeKey, model)
                Provider.GEMINI -> GeminiAnswerer(settings.geminiKey, model)
            }
        }

        const val USER_PROMPT = "Odpowiedz na pytanie z tego ekranu."

        fun searchPrompt(guess: String) =
            "Nie byłeś pewien odpowiedzi (pierwszy strzał: „$guess”). Sprawdź ją w internecie, najwyżej " +
                "dwoma wyszukiwaniami, i odpowiedz tym samym jednym wierszem JSON. " +
                "sure=true tylko, gdy źródła potwierdzają odpowiedź."

        val SYSTEM_PROMPT = """
            Dostajesz zrzut ekranu telefonu z quizem na czas (Kahoot, Mentimeter, Quizizz, Wayground, Blooket,
            Google Forms i podobne). Pytania są najczęściej o grach (gry wideo, postacie, serie, studia, daty premier,
            mechaniki, e-sport), ale mogą być o czymkolwiek. Liczy się szybkość.
            Odpowiedz wyłącznie jednym wierszem JSON, bez markdownu i bez wyjaśnień:
            {"a":"…","s":"…","x":0,"y":0,"bx":-1,"by":-1,"sure":true}
            a: poprawna odpowiedź jak najkrócej, w języku pytania (treść opcji, słowo, liczba). Gdy nie jesteś pewien,
            najlepszy strzał. "" gdy na ekranie nie ma pytania albo widać same kształty bez treści pytania.
            s: w Kahoot kształt poprawnej opcji: "triangle" (czerwony trójkąt), "diamond" (niebieski romb),
            "circle" (żółte koło), "square" (zielony kwadrat). Poza Kahoot "".
            x, y: środek przycisku z poprawną odpowiedzią na zrzucie w skali 0–1000 (0,0 to lewy górny róg,
            1000,1000 prawy dolny). -1, gdy nie ma czego kliknąć.
            bx, by: środek przycisku zatwierdzenia (np. „Wyślij”, „Submit”), jeśli trzeba go nacisnąć po wybraniu
            odpowiedzi. Inaczej -1.
            sure: true tylko, gdy jesteś pewien odpowiedzi. Przy zgadywaniu false.
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
