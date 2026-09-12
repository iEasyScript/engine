package org.projectx.core.net.prot.update

import world.gregs.voidps.buffer.write.BufferWriter

object NpcUpdateMaskEncoder {
    private val encoders = mutableMapOf<NpcUpdateMaskKey, BufferWriter.(UpdateMask) -> Unit>()

    fun register(key: NpcUpdateMaskKey, encoder: BufferWriter.(UpdateMask) -> Unit) {
        encoders[key] = encoder
    }

    fun encode(out: BufferWriter, key: NpcUpdateMaskKey, mask: UpdateMask) {
        val encoder = encoders[key] ?: return
        encoder(out, mask)
    }

    fun hasEncoder(key: NpcUpdateMaskKey): Boolean = encoders.containsKey(key)
}
