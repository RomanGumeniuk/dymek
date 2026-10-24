package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Odpowiada na pytanie ze zrzutu ekranu: tekst do dymka i miejsce do klikniecia. */
interface Answerer : AutoCloseable {
    /**
     * Jedno zapytanie do modelu o zrzut (JPEG w base64); w trybie z [Mode.think] model dluzej mysli.
     * Z [unsure], czyli wczesniejsza niepewna odpowiedzia, model dostaje wyszukiwarke i ma sprawdzic
     * odpowiedz w internecie. Nigdy nie rzuca bledow API: zwraca [Answer.error].
     */
    fun ask(image: String, mode: Mode, unsure: Answer? = null): Answer

    /** Otwiera polaczenie z API zawczasu (TLS), zeby samo pytanie poszlo szybciej. Bledy ignoruje. */
    fun warmUp() {}

    /**
     * Odpowiedz w danym trybie: najpierw bez internetu, a gdy model nie jest dosc pewien i tryb na to pozwala,
     * druga proba z wyszukiwaniem; [onSearch] jest wolane tuz przed nia (z watku roboczego).
     */
    fun solve(screen: Bitmap, mode: Mode, onSearch: () -> Unit = {}): Answer =
        solveImage(toJpegBase64(screen), mode, onSearch)

    /** [solve] dla zrzutu juz zakodowanego jako JPEG w base64. */
    fun solveImage(image: String, mode: Mode, onSearch: () -> Unit = {}): Answer {
        val first = ask(image, mode)
        // bez pytania na ekranie nie ma czego szukac
        if (first.error || first.sureIn(mode) || first.question.isEmpty() || mode.maxSearches == 0) return first
        onSearch()
        return ask(image, mode, unsure = first).copy(searched = true)
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

        /** Druga proba: model ma najpierw wyszukac odpowiedz, a nie odpowiadac z pamieci. */
        fun searchPrompt(unsure: Answer, mode: Mode) =
            "Bez internetu nie byłeś pewien odpowiedzi (strzał: „${unsure.text}”, pewność ${unsure.confidence}%). " +
                "Zanim odpowiesz, wyszukaj w internecie odpowiedź na pytanie „${unsure.question}” " +
                (if (mode.maxSearches == 1) "(jedno wyszukiwanie). " else "(od jednego do ${mode.maxSearches} wyszukiwań). ") +
                "Potem odpowiedz tym samym jednym wierszem JSON; p to teraz to, jak mocno znalezione źródła " +
                "potwierdzają odpowiedź."

        val SYSTEM_PROMPT = """
            Dostajesz zrzut ekranu telefonu z quizem na czas (Kahoot, Mentimeter, Quizizz, Wayground, Blooket,
            Google Forms i podobne). Pytania są najczęściej o grach (gry wideo, postacie, serie, studia, daty premier,
            mechaniki, e-sport), ale mogą być o czymkolwiek. Liczy się szybkość.
            Odpowiedz wyłącznie jednym wierszem JSON, bez markdownu i bez wyjaśnień:
            {"q":"…","a":"…","s":"…","x":0,"y":0,"bx":-1,"by":-1,"p":0}
            q: treść pytania z ekranu. "" gdy na ekranie nie ma pytania albo widać same kształty bez treści pytania.
            a: poprawna odpowiedź jak najkrócej, w języku pytania (treść opcji, słowo, liczba). Zawsze podaj najlepszy
            strzał, także gdy nie jesteś pewien; "" tylko wtedy, gdy q jest puste.
            s: w Kahoot kształt poprawnej opcji: "triangle" (czerwony trójkąt), "diamond" (niebieski romb),
            "circle" (żółte koło), "square" (zielony kwadrat). Poza Kahoot "".
            x, y: środek przycisku z poprawną odpowiedzią na zrzucie w skali 0–1000 (0,0 to lewy górny róg,
            1000,1000 prawy dolny). -1, gdy nie ma czego kliknąć.
            bx, by: środek przycisku zatwierdzenia (np. „Wyślij”, „Submit”), jeśli trzeba go nacisnąć po wybraniu
            odpowiedzi. Inaczej -1.
            p: pewność w procentach (0–100), że a jest poprawne. Oceniaj uczciwie: ${Answer.SURE_PERCENT} i więcej tylko,
            gdy znasz ten fakt na pewno; gdy zgadujesz albo nie pamiętasz dokładnie daty, liczby czy nazwy, mniej.
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
