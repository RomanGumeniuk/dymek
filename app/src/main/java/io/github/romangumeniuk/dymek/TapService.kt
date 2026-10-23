package io.github.romangumeniuk.dymek

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/**
 * Autoclicker: usluga ulatwien dostepu, ktora stuka w ekran za uzytkownika. Nie czyta zawartosci ekranu,
 * tylko wykonuje gesty; wlacza sie ja w ustawieniach Androida.
 */
class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Stukniecie w punkt ekranu w pikselach; [done] dostaje true, gdy system je wykonal (na watku glownym). */
    fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        // palec nie przytrzymuje ekranu zawsze tak samo dlugo
        val stroke = GestureDescription.StrokeDescription(path, 0, Random.nextLong(40, 110))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, null)
        if (!sent) done(false)
    }

    companion object {
        /** Wlaczona usluga albo null, gdy uzytkownik jej nie wlaczyl. */
        @Volatile
        var instance: TapService? = null
            private set
    }
}
