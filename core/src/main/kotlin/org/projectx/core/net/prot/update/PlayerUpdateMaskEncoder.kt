package org.projectx.core.net.prot.update

import world.gregs.voidps.buffer.write.BufferWriter

object PlayerUpdateMaskEncoder {
    private val encoders = mutableMapOf<PlayerUpdateMaskKey, BufferWriter.(UpdateMask) -> Unit>()

    fun register(key: PlayerUpdateMaskKey, encoder: BufferWriter.(UpdateMask) -> Unit) {
        encoders[key] = encoder
    }

    fun encode(out: BufferWriter, key: PlayerUpdateMaskKey, mask: UpdateMask) {
        val encoder = encoders[key] ?: return
        encoder(out, mask)
    }

    fun hasEncoder(key: PlayerUpdateMaskKey): Boolean = encoders.containsKey(key)
}
