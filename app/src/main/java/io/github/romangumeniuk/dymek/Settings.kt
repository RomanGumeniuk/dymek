package io.github.romangumeniuk.dymek

import android.content.Context
import android.content.SharedPreferences

enum class Provider { CLAUDE, GEMINI }

/** Model do wyboru w ustawieniach, od najszybszego. */
enum class ModelChoice(val id: String, val label: String, val provider: Provider) {
    GEMINI_LITE("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (darmowy, najszybszy)", Provider.GEMINI),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (bardzo szybki, tani)", Provider.CLAUDE),
    GEMINI_FLASH("gemini-3.8-flash", "Gemini 3.8 Flash (darmowy, dokładniejszy)", Provider.GEMINI),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 (szybki, dokładny)", Provider.CLAUDE),
    OPUS("claude-opus-5-5", "Claude Opus 5.5 (najdokładniejszy, wolniejszy)", Provider.CLAUDE),
}

/** Klucze API, model i wyglad przycisku w prywatnych ustawieniach aplikacji (bez kopii w chmurze: allowBackup=false). */
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
            ?: ModelChoice.GEMINI_LITE
        set(value) = prefs.edit().putString(KEY_MODEL, value.name).apply()

    /** Maly, szary i polprzezroczysty przycisk zamiast pomaranczowego. */
    var quietButton: Boolean
        get() = prefs.getBoolean(KEY_QUIET, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET, value).apply()

    /** Upuszczenie przycisku na srodku ekranu zamyka cala aplikacje. */
    var centerQuits: Boolean
        get() = prefs.getBoolean(KEY_CENTER_QUITS, true)
        set(value) = prefs.edit().putBoolean(KEY_CENTER_QUITS, value).apply()

    /** Wola [onChange] po kazdej zmianie; zwrocony obiekt trzeba trzymac, bo prefs pamietaja go slabo. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        // "api_key" z wersji 1.0, zeby klucz Claude nie zniknal po aktualizacji
        const val KEY_CLAUDE = "api_key"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_MODEL = "model"
        const val KEY_QUIET = "quiet_button"
        const val KEY_CENTER_QUITS = "center_quits"
    }
}
