package org.projectx.core.net.prot.revision.rev949

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev949ServerCodecsVariable() {
    serverProt<VarpSmall>(opcode = 95, size = 3) { out ->
        out.writeByte(value)
        out.writeShortAddLittle(id)
    }

    serverProt<VarpLarge>(opcode = 78, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShort(id)
    }

    serverProt<VarpLong>(opcode = 158, size = 10) { out ->
        out.writeShort(id)
        out.writeIntInverseMiddle((value ushr 32).toInt())
        out.writeIntInverseMiddle(value.toInt())
    }

    serverProt<VarpBitSmall>(opcode = 22, size = 3) { out ->
        out.writeShort(id)
        out.writeByteSubtract(value)
    }

    serverProt<VarpBitLarge>(opcode = 51, size = 6) { out ->
        out.writeIntInverseMiddle(value)
        out.writeShortAdd(id)
    }

    serverProt<ClientSetVarcSmall>(opcode = 6, size = 3) { out ->
        out.writeByteInverse(value)
        out.writeShortAdd(id)
    }

    serverProt<ClientSetVarcLarge>(opcode = 40, size = 6) { out ->
        out.writeShortLittle(id)
        out.writeInt(value)
    }

    serverProt<ClientSetVarcBitSmall>(opcode = 54, size = 3) { out ->
        out.writeByte(value)
        out.writeShort(id)
    }

    serverProt<ClientSetVarcBitLarge>(opcode = 50, size = 6) { out ->
        out.writeIntLittle(value)
        out.writeShortAddLittle(id)
    }

    serverProt<UpdateStat>(opcode = 4, size = 6) { out ->
        out.writeIntLittle(xp)
        out.writeByteAdd(level)
        out.writeByteInverse(skillId)
    }

    serverProt<UpdateRunEnergy>(opcode = 92, size = 1) { out ->
        out.writeByte(energy)
    }

    serverProt<SetTargetMarker>(opcode = 2, size = 10) { out ->
        out.writeByte(if (clear) 0xff else 0)
        out.writeByte(playerLocalY)
        out.writeByteInverse(playerLocalX)
        out.writeByteSubtract(if (clear) 0 else targetType)
        out.writeByteSubtract(if (clear) -1 else targetLocalY)
        out.writeByteInverse(if (clear) -1 else targetLocalX)
        out.writeShortLittle(if (clear) -1 else 0)
        out.writeShortLittle(if (clear) -1 else targetIndex)
    }

    serverProt<ClientSetVarcStr>(opcode = 56, size = ProtSize.VarByte) { out ->
        out.writeRSString(value)
        out.writeShort(id)
    }

    serverProt<ClientSetVarcStrLarge>(opcode = 96, size = ProtSize.VarShort) { out ->
        out.writeShort(id)
        out.writeRSString(value)
    }

    serverProt<ResetClientVarcache>(opcode = 20, size = 0)
    serverProt<StoreServerpermVarcsAck>(opcode = 200, size = 0)
}
