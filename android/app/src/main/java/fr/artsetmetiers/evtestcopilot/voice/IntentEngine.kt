package fr.artsetmetiers.evtestcopilot.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Ce que l'assistant connaît de l'essai en cours au moment de la question. */
data class TestSnapshot(
    val speedKmh: Double,
    val powerKw: Double,
    val socPct: Double,
    val tempC: Double,
    val netWh: Double,
    val regenWh: Double,
    val regenRatio: Double,
    val whPerKm: Double,
    val distanceKm: Double,
    val rangeKm: Double,
    val linkOk: Boolean,
)

enum class Intent { CONSUMPTION, ENERGY, REGEN, RANGE, POWER, BATTERY, TEMPERATURE, SPEED, DISTANCE, MARK, STATUS, HELP }

data class Reply(val intent: Intent, val text: String, val markEvent: Boolean = false)

/**
 * Assistant conversationnel embarqué, conçu pour ne pas distraire l'ingénieur d'essais :
 * reconnaissance d'intention locale (aucune connexion nécessaire), réponses courtes à l'oral, chiffres arrondis.
 * Un modèle de langage (par exemple Azure OpenAI) peut compléter cette base pour les questions ouvertes.
 */
object IntentEngine {
    private val rules: List<Pair<Intent, List<String>>> = listOf(
        Intent.MARK to listOf("marque", "repere", "note", "evenement", "signale"),
        Intent.REGEN to listOf("recup", "regen", "freinage"),
        Intent.CONSUMPTION to listOf("consommation", "conso", "wh par", "par kilometre", "mange"),
        Intent.RANGE to listOf("autonomie", "rouler encore", "combien de kilometres reste"),
        Intent.ENERGY to listOf("energie", "kilowattheure", "wh consomme"),
        Intent.POWER to listOf("puissance", "kilowatt", "kw"),
        Intent.BATTERY to listOf("batterie", "charge", "soc", "pourcentage"),
        Intent.TEMPERATURE to listOf("temperature", "chaud", "degre"),
        Intent.DISTANCE to listOf("distance", "parcouru", "kilometres faits"),
        Intent.SPEED to listOf("vitesse", "roule a"),
        Intent.STATUS to listOf("etat", "bilan", "resume", "statut"),
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun classify(text: String): Intent {
        val t = normalize(text)
        for ((intent, keys) in rules) if (keys.any { t.contains(it) }) return intent
        return Intent.HELP
    }

    private fun n(x: Double): String = x.roundToInt().toString()
    private fun d1(x: Double): String = String.format(Locale.FRENCH, "%.1f", x)

    fun answer(text: String, s: TestSnapshot): Reply {
        val intent = classify(text)
        if (!s.linkOk && intent != Intent.MARK && intent != Intent.HELP) {
            return Reply(intent, "Pas de données du véhicule pour le moment.")
        }
        val reply = when (intent) {
            Intent.CONSUMPTION -> "Consommation ${n(s.whPerKm)} wattheures par kilomètre."
            Intent.ENERGY -> "${d1(s.netWh / 1000.0)} kilowattheures consommés."
            Intent.REGEN -> "${d1(s.regenWh / 1000.0)} kilowattheures récupérés, ${n(s.regenRatio * 100)} pour cent."
            Intent.RANGE -> "Autonomie estimée ${n(s.rangeKm)} kilomètres."
            Intent.POWER -> if (s.powerKw >= 0) "Traction ${n(s.powerKw)} kilowatts." else
                "Régénération ${n(-s.powerKw)} kilowatts."
            Intent.BATTERY -> "Batterie à ${n(s.socPct)} pour cent."
            Intent.TEMPERATURE -> "Cellules à ${n(s.tempC)} degrés."
            Intent.DISTANCE -> "${d1(s.distanceKm)} kilomètres parcourus."
            Intent.SPEED -> "${n(s.speedKmh)} kilomètres heure."
            Intent.MARK -> "Événement marqué."
            Intent.STATUS -> "${n(s.whPerKm)} wattheures par kilomètre, batterie ${n(s.socPct)} pour cent."
            Intent.HELP -> "Demandez la consommation, l'autonomie, la batterie, ou dites marque."
        }
        return Reply(intent, reply, markEvent = intent == Intent.MARK)
    }
}
