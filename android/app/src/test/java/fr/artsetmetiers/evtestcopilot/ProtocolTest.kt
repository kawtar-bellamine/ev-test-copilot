package fr.artsetmetiers.evtestcopilot

import fr.artsetmetiers.evtestcopilot.protocol.EnergyPacket
import fr.artsetmetiers.evtestcopilot.protocol.Packet
import fr.artsetmetiers.evtestcopilot.protocol.PacketAssembler
import fr.artsetmetiers.evtestcopilot.protocol.Protocol
import fr.artsetmetiers.evtestcopilot.protocol.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtocolTest {
    private val demo: ByteArray = File("src/main/assets/demo_drive.bin").readBytes()

    @Test
    fun crc16CcittReference() {
        // Valeur de référence CRC-16/CCITT-FALSE pour "123456789"
        val b = "123456789".toByteArray()
        assertEquals(0x29B1, Protocol.crc16(b, 0, b.size))
    }

    @Test
    fun decodesEveryDemoPacket() {
        val packets = PacketAssembler().feed(demo)
        assertEquals(demo.size / Protocol.LENGTH, packets.size)
        assertEquals(13093, packets.count { it is SamplePacket })
        assertEquals(1309, packets.count { it is EnergyPacket })
        val last = packets.filterIsInstance<SamplePacket>().last()
        assertTrue(last.packV in 300.0..450.0)
        assertEquals(74.15, last.socPct, 1e-9)
    }

    @Test
    fun rejectsCorruptedPacket() {
        val p = demo.copyOfRange(0, Protocol.LENGTH)
        p[9] = (p[9].toInt() xor 0x01).toByte()
        assertNull(Protocol.decode(p))
    }

    @Test
    fun resynchronisesOnSplitAndNoisyStream() {
        val noisy = byteArrayOf(0x00, 0xA5.toByte(), 0x13) + demo.copyOfRange(0, 2000)
        val asm = PacketAssembler()
        val out = mutableListOf<Packet>()
        // Morceaux de 7 octets, comme un lien BLE qui découpe arbitrairement
        var i = 0
        while (i < noisy.size) {
            val end = minOf(i + 7, noisy.size)
            out += asm.feed(noisy.copyOfRange(i, end))
            i = end
        }
        assertEquals(100, out.size)
        assertEquals(3, asm.rejectedBytes)
    }
}
