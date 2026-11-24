package fr.artsetmetiers.evtestcopilot.protocol

/** Trames de 20 octets envoyées par le boîtier d'acquisition (même format que core/evtc.c). */
sealed interface Packet {
    val seq: Int
    val tMs: Long
}

data class SamplePacket(
    override val seq: Int,
    override val tMs: Long,
    val gear: Int,
    val canStale: Boolean,
    val speedKmh: Double,
    val packV: Double,
    val packA: Double,
    val socPct: Double,
    val tempC: Double,
) : Packet {
    /** Puissance batterie en kW : positive en traction, négative en régénération. */
    val powerKw: Double get() = packV * packA / 1000.0
}

data class EnergyPacket(
    override val seq: Int,
    override val tMs: Long,
    val tractionWh: Double,
    val regenWh: Double,
    val distanceKm: Double,
) : Packet

object Protocol {
    const val LENGTH = 20
    const val HEADER = 0xA5
    const val TYPE_SAMPLE = 0x01
    const val TYPE_ENERGY = 0x02
    private const val FLAG_CAN_STALE = 0x80

    /** CRC16-CCITT, polynôme 0x1021, valeur initiale 0xFFFF. */
    fun crc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()
    private fun u32(b: ByteArray, i: Int): Long =
        u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    fun isValid(b: ByteArray, offset: Int = 0): Boolean =
        b.size - offset >= LENGTH && u8(b, offset) == HEADER &&
            u16(b, offset + 18) == crc16(b, offset, 18)

    /** Décode une trame ; retourne null si l'en-tête, la longueur ou le CRC sont incorrects. */
    fun decode(b: ByteArray, offset: Int = 0): Packet? {
        if (!isValid(b, offset)) return null
        val o = offset
        val seq = u8(b, o + 2)
        val t = u32(b, o + 4)
        return when (u8(b, o + 1)) {
            TYPE_SAMPLE -> {
                val gearByte = u8(b, o + 3)
                SamplePacket(
                    seq = seq,
                    tMs = t,
                    gear = gearByte and 0x7F,
                    canStale = gearByte and FLAG_CAN_STALE != 0,
                    speedKmh = u16(b, o + 8) / 100.0,
                    packV = u16(b, o + 10) / 100.0,
                    packA = s16(b, o + 12) / 10.0,
                    socPct = u16(b, o + 14) / 100.0,
                    tempC = s16(b, o + 16) / 10.0,
                )
            }
            TYPE_ENERGY -> EnergyPacket(
                seq = seq,
                tMs = t,
                tractionWh = u32(b, o + 8) / 10.0,
                regenWh = u32(b, o + 12) / 10.0,
                distanceKm = u16(b, o + 16) / 100.0,
            )
            else -> null
        }
    }
}

/**
 * Reconstitue les trames à partir d'un flux d'octets : le module BLE transparent peut couper ou regrouper les trames.
 * On se resynchronise sur l'en-tête 0xA5 et on ne garde que les trames dont le CRC est correct.
 */
class PacketAssembler {
    private var buffer = ByteArray(0)
    var rejectedBytes = 0
        private set

    fun feed(chunk: ByteArray): List<Packet> {
        buffer += chunk
        val out = mutableListOf<Packet>()
        var i = 0
        while (buffer.size - i >= Protocol.LENGTH) {
            if ((buffer[i].toInt() and 0xFF) == Protocol.HEADER) {
                val packet = Protocol.decode(buffer, i)
                if (packet != null) {
                    out += packet
                    i += Protocol.LENGTH
                    continue
                }
            }
            i += 1
            rejectedBytes += 1
        }
        buffer = buffer.copyOfRange(i, buffer.size)
        return out
    }
}
