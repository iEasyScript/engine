package org.projectx.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.projectx.core.net.prot.*
import org.projectx.core.net.prot.revision.rev950.register950
import world.gregs.voidps.buffer.writeRSString
import world.gregs.voidps.cache.secure.Xtea
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-diffs the rev950 social encoders against the live-Jagex members capture
 * `re-resources/packet-dumps/949/lobby-friends-chat-clan-chat.log`. Display/channel names encode
 * their spaces as the non-breaking space (0xA0); world names keep the regular space (0x20).
 */
class Rev950SocialEncoderTest {
    private val codec = register950()

    private fun encode(prot: ServerProt): String = runBlocking {
        val entry = codec.serverProts[prot::class] ?: error("no encoder for ${prot::class.simpleName}")
        val encoder = entry.encoder ?: error("encoder null for ${prot::class.simpleName}")
        val buffer = Buffer()
        val channel = buffer.asByteWriteChannel()
        encoder.invoke(prot, channel)
        channel.flush()
        buffer.readByteArray().joinToString(" ") { "%02x".format(it) }
    }

    @Test fun friendsChatChannelFull() {
        val packet = FriendsChatChannel(
            ownerDisplayName = "Yehp",
            chatName = "Ironman\u00A0Btw",
            minRankCanKick = 7,
            players = arrayOf(
                FriendsChatChannel.FriendsChatPlayer(displayName = "Yehp", worldId = 1104, rank = 7, worldName = "Lobby 5"),
            ),
        )
        assertEquals(
            "59 65 68 70 00 00 49 72 6f 6e 6d 61 6e a0 42 74 77 00 07 02 59 65 68 70 00 00 04 50 07 4c 6f 62 62 79 20 35 00",
            encode(packet),
        )
    }

    @Test fun clanChannelFull() {
        val packet = ClanChannelFull(
            main = true,
            clanName = "Project X",
            clanHash = 0x0000019f485adabaL,
            updateNum = 0x000000000006060aL,
            kickRank = 0,
            talkRank = 4,
            chatters = arrayOf(ClanChannelFull.ClanChannelChatter(displayName = "Yehp", rank = 126, worldId = 1104)),
        )
        assertEquals(
            "00 02 00 00 00 00 00 06 06 0a 00 00 01 9f 48 5a da ba 44 61 72 6b 61 6e 00 01 04 00 00 01 59 65 68 70 00 7e 04 50",
            encode(packet),
        )
    }

    /** A lone slot byte clears: the main clan is slot 0, a guest channel is the negative slot. */
    @Test fun clanChannelFullClear() {
        assertEquals("00", encode(ClanChannelFull(main = true)))
        assertEquals("ff", encode(ClanChannelFull(main = false)))
    }

    @Test fun friendsChatChannelClear() {
        assertEquals("", encode(FriendsChatChannel(clear = true)))
    }

    /** Mirrors the client's op82 encode (plain strings, then XTEA over the whole body with +7 padding). */
    @Test fun privateMessageSendDecryptRoundTrip() {
        val key = intArrayOf(0x11111111, 0x22222222, 0x33333333, 0x44444444)
        val body = Buffer().apply {
            writeRSString("Zezima")
            writeRSString("")
            writeByte(0)
            writeByte(1)
            writeRSString("hello there")
        }.readByteArray()
        val padded = body + ByteArray(7)
        Xtea.encipher(padded, 0, padded.size, key)

        val decoded = MessagePrivateEncrypted(padded).decodePrivateMessage(key)
        assertEquals("Zezima", decoded?.toDisplayName)
        assertEquals("hello there", decoded?.message?.toString(Charsets.ISO_8859_1))
    }
}
