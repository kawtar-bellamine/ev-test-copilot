package fr.artsetmetiers.evtestcopilot

import fr.artsetmetiers.evtestcopilot.voice.Intent
import fr.artsetmetiers.evtestcopilot.voice.IntentEngine
import fr.artsetmetiers.evtestcopilot.voice.TestSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentEngineTest {
    private val snap = TestSnapshot(
        speedKmh = 87.4, powerKw = -23.6, socPct = 74.2, tempC = 31.0, netWh = 4711.0, regenWh = 293.0,
        regenRatio = 0.0586, whPerKm = 166.3, distanceKm = 28.33, rangeKm = 268.0, linkOk = true,
    )

    @Test
    fun classifiesFrenchQuestions() {
        mapOf(
            "Quelle est la consommation ?" to Intent.CONSUMPTION,
            "Combien d'énergie on a récupéré au freinage ?" to Intent.REGEN,
            "Il reste quelle autonomie" to Intent.RANGE,
            "Niveau de batterie" to Intent.BATTERY,
            "Température des cellules ?" to Intent.TEMPERATURE,
            "Marque cet événement" to Intent.MARK,
            "Quelle distance parcourue" to Intent.DISTANCE,
            "On roule à quelle vitesse" to Intent.SPEED,
            "Fais-moi un bilan" to Intent.STATUS,
            "Bonjour" to Intent.HELP,
        ).forEach { (q, expected) -> assertEquals(q, expected, IntentEngine.classify(q)) }
    }

    @Test
    fun answersAreShortAndRounded() {
        assertEquals("Consommation 166 wattheures par kilomètre.", IntentEngine.answer("conso ?", snap).text)
        assertEquals("Régénération 24 kilowatts.", IntentEngine.answer("puissance", snap).text)
        assertEquals("Autonomie estimée 268 kilomètres.", IntentEngine.answer("autonomie", snap).text)
        assertTrue(IntentEngine.answer("bilan", snap).text.split(" ").size <= 12)
    }

    @Test
    fun markWorksWithoutVehicleData() {
        val r = IntentEngine.answer("marque", snap.copy(linkOk = false))
        assertTrue(r.markEvent)
        assertEquals("Pas de données du véhicule pour le moment.", IntentEngine.answer("batterie", snap.copy(linkOk = false)).text)
    }
}
