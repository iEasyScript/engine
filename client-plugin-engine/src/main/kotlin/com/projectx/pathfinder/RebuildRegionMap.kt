package com.projectx.pathfinder

import java.util.concurrent.ConcurrentHashMap

// Decodes the REBUILD_REGION (op93) instance zone map: each packet describes one virtual mapsquare
// (WxH zones) as either void or a rotated copy of a source cache zone. Instance floor collision is
// rebuilt from the source zones' cache tile-flags; the client itself keeps no walk-collision grid.
object RebuildRegionMap {
    const val REBUILD_REGION_OPCODE = 93

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
        if (opcode == REBUILD_REGION_OPCODE) decode(body)
    }

    fun entryOf(zoneX: Int, zoneY: Int, plane: Int): ZoneEntry? = entries[key(zoneX, zoneY, plane)]

    fun clear() = entries.clear()

    private fun decode(body: ByteArray) {
        if (body.size < HEADER_SIZE) return
        if ((body[2].toInt() and 0xFF) != VALIDITY_MAGIC) return

        val baseZoneX = u16be(body, 8)
        val baseZoneY = u16be(body, 10)
        val width = body[12].toInt() and 0xFF
        val height = body[13].toInt() and 0xFF
        if (width !in 1..MAX_REGION_ZONES || height !in 1..MAX_REGION_ZONES) return

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
    private const val VALIDITY_MAGIC = 5

    // op93 encodes the region width/height as single unsigned bytes, so 255 zones per axis is the full
    // protocol maximum. The bounds-safe BitReader aborts on any truncated/garbage packet, so no smaller
    // sanity cap is needed. (Quest-zone instances reach 24×24; the old 16 cap silently dropped them.)
    private const val MAX_REGION_ZONES = 255
}
