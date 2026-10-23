package io.github.romangumeniuk.dymek

import android.content.Context

enum class Provider { CLAUDE, GEMINI }

/** Model do wyboru w ustawieniach. */
enum class ModelChoice(val id: String, val label: String, val provider: Provider) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5 (najdokładniejszy)", Provider.CLAUDE),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 (szybszy)", Provider.CLAUDE),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (najszybszy, najtańszy)", Provider.CLAUDE),
    GEMINI_FLASH("gemini-3.8-flash", "Gemini 3.8 Flash (darmowy)", Provider.GEMINI),
    GEMINI_LITE("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (darmowy, najszybszy)", Provider.GEMINI),
}

/** Klucze API i model w prywatnych ustawieniach aplikacji (bez kopii w chmurze: allowBackup=false). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("dymek", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = prefs.getString(KEY_CLAUDE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLAUDE, value.trim()).apply()

    var geminiKey: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var model: ModelChoice
        get() = ModelChoice.entries.firstOrNull { it.name == prefs.getString(KEY_MODEL, null) }
            ?: ModelChoice.OPUS
        set(value) = prefs.edit().putString(KEY_MODEL, value.name).apply()

    private companion object {
        // "api_key" z wersji 1.0, zeby klucz Claude nie zniknal po aktualizacji
        const val KEY_CLAUDE = "api_key"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_MODEL = "model"
    }
}
