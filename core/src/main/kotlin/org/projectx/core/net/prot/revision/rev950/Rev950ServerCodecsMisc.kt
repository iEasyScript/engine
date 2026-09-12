package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*

internal fun Codec.registerRev950ServerCodecsMisc() {
    serverProt<FriendStatus>(opcode = 3, size = ProtSize.VarShort) { out ->
        for (friend in updates) {
            out.writeByte(friend.warnMessage)
            out.writeRSString(friend.displayName)
            out.writeRSString(friend.previousName)
            out.writeShort(friend.worldId)
            out.writeByte(friend.fcRank)
            out.writeByte(friend.flags)
            if (friend.worldId > 0) {
                out.writeRSString(friend.worldName)
                out.writeByte(friend.platform)
                out.writeInt(friend.worldFlags)
            }
            out.writeRSString(friend.notes)
        }
    }

    serverProt<FriendlistLoaded>(opcode = 93, size = 0)

    serverProt<ServerTickEnd>(opcode = 160, size = 0)
    serverProt<LobbyTickEnd>(opcode = 183, size = 0)

    serverProt<SetMoveAction>(opcode = 123, size = ProtSize.VarByte) { }

    serverProt<ReduceNpcAttackPriority>(opcode = 195, size = 1) { out ->
        out.writeByteAdd(level)
    }

    serverProt<JcoinsUpdate>(opcode = 153, size = 4) { out ->
        out.writeInt(balance)
    }

    // The client fills its argument slots from the last type character backwards, so the values go
    // out in reverse declaration order and the script id trails them.
    serverProt<RunClientScript>(opcode = 35, size = ProtSize.VarShort) { out ->
        out.writeRSString(types)
        for (i in args.indices.reversed()) {
            when (val arg = args[i]) {
                is Int -> out.writeInt(arg)
                is String -> out.writeRSString(arg)
                is Long -> out.writeLong(arg)
            }
        }
        out.writeInt(scriptId)
    }

    serverProt<SetTargetMarker>(opcode = 110, size = 10) { out ->
        out.writeByteSubtract(if (clear) 0 else targetLocalX)
        out.writeByteAdd(if (clear) -1 else targetType)
        out.writeByte(0)
        out.writeByteInverse(if (clear) 0 else playerLocalY)
        out.writeIntMiddle(if (clear) 0 else targetIndex)
        out.writeByteSubtract(if (clear) -1 else playerLocalX)
        out.writeByteSubtract(if (clear) 0 else targetLocalY)
    }

    serverProt<WorldListPacket>(opcode = 129, size = ProtSize.VarShort) { out ->
        val worlds = worldList.getWorldArray()
        val minWorldId = worlds.minOfOrNull { it.number } ?: 0
        val maxWorldId = worlds.maxOfOrNull { it.number } ?: 0

        out.writeByte(1)
        out.writeByte(2)

        if (fullRefresh) {
            out.writeByte(1)

            val countries = worlds.map { it.country }.distinct()
            out.writeSmart(countries.size)
            for (country in countries) {
                out.writeSmart(country.id)
                out.writeJagString(country.name.lowercase().replaceFirstChar { it.uppercase() })
            }

            out.writeSmart(minWorldId)
            out.writeSmart(maxWorldId)
            out.writeSmart(worlds.size)

            for (world in worlds) {
                out.writeSmart(world.number - minWorldId)
                out.writeByte(countries.indexOf(world.country))

                var flags = 0
                if (world.members) flags = flags or 0x1
                if (world.quickchat) flags = flags or 0x2
                if (world.pvp) flags = flags or 0x4
                if (world.lootShare) flags = flags or 0x8
                if (world.highlighted) flags = flags or 0x10
                out.writeInt(flags)

                out.writeSmart(0)
                out.writeJagString(world.activity)
                out.writeJagString(world.hostname)
            }

            out.writeInt(worldList.revision)
        } else {
            out.writeByte(0)
        }

        for (world in worlds) {
            out.writeSmart(world.number - minWorldId)
            out.writeShort(if (world.offline) -1 else world.playersOnline)
        }
    }

    serverProt<CamReset>(opcode = 72, size = 0)

    serverProt<CamSmoothreset>(opcode = 106, size = 0)

    serverProt<ResetAnims>(opcode = 113, size = 0)

    serverProt<Js5Reload>(opcode = 202, size = 0)

    serverProt<MinimapToggle>(opcode = 32, size = 1) { out ->
        out.writeByte(state)
    }

    serverProt<Logout>(opcode = 46, size = 1) { out ->
        out.writeByte(reason)
    }

    serverProt<LogoutFull>(opcode = 73, size = 1) { out ->
        out.writeByte(reason)
    }

    serverProt<Setdraworder>(opcode = 192, size = 1) { out ->
        out.writeByte(order)
    }

    serverProt<UpdateRebootTimer>(opcode = 31, size = 2) { out ->
        out.writeShort(ticks)
    }

    serverProt<CamRemoveroof>(opcode = 53, size = 4) { out ->
        out.writeIntInverseMiddle(packedCoord)
    }

    serverProt<LastLoginInfo>(opcode = 0, size = 4) { out ->
        out.writeInt(value)
    }
}
