package org.projectx.core.net.prot.revision.rev949

import io.ktor.utils.io.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.projectx.core.Logger.logWarn
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*
import kotlin.reflect.KClass

private val ZONE_SUBOPCODE: Map<KClass<out ServerProt>, Int> = mapOf(
    SoundArea::class to 0,
    LocAdd::class to 1,
    ObjCount::class to 3,
    MapProjAnim::class to 4,
    MapProjAnimHalfsq::class to 5,
    MapProjAnimHalfsqAlt::class to 16,
    ObjDel::class to 7,
    ObjAdd::class to 9,
    MapAnimAlt::class to 10,
    ObjReveal::class to 12,
    LocAnim::class to 14,
    MapAnim::class to 15,
)

internal fun Codec.registerRev949ServerCodecsZone() {
    val codec = this

    serverProt<UpdateZoneFullFollows>(opcode = 28, size = 3) { out ->
        out.writeByte(level)
        out.writeByte(zoneX)
        out.writeByteAdd(zoneY)
    }

    serverProt<UpdateZonePartialEnclosed>(opcode = 109, size = ProtSize.VarShort) { out ->
        out.writeByteInverse(level)
        out.writeByteSubtract(zoneX)
        out.writeByteSubtract(zoneY)
        for (sub in subPackets) {
            val subOp = ZONE_SUBOPCODE[sub::class]
            val subCodec = codec.serverProts[sub::class]
            if (subOp == null || subCodec?.encoder == null) {
                logWarn(
                    "UPDATE_ZONE_PARTIAL_ENCLOSED: no 949 zone sub-opcode/encoder for " +
                        "${sub::class.simpleName} — sub-packet dropped."
                )
                continue
            }
            val buf = Buffer()
            val ch = buf.asByteWriteChannel()
            subCodec.encoder.invoke(sub, ch)
            ch.flush()
            val payload = buf.readByteArray()
            out.writeByte(subOp)
            when (subCodec.size) {
                ProtSize.VarByte -> out.writeByte(payload.size)
                ProtSize.VarShort -> out.writeShort(payload.size)
                is ProtSize.Fixed -> {}
            }
            out.writeBytes(payload)
        }
    }

    serverProt<LocAdd>(opcode = 149, size = ProtSize.VarByte) { out ->
        out.writeByte(0)
        out.writeByteAdd(packedCoord)
        out.writeIntMiddle(locId)
        out.writeByteAdd(shapeFlags)
    }

    serverProt<ObjAdd>(opcode = 41, size = 5) { out ->
        out.writeByte(packedCoord)
        out.writeByteAdd(count and 0xFF)
        out.writeByte((count shr 8) and 0xFF)
        out.writeShort(objId)
    }

    serverProt<ObjAddBig>(opcode = 112, size = 6) { out ->
        out.writeByte((objId shr 8) and 0xFF)
        out.writeByte((objId shr 16) and 0xFF)
        out.writeByte(objId and 0xFF)
        out.writeByte((count shr 8) and 0xFF)
        out.writeByteAdd(count and 0xFF)
        out.writeByteInverse(packedCoord)
    }

    serverProt<ObjDel>(opcode = 14, size = 3) { out ->
        out.writeByte(packedCoord)
        out.writeByte(objIdLo)
        out.writeByte(objIdHi)
    }

    serverProt<ObjCount>(opcode = 202, size = 7) { out ->
        out.writeByte(packedCoord)
        out.writeShort(objId)
        out.writeShort(oldCount)
        out.writeShort(newCount)
    }

    serverProt<ObjReveal>(opcode = 157, size = 7) { out ->
        out.writeShort(count)
        out.writeByte(objIdHi)
        out.writeByteAdd(objIdLo)
        out.writeByteInverse(packedCoord)
        out.writeShortLittle(playerIndex)
    }

    serverProt<MapAnim>(opcode = 173, size = 11) { out ->
        out.writeByte(packedCoord)
        out.writeInt(graphicId)
        out.writeByte(rotationDirection)
        out.writeByte(loopCount)
        out.writeByte(heightOffset)
        out.writeShort(scale)
        out.writeByte(selector)
    }

    serverProt<MapAnimAlt>(opcode = 183, size = 14) { out ->
        out.writeByte(packedCoord)
        out.writeShort(graphicId)
        out.writeShort(heightRotation)
        out.writeShort(flags)
        out.writeByte(heightOffset)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeMedium(fineOffset)
    }

    serverProt<SoundArea>(opcode = 160, size = 10) { out ->
        out.writeByte(packedCoord)
        out.writeInt(soundId)
        out.writeByte(rotationDirection)
        out.writeByte(loopCount)
        out.writeByte(heightOffset)
        out.writeShort(scale)
    }

    // Trailing 3 bytes are consumed but never read by the handler; the size is still fixed at 20.
    serverProt<MapProjAnim>(opcode = 132, size = 20) { out ->
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
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
    }

    serverProt<MapProjAnimHalfsq>(opcode = 168, size = 21) { out ->
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

    // Standalone only: 949 dropped this one from the zone sub-op vector, so it is deliberately
    // absent from ZONE_SUBOPCODE and can never ride a batch. Bytes 19..21 are consumed but unread.
    serverProt<MapProjAnimAlt>(opcode = 215, size = 28) { out ->
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
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeMedium(sourceOffset)
        out.writeMedium(destOffset)
    }

    serverProt<MapProjAnimHalfsqAlt>(opcode = 163, size = 29) { out ->
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

    zoneOnlyProt<LocAnim>(size = 7) { out ->
        out.writeByteAdd(shapeRotation or 0x80)
        out.writeIntLittle(seqId)
        out.writeByteInverse(packedCoord)
        out.writeByte(0)
    }
}
