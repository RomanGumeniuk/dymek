package io.github.romangumeniuk.dymek

import android.graphics.PointF
import org.json.JSONException
import org.json.JSONObject

/** Ksztalty odpowiedzi w Kahoot, w kolejnosci z ekranu; [glyph] w kolorze [color] to wersja bez emotek. */
enum class Shape(val key: String, val emoji: String, val glyph: String, val color: Int) {
    TRIANGLE("triangle", "🔺", "▲", 0xFFE21B3C.toInt()),
    DIAMOND("diamond", "🔷", "◆", 0xFF1368CE.toInt()),
    CIRCLE("circle", "🟡", "●", 0xFFFFA602.toInt()),
    SQUARE("square", "🟩", "■", 0xFF26890C.toInt()),
}

/**
 * Odpowiedz modelu: pytanie odczytane z ekranu, odpowiedz z pewnoscia w procentach, ksztalt z Kahoot
 * i punkty do klikniecia (ulamek szerokosci i wysokosci ekranu) albo komunikat bledu do dymka.
 * [searched]: z drugiej proby, z wyszukiwarka; [searches]: ile razy model faktycznie szukal.
 */
data class Answer(
    val text: String,
    val question: String = "",
    val confidence: Int = 0,
    val shape: Shape? = null,
    val tap: PointF? = null,
    val confirm: PointF? = null,
    val error: Boolean = false,
    val searched: Boolean = false,
    val searches: Int = 0,
) {
    /** Tylko taka odpowiedz pokazujemy i klikamy; ile pewnosci wystarcza, zalezy od trybu i od wyszukania. */
    fun sureIn(mode: Mode): Boolean =
        !error && text.isNotEmpty() && confidence >= if (searched) mode.sureSearched else mode.sureFirst

    /** Tresc dymka: przy Kahoot sam ksztalt, ktory widac jednym spojrzeniem. */
    val label: String get() = shape?.emoji ?: text

    companion object {
        // w poleceniu dla modelu: od tylu procent "znam to na pewno"; szybkie modele chetnie zawyzaja pewnosc
        const val SURE_PERCENT = 90

        fun error(message: String) = Answer(message, error = true)

        /** Czyta jednowierszowy JSON z odpowiedzi modelu; tekst przed nim i po nim pomija. */
        fun parse(reply: String): Answer {
            val start = reply.lastIndexOf("{\"q\"").takeIf { it >= 0 } ?: reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return error(reply.trim().ifEmpty { "Brak odpowiedzi." }.take(200))
            return try {
                val json = JSONObject(reply.substring(start, end + 1))
                val shape = json.optString("s")
                Answer(
                    text = json.optString("a").trim(),
                    question = json.optString("q").trim(),
                    confidence = confidence(json),
                    shape = Shape.entries.firstOrNull { it.key == shape },
                    tap = point(json, "x", "y"),
                    confirm = point(json, "bx", "by"),
                )
            } catch (e: JSONException) {
                error(reply.trim().take(200))
            }
        }

        // procenty 0-100; model czasem poda ulamek 0-1 albo samo "sure"
        private fun confidence(json: JSONObject): Int {
            val p = json.optDouble("p", Double.NaN)
            return when {
                p.isNaN() -> if (json.optBoolean("sure", false)) 100 else 0
                p > 0 && p <= 1 -> (p * 100).toInt()
                else -> p.toInt().coerceIn(0, 100)
            }
        }

        // model podaje punkt w skali 0-1000, a -1, gdy nie ma czego kliknac
        private fun point(json: JSONObject, xKey: String, yKey: String): PointF? {
            val x = json.optDouble(xKey, -1.0)
            val y = json.optDouble(yKey, -1.0)
            if (x !in 0.0..1000.0 || y !in 0.0..1000.0) return null
            return PointF((x / 1000).toFloat(), (y / 1000).toFloat())
        }
    }
}
