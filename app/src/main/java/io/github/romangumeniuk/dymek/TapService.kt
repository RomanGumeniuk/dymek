package io.github.romangumeniuk.dymek

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.Normalizer
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Autoclicker: usluga ulatwien dostepu, ktora klika za uzytkownika. Odpowiedz szuka po tekscie wsrod przyciskow
 * aplikacji pod spodem, bo to trafia dokladniej niz punkt odczytany przez AI ze zrzutu; niczego nie zapisuje.
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

    /**
     * Klika przycisk z tekstem [text] (z kilku takich ten najblizej [near]); gdy go nie ma, stuka w [near].
     * [done] dostaje miejsce klikniecia w pikselach ekranu albo null, gdy sie nie udalo (na watku glownym).
     */
    fun clickOption(text: String, near: PointF?, done: (PointF?) -> Unit) {
        val wanted = normalize(text)
        click(findClickable(near) { similarity(it, wanted) }, near, done)
    }

    /** Klika przycisk zatwierdzenia ("Wyślij", "Submit"...) najblizej [near], a gdy go nie ma, stuka w [near]. */
    fun clickSubmit(near: PointF?, done: (PointF?) -> Unit) {
        click(findClickable(near) { if (it in SUBMIT_WORDS) 0 else NO_MATCH }, near, done)
    }

    private fun click(node: AccessibilityNodeInfo?, near: PointF?, done: (PointF?) -> Unit) {
        if (node != null) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // prawdziwe stukniecie w srodek przycisku dziala wszedzie; ACTION_CLICK, gdy system nie przyjmie gestu
            tap(bounds.exactCenterX(), bounds.exactCenterY()) { at ->
                done(at ?: if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) center(bounds) else null)
            }
        } else if (near != null) {
            tap(near.x, near.y, done)
        } else {
            done(null)
        }
    }

    /** Stukniecie w punkt ekranu w pikselach. */
    private fun tap(x: Float, y: Float, done: (PointF?) -> Unit) {
        val point = PointF(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        val path = Path().apply { moveTo(point.x, point.y) }
        // palec nie przytrzymuje ekranu zawsze tak samo dlugo
        val stroke = GestureDescription.StrokeDescription(path, 0, Random.nextLong(40, 110))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(point)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(null)
        }, null)
        if (!sent) done(null)
    }

    /**
     * Najlepszy klikalny element aplikacji pod spodem wedlug [score] jego tekstu (mniej = lepiej, [NO_MATCH] = nie),
     * przy rownym wyniku blizszy [near].
     */
    private fun findClickable(near: PointF?, score: (String) -> Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Double.MAX_VALUE
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < MAX_NODES) {
                val node = queue.removeFirst()
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                val label = normalize((node.text ?: node.contentDescription)?.toString().orEmpty())
                if (label.isEmpty()) continue
                val match = score(label)
                if (match == NO_MATCH) continue
                val target = clickableAncestor(node) ?: continue
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                if (bounds.isEmpty) continue
                val distance = near?.let { hypot(bounds.exactCenterX() - it.x, bounds.exactCenterY() - it.y) } ?: 0f
                val total = match * MATCH_WEIGHT + distance.toDouble()
                if (total < bestScore) {
                    best = target
                    bestScore = total
                }
            }
        }
        return best
    }

    // okna aplikacji pod spodem (przegladarka, Kahoot), bez wlasnych nakladek Dymka
    private fun roots(): List<AccessibilityNodeInfo> {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        return apps.ifEmpty { listOfNotNull(rootInActiveWindow) }.filter { it.packageName != packageName }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTORS) {
            val n = current ?: return null
            if (n.isClickable && n.isEnabled) return n
            current = n.parent
        }
        return null
    }

    private fun center(bounds: Rect) = PointF(bounds.exactCenterX(), bounds.exactCenterY())

    companion object {
        /** Wlaczona usluga albo null, gdy uzytkownik jej nie wlaczyl. */
        @Volatile
        var instance: TapService? = null
            private set

        private const val NO_MATCH = Int.MAX_VALUE
        // dokladny tekst wygrywa z czesciowym bez wzgledu na odleglosc od punktu AI
        private const val MATCH_WEIGHT = 100_000.0
        private const val MAX_NODES = 3000
        private const val MAX_ANCESTORS = 6

        private val SUBMIT_WORDS = setOf(
            "wyslij", "zatwierdz", "przeslij", "potwierdz", "glosuj", "gotowe", "submit", "send", "vote", "confirm", "done",
        )

        /** 0: ten sam tekst, 1: jeden zawiera drugi i nie jest duzo dluzszy (np. nie cale pytanie), inaczej [NO_MATCH]. */
        private fun similarity(label: String, wanted: String): Int {
            if (wanted.isEmpty()) return NO_MATCH
            if (label == wanted) return 0
            val (short, long) = if (label.length < wanted.length) label to wanted else wanted to label
            return if (short.length >= 2 && long.contains(short) && long.length <= short.length * 2 + 4) 1 else NO_MATCH
        }

        /** Male litery, bez polskich znakow, interpunkcji na brzegach i podwojnych spacji. */
        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('ł', 'l')
                .replace(Regex("\\s+"), " ")
                .trim { !it.isLetterOrDigit() }
    }
}
