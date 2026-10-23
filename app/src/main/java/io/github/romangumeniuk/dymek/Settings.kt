package io.github.romangumeniuk.dymek

import android.content.Context

/** Model do wyboru w ustawieniach. */
enum class ModelChoice(val id: String, val label: String, val serverFallback: Boolean) {
    OPUS("claude-opus-5-5", "Opus 5.5 (najdokładniejszy)", true),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5 (szybszy)", true),
    HAIKU("claude-haiku-5-5", "Haiku 5.5 (najszybszy, najtańszy)", false),
}

/** Klucz API i model w prywatnych ustawieniach aplikacji (bez kopii w chmurze: allowBackup=false). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("dymek", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var model: ModelChoice
        get() = ModelChoice.entries.firstOrNull { it.name == prefs.getString(KEY_MODEL, null) }
            ?: ModelChoice.OPUS
        set(value) = prefs.edit().putString(KEY_MODEL, value.name).apply()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
    }
}
