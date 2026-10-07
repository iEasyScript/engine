package com.projectx.pathfinder

import java.util.concurrent.ConcurrentHashMap
import org.projectx.core.net.prot.ProtRevisions

// Decodes the REBUILD_REGION instance zone map: each packet describes one virtual mapsquare
// (WxH zones) as either void or a rotated copy of a source cache zone. Instance floor collision is
// rebuilt from the source zones' cache tile-flags; the client itself keeps no walk-collision grid.
object RebuildRegionMap {
    @Deprecated("Shipped API kept for binary compatibility; the decoder looks the packet up by name.")
    const val REBUILD_REGION_OPCODE = 5

    // Looked up by name from the newest protocol the engine speaks, so a game update cannot leave it on a stale opcode.
    private val rebuildRegion: Int? by lazy {
        ProtRevisions.currentCodec().serverProtInfo.entries.firstOrNull { it.value.name == "REBUILD_REGION" }?.key
    }

    sealed interface ZoneEntry
    data object Void : ZoneEntry
    data class Source(
        val sourceMapSquareX: Int,
        val sourceMapSquareY: Int,
        val sourceLocalZoneX: Int,
        val sourceLocalZoneY: Int,
        val level: Int,
        val rotation: Int,
    ) : ZoneEntry

    private val entries = ConcurrentHashMap<Int, ZoneEntry>()

    fun observe(opcode: Int, body: ByteArray) {
        if (opcode == rebuildRegion) decode(body)
    }

    fun entryOf(zoneX: Int, zoneY: Int, plane: Int): ZoneEntry? = entries[key(zoneX, zoneY, plane)]

    fun clear() = entries.clear()

    private fun decode(body: ByteArray) {
        if (body.size < HEADER_SIZE) return

        val baseZoneX = u16be(body, 8)
        val baseZoneY = u16be(body, 10)
        val width = body[12].toInt() and 0xFF
        val height = body[13].toInt() and 0xFF
        if (width !in 1..MAX_REGION_ZONES || height !in 1..MAX_REGION_ZONES) return
        // The zone grid takes at least a bit per zone; a body too short to hold it is not this packet.
        if ((body.size - HEADER_SIZE) * 8 < 4 * width * height) return

        val bits = BitReader(body, HEADER_SIZE)
        val decoded = HashMap<Int, ZoneEntry>(width * height * 4)
        for (plane in 0 until 4) {
            for (zoneXOffset in 0 until width) {
                for (zoneYOffset in 0 until height) {
                    val flag = bits.readBit()
                    if (flag < 0) return
                    val entry = if (flag == 1) {
                        val packed = bits.readBits(26)
                        if (packed < 0) return
                        val sourceZoneY = (packed ushr 3) and 0x7FF
                        val sourceZoneX = (packed ushr 14) and 0x3FF
                        Source(
                            sourceMapSquareX = sourceZoneX shr 3,
                            sourceMapSquareY = sourceZoneY shr 3,
                            sourceLocalZoneX = sourceZoneX and 7,
                            sourceLocalZoneY = sourceZoneY and 7,
                            level = (packed ushr 24) and 0x3,
                            rotation = (packed ushr 1) and 0x3,
                        )
                    } else {
                        Void
                    }
                    decoded[key(baseZoneX + zoneXOffset, baseZoneY + zoneYOffset, plane)] = entry
                }
            }
        }
        entries.putAll(decoded)
        DynamicMapSquareCollision.markDirty()
        println("[pathfinder] instance layout: ${width}x$height zones from zone ($baseZoneX, $baseZoneY)")
    }

    private fun key(zoneX: Int, zoneY: Int, plane: Int) = (zoneX shl 11) or zoneY or (plane shl 22)

    private fun u16be(b: ByteArray, offset: Int) =
        ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var bitPosition = startByte * 8
        private val bitLimit = data.size * 8

        fun readBit(): Int {
            if (bitPosition >= bitLimit) return -1
            val byte = data[bitPosition ushr 3].toInt() and 0xFF
            val bit = (byte ushr (7 - (bitPosition and 7))) and 1
            bitPosition++
            return bit
        }

        fun readBits(count: Int): Int {
            var value = 0
            repeat(count) {
                val bit = readBit()
                if (bit < 0) return -1
                value = (value shl 1) or bit
            }
            return value
        }
    }

    private const val HEADER_SIZE = 14

    // Width and height are single unsigned bytes; quest instances reach 24x24, so no smaller cap.
    private const val MAX_REGION_ZONES = 255
}
