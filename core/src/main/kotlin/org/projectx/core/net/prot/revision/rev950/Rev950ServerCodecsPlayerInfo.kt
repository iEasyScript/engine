package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev950ServerCodecsPlayerInfo() {
    serverProt<PlayerInfo>(opcode = 36, size = ProtSize.VarShort) { out ->
        out.writeFully(bitBlock)
        for (block in extendedInfo) {
            out.writeShort(block.size)
            out.writeFully(block)
        }
    }
}
