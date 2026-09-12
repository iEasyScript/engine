package org.projectx.core.net.prot

import kotlinx.io.Buffer
import world.gregs.voidps.buffer.readRSString
import world.gregs.voidps.cache.secure.Xtea

/**
 * Decrypts a C2S MESSAGE_PRIVATE (op82) body with the session XTEA/tinyKey and parses the plaintext
 * the client wrote before encrypting: recipient name, a CS2 context string, two bytes, then the
 * message. Returns null if the key is missing or the block is malformed. Mirrors
 * `jag::ClientProt::SendMessagePrivate` (Packet::tinyKeyEncrypt over the whole body).
 */
fun MessagePrivateEncrypted.decodePrivateMessage(xteaKey: IntArray): MessagePrivateSend? {
    if (xteaKey.size != 4 || encrypted.size < 8) return null
    val buffer = encrypted.copyOf()
    Xtea.decipher(buffer, xteaKey)
    val source = Buffer().apply { write(buffer) }
    return try {
        val recipient = source.readRSString()
        source.readRSString()
        source.readByte()
        source.readByte()
        val message = source.readRSString()
        if (recipient.isEmpty()) return null
        MessagePrivateSend(toDisplayName = recipient, message = message.toByteArray(Charsets.ISO_8859_1))
    } catch (e: Exception) {
        null
    }
}
