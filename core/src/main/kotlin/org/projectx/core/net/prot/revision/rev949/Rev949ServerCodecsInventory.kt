package org.projectx.core.net.prot.revision.rev949

import io.ktor.utils.io.*
import org.projectx.core.game.ObjVar
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

// 949 (build >= 0x3b5) widened the obj-id field from a 2-byte short to a 3-byte medium in both
// inventory packets — see the DAT_0144460c gate in the FUN_001aaed0 / FUN_00185a20 decoders.
internal fun Codec.registerRev949ServerCodecsInventory() {
    serverProt<UpdateInvFull>(opcode = 8, size = ProtSize.VarShort) { out ->
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

    serverProt<UpdateInvPartial>(opcode = 43, size = ProtSize.VarShort) { out ->
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
}

private suspend fun ByteWriteChannel.writeObjVars(vars: List<ObjVar>) {
    writeByte(vars.size)
    for (v in vars) {
        writeShort(v.varId)
        writeInt(v.value)
    }
}
