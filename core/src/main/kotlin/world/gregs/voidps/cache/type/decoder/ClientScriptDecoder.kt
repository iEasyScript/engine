package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.ClientScriptCodec
import world.gregs.voidps.cache.type.data.ClientScriptType

class ClientScriptDecoder : TypeDecoder<ClientScriptType>(Index.CLIENT_SCRIPTS) {

    override fun size(cache: Cache): Int = cache.lastArchiveId(index)

    override fun create(size: Int) = Array(size) { ClientScriptType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: ClientScriptType, buffer: Reader) {
        recordDecode(definition.id, buffer) { ClientScriptCodec.decode(buffer, definition) }
    }

    override fun ClientScriptType.read(opcode: Int, buffer: Reader) = Unit
}
