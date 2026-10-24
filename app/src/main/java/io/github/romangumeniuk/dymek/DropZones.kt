package io.github.romangumeniuk.dymek

import kotlin.math.hypot
import kotlin.math.min

/** Miejsce, na ktore mozna upuscic przycisk: srodek zamyka aplikacje, pozostale wlaczaja tryb. */
enum class Drop(val mode: Mode?) {
    QUIT(null),
    QUICK(Mode.QUICK),
    ACCURATE(Mode.ACCURATE),
    BALANCED(Mode.BALANCED),
}

/**
 * Uklad miejsc do upuszczenia przycisku na ekranie [width] x [height] pikseli: Szybki w lewym gornym rogu,
 * Dokladny w prawym gornym, Zbalansowany na dole i zamykanie na srodku. Sama geometria, bez widokow.
 */
class DropZones(width: Int, height: Int, density: Float, withQuit: Boolean) {
    class Center(val x: Int, val y: Int)

    private val pull = PULL_DP * density

    val centers: Map<Drop, Center> = buildMap {
        // na niskim ekranie (poziomo) blizej brzegow, zeby kolka nie wchodzily na siebie
        val side = min(SIDE_DP * density, width * 0.15f).toInt()
        val top = min(TOP_DP * density, height * 0.2f).toInt()
        val bottom = min(BOTTOM_DP * density, height * 0.2f).toInt()
        put(Drop.QUICK, Center(side, top))
        put(Drop.ACCURATE, Center(width - side, top))
        put(Drop.BALANCED, Center(width / 2, height - bottom))
        if (withQuit) put(Drop.QUIT, Center(width / 2, height / 2))
    }

    /** Najblizsze miejsce, do ktorego przyciaga przycisk o srodku w (x, y), albo null. */
    fun nearest(x: Float, y: Float): Drop? =
        centers.entries
            .map { (drop, c) -> drop to hypot(x - c.x, y - c.y) }
            .filter { it.second < pull }
            .minByOrNull { it.second }
            ?.first

    companion object {
        const val TARGET_DP = 72
        const val TARGET_ACTIVE_DP = 88
        // z tej odleglosci od srodka miejsca przycisk sam do niego przeskakuje
        private const val PULL_DP = 64
        private const val SIDE_DP = 72
        private const val TOP_DP = 150
        private const val BOTTOM_DP = 170
    }
}
