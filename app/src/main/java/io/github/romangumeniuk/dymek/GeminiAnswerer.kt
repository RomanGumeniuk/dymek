package io.github.romangumeniuk.dymek

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Gemini przez REST generateContent (darmowy plan Google AI Studio, z limitami zapytan).
 * Bez SDK: wystarcza HttpURLConnection i org.json z Androida.
 */
class GeminiAnswerer(private val apiKey: String, private val model: ModelChoice) : Answerer {

    override fun ask(image: String, mode: Mode, unsure: Answer?): Answer {
        val search = unsure != null
        // Gemini nie ma limitu wyszukiwan w API, wiec liczbe podaje tylko polecenie
        val prompt = unsure?.let { Answerer.searchPrompt(it, mode) } ?: Answerer.USER_PROMPT
        var result = send(requestBody(image, prompt, search, thinkingLevel(mode)))
        // gdyby model nie znal poziomu myslenia, ponow bez niego
        if (result.code == 400 && result.body.contains("thinking", ignoreCase = true)) {
            result = send(requestBody(image, prompt, search, thinkingLevel = null))
        }
        return when {
            result.code == -1 -> Answer.error("Brak połączenia z internetem.")
            result.code == 200 -> parseAnswer(result.body)
            result.code == 429 -> Answer.error("Limit darmowych zapytań Gemini wyczerpany, spróbuj za chwilę.")
            result.code == 400 && result.body.contains("API_KEY_INVALID") ->
                Answer.error("Zły klucz API Gemini. Sprawdź go w aplikacji Dymek.")
            result.code == 403 -> Answer.error("Klucz Gemini nie ma dostępu do tego modelu.")
            else -> Answer.error("Błąd Gemini (${result.code}): ${errorMessage(result.body).take(160)}")
        }
    }

    override fun warmUp() {
        // darmowe zapytanie o model: polaczenie zostaje w puli keep-alive HttpURLConnection
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.id}")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            // odpowiedz trzeba doczytac do konca, inaczej polaczenie nie wroci do puli
            stream?.use { it.readBytes() }
        } catch (e: IOException) {
            // rozgrzewka nie jest konieczna
        }
    }

    override fun close() = Unit

    // najnizszy poziom myslenia, jaki model obsluguje, bo w quizie liczy sie czas; tryb Dokladny o stopien wyzej
    private fun thinkingLevel(mode: Mode): String = when {
        model == ModelChoice.GEMINI_FLASH -> if (mode.think) "medium" else "low"
        else -> if (mode.think) "low" else "minimal"
    }

    private fun requestBody(imageBase64: String, prompt: String, search: Boolean, thinkingLevel: String?): String {
        // myslenie zuzywa tokeny, zanim model napisze JSON, wiec potrzebuje zapasu
        val generationConfig = JSONObject().put("maxOutputTokens", if (thinkingLevel == "minimal") 1024 else 4096)
        if (thinkingLevel != null) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(Answerer.SYSTEM_PROMPT))))
            .put(
                "contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts", JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64),
                                    )
                                )
                                .put(text(prompt))
                        )
                )
            )
            .put("generationConfig", generationConfig)
        // wyszukiwarka Google tylko przy drugiej probie, bo wydluza odpowiedz
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return body.toString()
    }

    private fun text(value: String) = JSONObject().put("text", value)

    private class HttpResult(val code: Int, val body: String)

    private fun send(body: String): HttpResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // klucz w naglowku, nie w adresie URL
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (e: IOException) {
            HttpResult(-1, "")
        }
        // bez disconnect(): polaczenie zostaje otwarte dla nastepnego pytania
    }

    private fun parseAnswer(body: String): Answer = try {
        val json = JSONObject(body)
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            Answer.error(if (reason.isNotEmpty()) "Gemini zablokował to zapytanie ($reason)." else "Brak odpowiedzi.")
        } else {
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = (0 until parts.length())
                .mapNotNull { parts.optJSONObject(it) }
                .filter { !it.optBoolean("thought", false) }
                .joinToString("") { it.optString("text") }
            // zapytania, ktore model faktycznie wyslal do wyszukiwarki Google
            val searches = candidate.optJSONObject("groundingMetadata")?.optJSONArray("webSearchQueries")?.length() ?: 0
            when {
                text.isNotBlank() -> Answer.parse(text).copy(searches = searches)
                candidate.optString("finishReason") == "SAFETY" -> Answer.error("Gemini zablokował odpowiedź.")
                else -> Answer.error("Brak odpowiedzi.")
            }
        }
    } catch (e: JSONException) {
        Answer.error("Nie udało się odczytać odpowiedzi Gemini.")
    }

    private fun errorMessage(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifEmpty { body }
    } catch (e: JSONException) {
        body
    }
}
