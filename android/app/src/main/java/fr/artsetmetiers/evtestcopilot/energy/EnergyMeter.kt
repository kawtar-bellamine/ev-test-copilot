package fr.artsetmetiers.evtestcopilot.energy

import fr.artsetmetiers.evtestcopilot.protocol.SamplePacket

/**
 * Calcul énergétique en temps réel sur le téléphone, identique à core/evtc.c :
 * intégration trapézoïdale de P = U x I, découpée au passage par zéro entre traction et régénération.
 */
class EnergyMeter(private val usableKwh: Double = 60.0) {
    var tractionWh = 0.0
        private set
    var regenWh = 0.0
        private set
    var distanceKm = 0.0
        private set
    private var lastT = -1L
    private var lastP = 0.0
    private var lastV = 0.0

    val netWh: Double get() = tractionWh - regenWh
    val whPerKm: Double get() = if (distanceKm > 0.1) netWh / distanceKm else 0.0
    val regenRatio: Double get() = if (tractionWh > 0.0) regenWh / tractionWh else 0.0

    fun rangeKm(socPct: Double): Double {
        val c = whPerKm
        return if (c > 1.0) socPct / 100.0 * usableKwh * 1000.0 / c else 0.0
    }

    fun reset() {
        tractionWh = 0.0
        regenWh = 0.0
        distanceKm = 0.0
        lastT = -1L
    }

    fun update(s: SamplePacket) {
        if (s.canStale) return
        val p = s.powerKw
        if (lastT >= 0) {
            val dtH = (s.tMs - lastT) / 3_600_000.0
            if (dtH > 0.0 && dtH < 2.0 / 3600.0) {
                val p0 = lastP
                var pos: Double
                var neg: Double
                if ((p0 >= 0.0) == (p >= 0.0)) {
                    val a = 0.5 * (p0 + p) * dtH
                    pos = maxOf(a, 0.0)
                    neg = maxOf(-a, 0.0)
                } else {
                    val f = p0 / (p0 - p)
                    val a0 = 0.5 * p0 * f * dtH
                    val a1 = 0.5 * p * (1.0 - f) * dtH
                    pos = maxOf(a0, 0.0) + maxOf(a1, 0.0)
                    neg = maxOf(-a0, 0.0) + maxOf(-a1, 0.0)
                }
                tractionWh += pos * 1000.0
                regenWh += neg * 1000.0
                distanceKm += 0.5 * (s.speedKmh + lastV) * dtH
            }
        }
        lastT = s.tMs
        lastP = p
        lastV = s.speedKmh
    }
}
