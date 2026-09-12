package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.INTERFACES
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.ComponentType
import world.gregs.voidps.cache.type.data.InterfaceType

class InterfaceDecoder(private val components: ComponentDecoder = ComponentDecoder()) : TypeDecoder<InterfaceType>(INTERFACES) {

    override fun create(size: Int) = Array(size) { InterfaceType(it) }

    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun load(definitions: Array<InterfaceType>, reader: Reader) {
        components.report = report
        val packed = readId(reader)
        val id = InterfaceType.id(packed)
        val component = InterfaceType.componentId(packed)
        val definition = definitions[id]
        val existing = definition.components
        val slots = if (existing != null && existing.size > component) existing else slots(id, component + 1)
        definition.components = slots
        components.readLoop(slots[component], reader)
    }

    override fun load(definitions: Array<InterfaceType>, cache: Cache, id: Int) {
        components.report = report
        val last = cache.lastFileId(index, id)
        if (last == -1) {
            return
        }
        val slots = slots(id, last + 1)
        for (file in cache.files(index, id)) {
            val data = cache.data(index, id, file) ?: continue
            components.readLoop(slots[file], BufferReader(data))
        }
        definitions[id].components = slots
    }

    override fun InterfaceType.read(opcode: Int, buffer: Reader) = throw UnsupportedOperationException("Interfaces are not opcode encoded.")

    private fun slots(id: Int, size: Int) = Array(size) { ComponentType(id = InterfaceType.pack(id, it)) }
}
