package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.game.ObjVar
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev950ServerCodecsInventory() {
    serverProt<UpdateInvFull>(opcode = 9, size = ProtSize.VarShort) { out ->
        val hasVars = slots.any { it.objVars.isNotEmpty() }
        out.writeShort(containerKey)
        out.writeByte((if (hasVars) 2 else 0) or (keyFlag and 1))
        out.writeShort(slots.size)
        for (slot in slots) {
            out.writeMedium(slot.objId + 1)
            out.writeInventoryAmount(slot.amount)
            if (hasVars) out.writeObjVars(slot.objVars)
        }
    }

    serverProt<UpdateInvPartial>(opcode = 50, size = ProtSize.VarShort) { out ->
        val hasVars = slots.any { it.objVars.isNotEmpty() }
        out.writeShort(containerKey)
        out.writeByte((if (hasVars) 2 else 0) or (keyFlag and 1))
        for (slot in slots) {
            out.writeSmart(slot.slotIndex)
            out.writeMedium(slot.objId + 1)
            if (slot.objId >= 0) {
                out.writeInventoryAmount(slot.amount)
                if (hasVars) out.writeObjVars(slot.objVars)
            }
        }
    }

    serverProt<UpdateInvStopTransmit>(opcode = 20, size = 3) { out ->
        out.writeByteSubtract(keyFlag and 1)
        out.writeShortAdd(containerKey)
    }
}

private suspend fun ByteWriteChannel.writeObjVars(vars: List<ObjVar>) {
    writeByte(vars.size)
    for (v in vars) {
        writeShort(v.varId)
        writeInt(v.value)
    }
}
