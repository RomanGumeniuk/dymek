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

/**
 * Jak starannie odpowiadac: pewnosc wystarczajaca bez internetu ([sureFirst]); przy mniejszej druga proba
 * z najwyzej [maxSearches] wyszukiwaniami w internecie i wtedy wystarcza [sureSearched]; [think]: model mysli dluzej.
 * [short] miesci sie w kolku na ekranie, [color] to obwodka przycisku w tym trybie.
 */
enum class Mode(
    val label: String,
    val short: String,
    val color: Int,
    val sureFirst: Int,
    val maxSearches: Int,
    val sureSearched: Int,
    val think: Boolean,
) {
    QUICK("Szybki", "Szybki", 0xFFFFC107.toInt(), 60, maxSearches = 1, sureSearched = 50, think = false),
    BALANCED("Zbalansowany", "Balans", 0xFFFFFFFF.toInt(), 80, maxSearches = 2, sureSearched = 60, think = false),
    ACCURATE("Dokładny", "Dokładny", 0xFF42A5F5.toInt(), 90, maxSearches = 3, sureSearched = 70, think = true),
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

    /** Ciemny, polprzezroczysty dymek bez emotek (poza lupa) zamiast bialego z pomaranczowa ramka. */
    var slickBubble: Boolean
        get() = prefs.getBoolean(KEY_SLICK, true)
        set(value) = prefs.edit().putBoolean(KEY_SLICK, value).apply()

    /** Tryb odpowiadania; zmienia sie tez przeciagnieciem przycisku do rogu albo na dol ekranu. */
    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: Mode.BALANCED
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Autoclicker sam stuka pewna odpowiedz (wymaga wlaczonej uslugi [TapService]). */
    var autoClick: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLICK, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLICK, value).apply()

    /** Srednie opoznienie klikniecia w sekundach; za kazdym razem losowane od polowy do poltora tej wartosci. */
    var clickDelaySeconds: Float
        get() = prefs.getFloat(KEY_CLICK_DELAY, 1f)
        set(value) = prefs.edit().putFloat(KEY_CLICK_DELAY, value.coerceIn(0f, MAX_CLICK_DELAY)).apply()

    /** Wola [onChange] po kazdej zmianie; zwrocony obiekt trzeba trzymac, bo prefs pamietaja go slabo. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val MAX_CLICK_DELAY = 30f

        // "api_key" z wersji 1.0, zeby klucz Claude nie zniknal po aktualizacji
        private const val KEY_CLAUDE = "api_key"
        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_MODEL = "model"
        private const val KEY_QUIET = "quiet_button"
        private const val KEY_CENTER_QUITS = "center_quits"
        private const val KEY_SLICK = "slick_bubble"
        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_CLICK = "auto_click"
        private const val KEY_CLICK_DELAY = "click_delay"
    }
}
