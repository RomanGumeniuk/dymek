package io.github.romangumeniuk.dymek

import android.graphics.PointF
import org.json.JSONException
import org.json.JSONObject

/** Ksztalty odpowiedzi w Kahoot, w kolejnosci z ekranu. */
enum class Shape(val key: String, val emoji: String) {
    TRIANGLE("triangle", "🔺"),
    DIAMOND("diamond", "🔷"),
    CIRCLE("circle", "🟡"),
    SQUARE("square", "🟩"),
}

/**
 * Odpowiedz modelu: tekst, pewnosc, ksztalt z Kahoot i punkty do klikniecia (ulamek szerokosci
 * i wysokosci ekranu) albo komunikat bledu do pokazania w dymku.
 */
class Answer(
    val text: String,
    val sure: Boolean = false,
    val shape: Shape? = null,
    val tap: PointF? = null,
    val confirm: PointF? = null,
    val error: Boolean = false,
) {
    /** Tresc dymka: przy Kahoot sam ksztalt, ktory widac jednym spojrzeniem. */
    val label: String get() = shape?.emoji ?: text

    companion object {
        fun error(message: String) = Answer(message, error = true)

        /** Czyta jednowierszowy JSON z odpowiedzi modelu; tekst przed nim i po nim pomija. */
        fun parse(reply: String): Answer {
            val start = reply.lastIndexOf("{\"a\"").takeIf { it >= 0 } ?: reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return error(reply.trim().ifEmpty { "Brak odpowiedzi." }.take(200))
            return try {
                val json = JSONObject(reply.substring(start, end + 1))
                val shape = json.optString("s")
                Answer(
                    text = json.optString("a").trim(),
                    sure = json.optBoolean("sure", false),
                    shape = Shape.entries.firstOrNull { it.key == shape },
                    tap = point(json, "x", "y"),
                    confirm = point(json, "bx", "by"),
                )
            } catch (e: JSONException) {
                error(reply.trim().take(200))
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
