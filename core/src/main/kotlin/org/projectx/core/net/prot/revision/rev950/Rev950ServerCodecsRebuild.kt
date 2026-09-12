package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.buffer.write.BufferWriter

private const val MAP_SIZE = 5

internal fun Codec.registerRev950ServerCodecsRebuild() {
    serverProt<RebuildNormalSimple>(opcode = 63, size = ProtSize.VarShort) { out ->
        gpiInit?.let { out.writeFully(it) }
        out.writeShortAdd(playerCoordY)
        out.writeByte(MAP_SIZE)
        out.writeByteAdd(npcInfoCoordBitWidth)
        out.writeShort(0)
        out.writeShort(playerCoordX)
        out.writeShort(worldAreaTypeId)
        out.writeInt(worldSouthWest.id)
        out.writeInt(worldNorthEast.id)
    }

    serverProt<RebuildRegion>(opcode = 5, size = ProtSize.VarShort) { out ->
        out.writeShortLittle(centerZoneY)
        out.writeByte(0)
        out.writeByte(npcInfoCoordBitWidth)
        out.writeByte(MAP_SIZE)
        out.writeByteAdd(type)
        out.writeShortLittle(centerZoneX)
        out.writeShort(baseZoneX)
        out.writeShort(baseZoneY)
        out.writeByte(widthZones)
        out.writeByte(heightZones)

        val bits = BufferWriter(4 * widthZones * heightZones * 4 + 16)
        bits.startBitAccess()
        for (plane in 0 until 4) {
            for (zoneX in 0 until widthZones) {
                for (zoneY in 0 until heightZones) {
                    val packed = packedZones[((plane * widthZones) + zoneX) * heightZones + zoneY]
                    if (packed == RebuildRegion.VOID) {
                        bits.writeBits(1, 0)
                    } else {
                        bits.writeBits(1, 1)
                        bits.writeBits(26, packed)
                    }
                }
            }
        }
        bits.stopBitAccess()
        out.writeFully(bits.toArray())
    }
}
