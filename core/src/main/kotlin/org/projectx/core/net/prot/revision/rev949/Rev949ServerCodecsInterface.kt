package org.projectx.core.net.prot.revision.rev949

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev949ServerCodecsInterface() {
    serverProt<IfOpenTop>(opcode = 73, size = 19) { out ->
        out.skip(8)
        out.writeShortAddLittle(topLevelId)
        out.skip(9)
    }

    serverProt<IfOpenSub>(opcode = 25, size = 23) { out ->
        out.skip(4)
        out.writeByteSubtract(layer)
        out.skip(4)
        out.skip(4)
        out.writeShort(childId)
        out.skip(4)
        out.writeIntMiddle(parentHash)
    }

    serverProt<IfSetEvents>(opcode = 122, size = 12) { out ->
        val componentHash = (events.interfaceId shl 16) or (events.componentId and 0xFFFF)
        out.writeIntLittle(componentHash)
        out.writeShort(if (events.fromSlot == -1) 0xFFFF else events.fromSlot)
        out.writeIntInverseMiddle(events.settings)
        out.writeShortLittle(if (events.toSlot == -1) 0xFFFF else events.toSlot)
    }

    serverProt<IfOpenSubActiveLoc>(opcode = 98, size = 32) { out ->
        out.skip(4)
        out.skip(4)
        out.writeShortAddLittle(subId)
        out.skip(4)
        out.writeIntLittle(componentHash)
        out.skip(4)
        out.writeByteSubtract(angle)
        out.writeIntMiddle(locType)
        out.writeIntLittle(packedCoord)
        out.writeByteSubtract(layer)
    }

    serverProt<DoCheat>(opcode = 142, size = ProtSize.VarByte) { out ->
        out.writeRSString(imageUrl)
    }

    serverProt<IfSetNpcHead>(opcode = 17, size = 8) { out ->
        out.writeIntMiddle(colour24)
        out.writeIntLittle(componentHash)
    }

    serverProt<IfSetObjectLong>(opcode = 185, size = 14) { out ->
        out.writeInt(componentHash)
        out.writeIntLittle(part1)
        out.writeIntLittle(part2)
        out.writeShortAddLittle(npcId)
    }

    serverProt<IfOpenSubActiveObj>(opcode = 60, size = 29) { out ->
        out.writeFully(payload)
    }

    serverProt<TriggerOndialogabort>(opcode = 87, size = 0)

    serverProt<Cutscene2dPlay>(opcode = 221, size = 2) { out ->
        out.writeShort(id)
    }

    serverProt<IfSetText>(opcode = 34, size = ProtSize.VarShort) { out ->
        out.writeRSString(text)
        out.writeIntMiddle(componentHash)
    }

    serverProt<IfSetHide>(opcode = 124, size = 5) { out ->
        out.writeIntInverseMiddle(componentHash)
        out.writeByteAdd(if (hide) 1 else 0)
    }

    serverProt<IfCloseSub>(opcode = 107, size = 4) { out ->
        out.writeInt(componentHash)
    }

    serverProt<IfSetPlayerHead>(opcode = 24, size = 4) { out ->
        out.writeIntLittle(componentHash)
    }
}
