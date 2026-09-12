package org.projectx.core.net.prot.revision.rev950

import io.ktor.utils.io.*
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.*
import world.gregs.voidps.cache.Cache

/** The highest version the body decoder accepts; only this one carries the clan var block. */
private const val CLAN_SETTINGS_VERSION = 6

/** The decoder abandons the body unless the header flags read exactly this. */
private const val CLAN_SETTINGS_FLAGS = 2

/** A var's value encoding is selected by the top two bits of its key. */
private const val CLAN_VAR_INT = 0
private const val CLAN_VAR_LONG = 1
private const val CLAN_VAR_STRING = 2

internal fun Codec.registerRev950ServerCodecsSocial() {
    serverProt<GameMessage>(opcode = 33, size = ProtSize.VarByte) { out ->
        out.writeSmart(type.value)
        out.writeInt(effectFlags)
        val hasSender = targetDisplayName != null
        out.writeByte(if (hasSender) 1 else 0)
        if (hasSender) {
            out.writeRSString(targetDisplayName)
        }
        out.writeRSString(message)
    }

    serverProt<MessagePrivate>(opcode = 78, size = ProtSize.VarShort) { out ->
        out.writeName(displayName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessagePrivateEcho>(opcode = 15, size = ProtSize.VarShort) { out ->
        out.writeRSString(senderDisplayName)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessageFriendsChat>(opcode = 60, size = ProtSize.VarByte) { out ->
        out.writeName(displayName)
        out.writeRSString(chatName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<MessageClanChannel>(opcode = 22, size = ProtSize.VarByte) { out ->
        out.writeByte(if (guest) 1 else 0)
        out.writeRSString(displayName)
        out.writeHashedMessageTimestamp(message)
        out.writeByte(crown)
        out.writeFully(Cache.huffman.compress(message))
    }

    serverProt<FriendsChatChannel>(opcode = 29, size = ProtSize.VarShort) { out ->
        // Empty payload = leave/close variant (the client reads nothing).
        if (clear || ownerDisplayName == null || chatName == null || players == null) {
            return@serverProt
        }
        out.writeRSString(ownerDisplayName)
        out.writeByte(0)
        out.writeRSString(chatName)
        out.writeByte(minRankCanKick)
        out.writeSmart(players.size + 1)
        for (player in players) {
            out.writeRSString(player.displayName)
            out.writeByte(0)
            out.writeShort(player.worldId)
            out.writeByte(player.rank)
            out.writeRSString(player.worldName)
        }
    }

    serverProt<ClanChannelFull>(opcode = 64, size = ProtSize.VarShort) { out ->
        if (clanName == null || chatters == null) {
            out.writeByte(if (main) 0 else -1)
            return@serverProt
        }
        out.writeByte(if (main) 0 else -1)
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

    serverProt<ClanSettingsFull>(opcode = 66, size = ProtSize.VarShort) { out ->
        if (clanName == null || members == null) {
            out.writeByte(if (main) 0 else -1)
            return@serverProt
        }
        val banned = bannedUsers ?: emptyArray()
        val vars = settings ?: emptyArray()
        out.writeByte(if (main) 0 else -1)
        out.writeByte(CLAN_SETTINGS_VERSION)
        out.writeByte(CLAN_SETTINGS_FLAGS)
        out.writeInt(updateCount)
        out.writeInt(0)
        out.writeShort(members.size)
        out.writeByte(banned.size)
        out.writeRSString(clanName)
        out.writeInt(0)
        out.writeByte(if (allowGuests) 1 else 0)
        out.writeByte(talkRank)
        out.writeByte(kickRank)
        out.writeByte(0)
        out.writeByte(0)
        for (member in members) {
            out.writeRSString(member.displayName)
            out.writeByte(member.rank)
            out.writeInt(0)
            out.writeShort(0)
            out.writeByte(0)
        }
        for (name in banned) {
            out.writeRSString(name)
        }
        out.writeShort(vars.size)
        for (setting in vars) {
            when {
                setting.stringValue != null -> {
                    out.writeInt(setting.key or (CLAN_VAR_STRING shl 30))
                    out.writeRSString(setting.stringValue)
                }
                setting.longValue != null -> {
                    out.writeInt(setting.key or (CLAN_VAR_LONG shl 30))
                    out.writeLong(setting.longValue)
                }
                else -> {
                    out.writeInt(setting.key or (CLAN_VAR_INT shl 30))
                    out.writeInt(setting.intValue ?: 0)
                }
            }
        }
    }

    serverProt<ChatFilterSettingsPrivateChat>(opcode = 40, size = 1) { out ->
        out.writeByte(filter)
    }

    serverProt<UpdateIgnoreList>(opcode = 10, size = ProtSize.VarShort) { out ->
        for (entry in ignores) {
            out.writeByte(0)
            out.writeRSString(entry.displayName)
            out.writeRSString(entry.previousName)
            out.writeRSString("")
        }
    }

    serverProt<VarclanEnable>(opcode = 108, size = 0)
    serverProt<VarclanDisable>(opcode = 112, size = 0)

    serverProt<SetPlayerOp>(opcode = 99, size = ProtSize.VarByte) { out ->
        out.writeByte(if (priority) 0x80 else 0)
        out.writeRSString(text ?: "null")
        out.writeByteInverse(slot + 1)
        out.writeShortAdd(0xFFFF)
    }
}
