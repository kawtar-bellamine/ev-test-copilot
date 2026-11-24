package fr.artsetmetiers.evtestcopilot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.artsetmetiers.evtestcopilot.ble.BleLink
import fr.artsetmetiers.evtestcopilot.energy.EnergyMeter
import fr.artsetmetiers.evtestcopilot.protocol.EnergyPacket
import fr.artsetmetiers.evtestcopilot.protocol.Packet
import fr.artsetmetiers.evtestcopilot.protocol.PacketAssembler
import fr.artsetmetiers.evtestcopilot.protocol.SamplePacket
import fr.artsetmetiers.evtestcopilot.voice.IntentEngine
import fr.artsetmetiers.evtestcopilot.voice.TestSnapshot
import fr.artsetmetiers.evtestcopilot.voice.VoiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Source { NONE, DEMO, BLUETOOTH }

data class TestEvent(val tMs: Long, val label: String)

data class UiState(
    val source: Source = Source.NONE,
    val linkState: String = "Choisissez une source de données",
    val linkOk: Boolean = false,
    val speedKmh: Double = 0.0,
    val powerKw: Double = 0.0,
    val socPct: Double = 0.0,
    val tempC: Double = 0.0,
    val gear: Int = 0,
    val tractionWh: Double = 0.0,
    val regenWh: Double = 0.0,
    val netWh: Double = 0.0,
    val whPerKm: Double = 0.0,
    val regenRatio: Double = 0.0,
    val distanceKm: Double = 0.0,
    val rangeKm: Double = 0.0,
    val boxNetWh: Double? = null,
    val elapsedS: Long = 0,
    val powerHistory: List<Float> = emptyList(),
    val events: List<TestEvent> = emptyList(),
    val voiceState: String = "Appuyez sur le micro et posez votre question",
    val lastQuestion: String = "",
    val lastReply: String = "",
    val rejectedBytes: Int = 0,
)

/**
 * Cœur de l'application : reçoit les trames (Bluetooth ou démo), recalcule l'énergie, alimente l'écran
 * et répond aux questions de l'ingénieur d'essais.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val HISTORY_POINTS = 600 // 60 s à 10 Hz
        const val DEMO_SPEEDUP = 4L
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val meter = EnergyMeter()
    private var assembler = PacketAssembler()
    private var demoJob: Job? = null
    private var t0 = -1L
    private val history = ArrayDeque<Float>()

    private val ble = BleLink(
        app,
        onBytes = { bytes -> viewModelScope.launch(Dispatchers.Main) { ingest(bytes) } },
        onState = { msg -> viewModelScope.launch(Dispatchers.Main) { setLink(msg) } },
    )

    private val voice = VoiceController(
        app,
        onHeard = { text -> onQuestion(text) },
        onState = { msg -> _state.update { it.copy(voiceState = msg) } },
    )

    private fun setLink(msg: String) {
        _state.update { it.copy(linkState = msg, linkOk = msg == "Boîtier connecté" || it.source == Source.DEMO) }
    }

    private fun resetTest(source: Source) {
        demoJob?.cancel()
        ble.disconnect()
        meter.reset()
        assembler = PacketAssembler()
        history.clear()
        t0 = -1L
        _state.value = UiState(source = source)
    }

    fun startDemo() {
        resetTest(Source.DEMO)
        _state.update { it.copy(linkState = "Démo : essai enregistré, accéléré x$DEMO_SPEEDUP", linkOk = true) }
        demoJob = viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                getApplication<Application>().assets.open("demo_drive.bin").use { it.readBytes() }
            }
            val packets = PacketAssembler().feed(bytes)
            var prevT = -1L
            for (p in packets) {
                if (!isActive) break
                if (prevT >= 0 && p.tMs > prevT) delay((p.tMs - prevT) / DEMO_SPEEDUP)
                prevT = p.tMs
                handle(p)
            }
            _state.update { it.copy(linkState = "Démo terminée") }
        }
    }

    fun connectBluetooth() {
        resetTest(Source.BLUETOOTH)
        ble.connect()
    }

    private fun ingest(bytes: ByteArray) {
        for (p in assembler.feed(bytes)) handle(p)
        _state.update { it.copy(rejectedBytes = assembler.rejectedBytes) }
    }

    private fun handle(p: Packet) {
        when (p) {
            is SamplePacket -> onSample(p)
            is EnergyPacket -> _state.update { it.copy(boxNetWh = p.tractionWh - p.regenWh) }
        }
    }

    private fun onSample(s: SamplePacket) {
        if (t0 < 0) t0 = s.tMs
        meter.update(s)
        history.addLast(s.powerKw.toFloat())
        while (history.size > HISTORY_POINTS) history.removeFirst()
        _state.update {
            it.copy(
                linkOk = !s.canStale,
                speedKmh = s.speedKmh,
                powerKw = s.powerKw,
                socPct = s.socPct,
                tempC = s.tempC,
                gear = s.gear,
                tractionWh = meter.tractionWh,
                regenWh = meter.regenWh,
                netWh = meter.netWh,
                whPerKm = meter.whPerKm,
                regenRatio = meter.regenRatio,
                distanceKm = meter.distanceKm,
                rangeKm = meter.rangeKm(s.socPct),
                elapsedS = (s.tMs - t0) / 1000,
                powerHistory = history.toList(),
            )
        }
    }

    fun listen() = voice.listen()

    /** Traite une question (vocale ou tapée) et lit la réponse à voix haute. */
    fun onQuestion(text: String) {
        val s = _state.value
        val snapshot = TestSnapshot(
            speedKmh = s.speedKmh, powerKw = s.powerKw, socPct = s.socPct, tempC = s.tempC,
            netWh = s.netWh, regenWh = s.regenWh, regenRatio = s.regenRatio, whPerKm = s.whPerKm,
            distanceKm = s.distanceKm, rangeKm = s.rangeKm, linkOk = s.linkOk && s.source != Source.NONE,
        )
        val reply = IntentEngine.answer(text, snapshot)
        _state.update {
            it.copy(
                lastQuestion = text,
                lastReply = reply.text,
                voiceState = "Appuyez sur le micro et posez votre question",
                events = if (reply.markEvent) it.events + TestEvent(it.elapsedS * 1000, "Repère vocal à ${"%.1f".format(it.distanceKm)} km")
                else it.events,
            )
        }
        voice.speak(reply.text)
    }

    fun mark() = onQuestion("marque")

    override fun onCleared() {
        demoJob?.cancel()
        ble.disconnect()
        voice.release()
    }
}
