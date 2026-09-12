package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.projectx.core.Logger.logWarn
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*
import kotlin.reflect.KClass

private val ZONE_SUBOPCODE: Map<KClass<out ServerProt>, Int> = mapOf(
    ObjAdd::class to 0,
    ObjAddBig::class to 0,
    MapAnim::class to 11,
    MapAnimAlt::class to 2,
    SoundArea::class to 4,
    MapProjAnimHalfsq::class to 5,
    ObjCount::class to 6,
    ObjReveal::class to 9,
    ObjDel::class to 10,
    LocAnim::class to 8,
    LocDel::class to 12,
    LocAdd::class to 13,
    MapProjAnim::class to 15,
    MapProjAnimAlt::class to 16,
    MapProjAnimHalfsqAlt::class to 17,
)

private suspend fun ByteWriteChannel.writeObjIdLittle(objId: Int) {
    writeByte(objId)
    writeByte(objId shr 8)
    writeByte(objId shr 16)
}

/** The graphic starts on the tick it arrives; the client counts this field in its own cycles. */
private const val NO_DELAY = 0

internal fun Codec.registerRev950ServerCodecsZone() {
    val codec = this

    serverProt<UpdateZoneFullFollows>(opcode = 2, size = 3) { out ->
        out.writeByteAdd(level)
        out.writeByte(zoneX)
        out.writeByte(zoneY)
    }

    serverProt<UpdateZonePartialFollows>(opcode = 96, size = 3) { out ->
        out.writeByte(zoneY)
        out.writeByteInverse(zoneX)
        out.writeByteAdd(level)
    }

    // Sub-packets are framed by a single opcode byte and nothing else: the client trusts each
    // handler's own cursor rather than a length, so a length prefix would desynchronise the batch.
    serverProt<UpdateZonePartialEnclosed>(opcode = 49, size = ProtSize.VarShort) { out ->
        out.writeByteSubtract(level)
        out.writeByteInverse(zoneX)
        out.writeByte(zoneY)
        for (sub in subPackets) {
            val subOp = ZONE_SUBOPCODE[sub::class]
            val subCodec = codec.serverProts[sub::class]
            if (subOp == null || subCodec?.encoder == null) {
                logWarn(
                    "UPDATE_ZONE_PARTIAL_ENCLOSED: no 950 zone sub-opcode/encoder for " +
                        "${sub::class.simpleName} — sub-packet dropped."
                )
                continue
            }
            val buf = Buffer()
            val ch = buf.asByteWriteChannel()
            subCodec.encoder.invoke(sub, ch)
            ch.flush()
            out.writeByte(subOp)
            out.writeBytes(buf.readByteArray())
        }
    }

    serverProt<ObjAdd>(opcode = 51, size = 6) { out ->
        out.writeObjIdLittle(objId)
        out.writeByteSubtract(packedCoord)
        out.writeShort(count)
    }

    serverProt<ObjAddBig>(opcode = 51, size = 6) { out ->
        out.writeObjIdLittle(objId)
        out.writeByteSubtract(packedCoord)
        out.writeShort(count)
    }

    serverProt<ObjCount>(opcode = 70, size = 8) { out ->
        out.writeByte(packedCoord)
        out.writeMedium(objId)
        out.writeShort(oldCount)
        out.writeShort(newCount)
    }

    serverProt<ObjDel>(opcode = 109, size = 4) { out ->
        out.writeByteSubtract(packedCoord)
        out.writeObjIdLittle(objIdLo or (objIdHi shl 8))
    }

    // Zone-only: 950 gives OBJ_REVEAL_BIG no standalone opcode of its own.
    zoneOnlyProt<ObjReveal>(size = 8) { out ->
        val objId = objIdLo or (objIdHi shl 8)
        out.writeByte(objId shr 8)
        out.writeByte(objId shr 16)
        out.writeByte(objId)
        out.writeShortLittle(playerIndex)
        out.writeShort(count)
        out.writeByteAdd(packedCoord)
    }

    // The trailing bit of the shape/rotation byte would append an optional transform block and
    // take the packet past its base length, so the encoder keeps it clear.
    serverProt<LocAnim>(opcode = 11, size = ProtSize.VarByte) { out ->
        out.writeByteAdd(packedCoord)
        out.writeIntInverseMiddle(seqId)
        out.writeByteAdd(shapeRotation and 0x7F)
    }

    serverProt<LocDel>(opcode = 26, size = 2) { out ->
        out.writeByteSubtract(packedCoord)
        out.writeByteAdd(shapeFlags and 0x7F)
    }

    serverProt<LocAdd>(opcode = 75, size = 7) { out ->
        out.writeByteInverse(shapeFlags and 0x7F)
        out.writeIntMiddle(locId)
        out.writeByteInverse(0)
        out.writeByteInverse(packedCoord)
    }

    serverProt<MapAnim>(opcode = 14, size = 11) { out ->
        out.writeByte(packedCoord)
        out.writeShort(graphicId)
        out.writeShort(heightOffset)
        out.writeShort(NO_DELAY)
        out.writeByte(rotationDirection and 0x7)
        // The client advances over the last three bytes without reading them.
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
    }

    serverProt<MapAnimAlt>(opcode = 188, size = 14) { out ->
        out.writeByte(packedCoord)
        out.writeShort(graphicId)
        out.writeShort(heightRotation)
        out.writeShort(flags)
        out.writeByte(heightOffset)
        out.skip(3)
        out.writeMedium(fineOffset)
    }

    serverProt<SoundArea>(opcode = 164, size = 10) { out ->
        out.writeByte(packedCoord)
        out.writeInt(soundId)
        out.writeByte(rotationDirection)
        out.writeByte(loopCount)
        out.writeByte(heightOffset)
        out.writeShort(scale)
    }

    serverProt<MapProjAnim>(opcode = 114, size = 20) { out ->
        out.writeByte(srcPackedCoord)
        out.writeByte(targetDeltaX)
        out.writeByte(targetDeltaY)
        out.writeMedium(lockOnId)
        out.writeShort(spotAnim)
        out.writeByte(startHeight)
        out.writeByte(endHeight)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(alpha)
        out.writeShort(lockOnSlot)
        out.skip(3)
    }

    serverProt<MapProjAnimHalfsq>(opcode = 154, size = 21) { out ->
        out.writeByte(srcPackedCoord)
        out.writeByte(flags)
        out.writeByte(destDeltaX)
        out.writeByte(destDeltaY)
        out.writeMedium(sourceId)
        out.writeMedium(lockOnId)
        out.writeShort(spotAnim)
        out.writeByte(startHeight)
        out.writeByte(endHeight)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(alpha)
        out.writeShort(lockOnSlot)
    }

    serverProt<MapProjAnimAlt>(opcode = 169, size = 28) { out ->
        out.writeByte(srcPackedCoord)
        out.writeByte(targetDeltaX)
        out.writeByte(targetDeltaY)
        out.writeMedium(lockOnId)
        out.writeShort(spotAnim)
        out.writeShort(startHeight)
        out.writeShort(endHeight)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(alpha)
        out.writeShort(lockOnSlot)
        out.skip(3)
        out.writeMedium(sourceOffset)
        out.writeMedium(destOffset)
    }

    serverProt<MapProjAnimHalfsqAlt>(opcode = 196, size = 29) { out ->
        out.writeByte(srcPackedCoord)
        out.writeByte(flags)
        out.writeByte(destDeltaX)
        out.writeByte(destDeltaY)
        out.writeMedium(sourceId)
        out.writeMedium(lockOnId)
        out.writeShort(spotAnim)
        out.writeShort(startHeight)
        out.writeShort(endHeight)
        out.writeShort(startTime)
        out.writeShort(endTime)
        out.writeByte(alpha)
        out.writeShort(lockOnSlot)
        out.writeMedium(sourceOffset)
        out.writeMedium(destOffset)
    }
}
