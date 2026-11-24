package fr.artsetmetiers.evtestcopilot

import fr.artsetmetiers.evtestcopilot.energy.EnergyMeter
import fr.artsetmetiers.evtestcopilot.protocol.EnergyPacket
import fr.artsetmetiers.evtestcopilot.protocol.PacketAssembler
import fr.artsetmetiers.evtestcopilot.protocol.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.math.abs

class EnergyMeterTest {
    private val golden = Properties().apply {
        EnergyMeterTest::class.java.classLoader!!.getResourceAsStream("golden.properties").use { load(it) }
    }
    private fun g(k: String) = golden.getProperty(k).toDouble()

    private fun sample(t: Long, v: Double, a: Double, speed: Double = 50.0) =
        SamplePacket(0, t, 3, false, speed, v, a, 80.0, 25.0)

    @Test
    fun phoneMatchesEmbeddedComputation() {
        val packets = PacketAssembler().feed(File("src/main/assets/demo_drive.bin").readBytes())
        val m = EnergyMeter()
        packets.filterIsInstance<SamplePacket>().forEach(m::update)
        assertEquals(g("traction_wh"), m.tractionWh, 0.05)
        assertEquals(g("regen_wh"), m.regenWh, 0.05)
        assertEquals(g("distance_km"), m.distanceKm, 0.001)
        assertEquals(g("wh_per_km"), m.whPerKm, 0.01)
        val box = packets.filterIsInstance<EnergyPacket>().last()
        assertEquals(box.tractionWh, m.tractionWh, 1.0)
    }

    @Test
    fun netConsumptionCloseToSimulatorTruth() {
        val packets = PacketAssembler().feed(File("src/main/assets/demo_drive.bin").readBytes())
        val m = EnergyMeter()
        packets.filterIsInstance<SamplePacket>().forEach(m::update)
        val truthNet = g("truth_traction_wh") - g("truth_regen_wh")
        assertTrue(abs(m.netWh - truthNet) / truthNet < 0.01)
        assertTrue(abs(m.distanceKm - g("truth_distance_km")) < 0.01)
    }

    @Test
    fun splitsTrapezoidAtZeroCrossing() {
        val m = EnergyMeter()
        // +40 kW puis -40 kW en 1 s : 2,78 Wh de chaque côté, et non zéro
        m.update(sample(0, 400.0, 100.0))
        m.update(sample(1000, 400.0, -100.0))
        assertEquals(40.0 * 0.5 * 0.5 / 3.6, m.tractionWh, 1e-6)
        assertEquals(40.0 * 0.5 * 0.5 / 3.6, m.regenWh, 1e-6)
    }

    @Test
    fun ignoresGapsAndStaleSamples() {
        val m = EnergyMeter()
        m.update(sample(0, 400.0, 100.0))
        m.update(sample(5000, 400.0, 100.0)) // trou de 5 s : ignoré
        assertEquals(0.0, m.tractionWh, 0.0)
        m.update(SamplePacket(0, 5100, 3, true, 50.0, 400.0, 100.0, 80.0, 25.0))
        assertEquals(0.0, m.tractionWh, 0.0)
    }

    @Test
    fun rangeUsesCurrentConsumption() {
        val m = EnergyMeter(usableKwh = 60.0)
        assertEquals(0.0, m.rangeKm(80.0), 0.0)
        var t = 0L
        repeat(3600) { m.update(sample(t, 400.0, 50.0, speed = 100.0)); t += 1000 } // 20 kW à 100 km/h
        assertEquals(200.0, m.whPerKm, 0.5)
        assertEquals(240.0, m.rangeKm(80.0), 1.0)
    }
}
