package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

private fun slot(value: Int) = if (value == -1) 0xFFFF else value

internal fun Codec.registerRev950ServerCodecsInterface() {
    serverProt<IfOpenTop>(opcode = 1, size = 19) { out ->
        out.writeShortLittle(topLevelId)
        out.skip(17)
    }

    serverProt<IfOpenSub>(opcode = 100, size = 23) { out ->
        out.writeInt(parentHash)
        out.skip(12)
        out.writeShortAddLittle(childId)
        out.writeByteSubtract(layer)
        out.skip(4)
    }

    serverProt<IfSetEvents>(opcode = 24, size = 12) { out ->
        val componentHash = (events.interfaceId shl 16) or (events.componentId and 0xFFFF)
        out.writeIntMiddle(events.settings)
        out.writeShort(slot(events.toSlot))
        out.writeShortAdd(slot(events.fromSlot))
        out.writeIntLittle(componentHash)
    }

    serverProt<IfSetTargetParam>(opcode = 71, size = 10) { out ->
        out.writeShortAdd(slot(startSlot))
        out.writeIntLittle(componentHash)
        out.writeShort(eventsMask)
        out.writeShortAdd(slot(endSlot))
    }

    // The leading byte packs shape into bits 2..6 and rotation into bits 0..1; bit 7 would append a
    // trailing transform block and take the packet past its declared fixed size.
    serverProt<IfOpenSubActiveLoc>(opcode = 111, size = 32) { out ->
        out.writeByte(angle and 0x7F)
        out.writeIntInverseMiddle(componentHash)
        out.skip(4)
        out.writeByteAdd(layer)
        out.writeIntMiddle(packedCoord)
        out.writeInt(locType)
        out.skip(4)
        out.writeShortAdd(subId)
        out.skip(8)
    }

    serverProt<IfSetNpcHead>(opcode = 25, size = 8) { out ->
        out.writeIntMiddle(componentHash)
        out.writeIntInverseMiddle(colour24)
    }

    serverProt<IfSetModel>(opcode = 65, size = 8) { out ->
        out.writeInt(componentHash)
        out.writeInt(value)
    }

    serverProt<IfSetColour>(opcode = 83, size = 6) { out ->
        out.writeIntMiddle(componentHash)
        out.writeShortAdd(rgb555)
    }

    serverProt<IfSetPlayerHead>(opcode = 38, size = 4) { out ->
        out.writeInt(componentHash)
    }

    serverProt<IfSetAnim>(opcode = 47, size = 8) { out ->
        out.writeIntInverseMiddle(animationId)
        out.writeIntInverseMiddle(componentHash)
    }

    serverProt<IfSetAngle>(opcode = 68, size = 10) { out ->
        out.writeShort(zoom)
        out.writeShortLittle(angleY)
        out.writeShort(angleX)
        out.writeIntMiddle(componentHash)
    }

    serverProt<IfSetObject>(opcode = 102, size = 11) { out ->
        out.writeIntMiddle(count)
        out.writeIntInverseMiddle(componentHash)
        out.writeByte(objId shr 16)
        out.writeByte(objId)
        out.writeByte(objId shr 8)
    }

    serverProt<IfSetObjectLong>(opcode = 173, size = 15) { out ->
        out.writeIntMiddle(part1)
        out.writeIntMiddle(part2)
        out.writeIntLittle(componentHash)
        out.writeByte(npcId shr 16)
        out.writeByte(npcId shr 8)
        out.writeByte(npcId)
    }

    serverProt<IfOpenSubActivePlayer>(opcode = 13, size = 25) { out ->
        out.writeFully(payload)
    }

    serverProt<IfOpenSubActiveNpc>(opcode = 42, size = 25) { out ->
        out.writeFully(payload)
    }

    serverProt<IfOpenSubActiveObj>(opcode = 91, size = 30) { out ->
        out.writeFully(payload)
    }

    serverProt<IfSetPlayerModelSelf>(opcode = 101, size = 4) { out ->
        out.writeIntMiddle(componentHash)
    }

    serverProt<IfSetHide>(opcode = 67, size = 5) { out ->
        out.writeIntMiddle(componentHash)
        out.writeByte(if (hide) 1 else 0)
    }

    serverProt<IfSetText>(opcode = 115, size = ProtSize.VarShort) { out ->
        out.writeInt(componentHash)
        out.writeRSString(text)
    }

    serverProt<IfCloseSub>(opcode = 69, size = 4) { out ->
        out.writeIntInverseMiddle(componentHash)
    }

    serverProt<DoCheat>(opcode = 147, size = ProtSize.VarByte) { out ->
        out.writeRSString(imageUrl)
    }

    serverProt<Cutscene2dPlay>(opcode = 205, size = 2) { out ->
        out.writeShort(id)
    }

    serverProt<TriggerOndialogabort>(opcode = 124, size = 0)
}
