package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev950ServerCodecsVariable() {
    serverProt<VarpSmall>(opcode = 79, size = 3) { out ->
        out.writeByte(value)
        out.writeShortAdd(id)
    }

    serverProt<VarpLarge>(opcode = 4, size = 6) { out ->
        out.writeShortAddLittle(id)
        out.writeIntMiddle(value)
    }

    serverProt<VarpLong>(opcode = 165, size = 10) { out ->
        out.writeIntInverseMiddle((value ushr 32).toInt())
        out.writeIntInverseMiddle(value.toInt())
        out.writeShort(id)
    }

    serverProt<VarpBitSmall>(opcode = 28, size = 3) { out ->
        out.writeByteAdd(value)
        out.writeShortLittle(id)
    }

    serverProt<VarpBitLarge>(opcode = 82, size = 6) { out ->
        out.writeInt(value)
        out.writeShortLittle(id)
    }

    serverProt<ClientSetVarcSmall>(opcode = 126, size = 3) { out ->
        out.writeShortLittle(id)
        out.writeByteSubtract(value)
    }

    serverProt<ClientSetVarcLarge>(opcode = 119, size = 6) { out ->
        out.writeShortAdd(id)
        out.writeIntInverseMiddle(value)
    }

    serverProt<ClientSetVarcBitSmall>(opcode = 48, size = 3) { out ->
        out.writeByteSubtract(value)
        out.writeShort(id)
    }

    serverProt<ClientSetVarcBitLarge>(opcode = 87, size = 6) { out ->
        out.writeIntLittle(value)
        out.writeShortAdd(id)
    }

    serverProt<ClientSetVarcStr>(opcode = 30, size = ProtSize.VarByte) { out ->
        out.writeShortAdd(id)
        out.writeRSString(value)
    }

    serverProt<ClientSetVarcStrLarge>(opcode = 81, size = ProtSize.VarShort) { out ->
        out.writeRSString(value)
        out.writeShortLittle(id)
    }

    serverProt<UpdateStat>(opcode = 92, size = 6) { out ->
        out.writeByteInverse(skillId)
        out.writeByteInverse(level)
        out.writeInt(xp)
    }

    serverProt<UpdateRunEnergy>(opcode = 21, size = 1) { out ->
        out.writeByte(energy)
    }

    serverProt<UpdateRunweight>(opcode = 7, size = 2) { out ->
        out.writeShort(kilograms)
    }

    serverProt<ResetClientVarcache>(opcode = 23, size = 0)
    serverProt<StoreServerpermVarcsAck>(opcode = 136, size = 0)
}
