package org.projectx.core.net.prot.revision.rev949

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache

internal fun Codec.registerRev949ServerCodecsSocial() {
    serverProt<GameMessage>(opcode = 21, size = ProtSize.VarByte) { out ->
        out.writeSmart(type.value)
        out.writeInt(effectFlags)
        val hasSender = targetDisplayName != null
        out.writeByte(if (hasSender) 1 else 0)
        if (hasSender) {
            out.writeRSString(targetDisplayName)
        }
        out.writeRSString(message)
    }

    serverProt<MessagePrivate>(opcode = 75, size = ProtSize.VarShort) { out ->
        out.writeName(displayName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessagePrivateEcho>(opcode = 49, size = ProtSize.VarShort) { out ->
        out.writeRSString(senderDisplayName)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessageFriendsChat>(opcode = 26, size = ProtSize.VarByte) { out ->
        out.writeName(displayName)
        out.writeRSString(chatName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessageClanChannel>(opcode = 84, size = ProtSize.VarByte) { out ->
        out.writeByte(if (guest) 1 else 0)
        out.writeRSString(displayName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<FriendsChatChannel>(opcode = 63, size = ProtSize.VarShort) { out ->
        // Empty payload = leave/close variant (client reads nothing).
        if (clear || ownerDisplayName == null || chatName == null || players == null) {
            return@serverProt
        }
        out.writeRSString(ownerDisplayName)
        out.writeByte(0)
        out.writeRSString(chatName)
        out.writeByte(minRankCanKick)
        out.writeByte(players.size + 1)
        for (player in players) {
            out.writeRSString(player.displayName)
            out.writeByte(0)
            out.writeShort(player.worldId)
            out.writeByte(player.rank)
            out.writeRSString(player.worldName)
        }
    }

    serverProt<ClanChannelFull>(opcode = 85, size = ProtSize.VarShort) { out ->
        // Negative slot byte = clear (guest slot); the client reads nothing further.
        if (clanName == null || chatters == null) {
            out.writeByte(0xFF)
            return@serverProt
        }
        out.writeByte(if (main) 0 else 1)
        out.writeByte(2)
        out.writeLong(updateNum)
        out.writeLong(clanHash)
        out.writeRSString(clanName)
        out.writeByte(1)
        out.writeByte(talkRank)
        out.writeByte(kickRank)
        out.writeShort(chatters.size)
        for (chatter in chatters) {
            out.writeRSString(chatter.displayName)
            out.writeByte(chatter.rank)
            out.writeShort(chatter.worldId)
        }
    }

    serverProt<ClanSettingsFull>(opcode = 97, size = ProtSize.VarShort) { out ->
        if (clanName == null || members == null) {
            out.writeByte(0xFF)
            return@serverProt
        }
        out.writeByte(if (main) 0 else 1)
        out.writeByte(3)
        out.writeInt(updateCount)
        out.writeRSString(clanName)
        out.writeByte(if (allowGuests) 1 else 0)
        out.writeByte(talkRank)
        out.writeByte(kickRank)
        out.writeShort(members.size)
        for (member in members) {
            out.writeRSString(member.displayName)
            out.writeByte(member.rank)
        }
        val banned = bannedUsers ?: emptyArray()
        out.writeShort(banned.size)
        for (name in banned) {
            out.writeRSString(name)
        }
        val settingsList = settings ?: emptyArray()
        out.writeShort(settingsList.size)
        for (setting in settingsList) {
            out.writeInt(setting.key)
            when {
                setting.stringValue != null -> { out.writeByte(2); out.writeRSString(setting.stringValue) }
                setting.longValue != null -> { out.writeByte(1); out.writeLong(setting.longValue) }
                else -> { out.writeByte(0); out.writeInt(setting.intValue ?: 0) }
            }
        }
    }

    serverProt<ChatFilterSettingsPrivateChat>(opcode = 74, size = 1) { out ->
        out.writeByte(filter)
    }

    serverProt<UpdateIgnoreList>(opcode = 66, size = ProtSize.VarShort) { out ->
        for (entry in ignores) {
            out.writeByte(0)
            out.writeRSString(entry.displayName)
            out.writeRSString(entry.previousName)
            out.writeRSString("")
        }
    }

    serverProt<VarclanEnable>(opcode = 116, size = 0)
    serverProt<VarclanDisable>(opcode = 120, size = 0)

    serverProt<SetPlayerOp>(opcode = 61, size = ProtSize.VarByte) { out ->
        out.writeByteAdd(slot + 1)
        out.writeByte(if (priority) 0x80 else 0)
        out.writeRSString(text ?: "null")
        out.writeShortAddLittle(0xFFFF)
    }
}
