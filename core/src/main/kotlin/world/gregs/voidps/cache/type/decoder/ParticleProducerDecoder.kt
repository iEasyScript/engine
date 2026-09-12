package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.PARTICLES
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.ParticleProducerType
import world.gregs.voidps.cache.type.encoder.ParticleProducerEncoder

class ParticleProducerDecoder : TypeDecoder<ParticleProducerType>(PARTICLES) {

    private val encoder = ParticleProducerEncoder()

    override fun create(size: Int) = Array(size) { ParticleProducerType(it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(index, ARCHIVE)

    override fun canonicalOpcodes(definition: ParticleProducerType): IntArray = encoder.opcodes(definition)

    override fun ParticleProducerType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                unknown1a = buffer.readUnsignedShort()
                unknown1b = buffer.readUnsignedShort()
                unknown1c = buffer.readUnsignedShort()
                unknown1d = buffer.readUnsignedShort()
            }
            3 -> {
                unknown3a = buffer.readInt()
                unknown3b = buffer.readInt()
            }
            4 -> {
                unknown4a = buffer.readUnsignedByte()
                unknown4b = buffer.readUnsignedByte()
            }
            5 -> unknown5 = buffer.readUnsignedShort()
            6 -> {
                unknown6a = buffer.readInt()
                unknown6b = buffer.readInt()
            }
            7 -> {
                unknown7a = buffer.readUnsignedShort()
                unknown7b = buffer.readUnsignedShort()
            }
            8 -> {
                unknown8a = buffer.readUnsignedShort()
                unknown8b = buffer.readUnsignedShort()
            }
            9 -> unknown9 = buffer.readShortList()
            10 -> unknown10 = buffer.readShortList()
            12 -> unknown12 = buffer.readUnsignedByte()
            13 -> unknown13 = buffer.readUnsignedByte()
            14 -> unknown14 = buffer.readUnsignedShort()
            15 -> unknown15 = buffer.readUnsignedShort()
            16 -> {
                unknown16a = buffer.readUnsignedByte()
                unknown16b = buffer.readUnsignedShort()
                unknown16c = buffer.readUnsignedShort()
                unknown16d = buffer.readUnsignedByte()
            }
            18 -> unknown18 = buffer.readInt()
            19 -> unknown19 = buffer.readUnsignedByte()
            20 -> unknown20 = buffer.readUnsignedByte()
            21 -> unknown21 = buffer.readUnsignedByte()
            22 -> unknown22 = buffer.readInt()
            23 -> unknown23 = buffer.readUnsignedByte()
            24 -> unknown24 = buffer.readUnsignedByte()
            25 -> unknown25 = buffer.readShortList()
            26 -> unknown26 = true
            27 -> unknown27 = buffer.readUnsignedShort()
            28 -> unknown28 = buffer.readUnsignedByte()
            29 -> unknown29 = buffer.capture { skip(readUnsignedByte() * 2 + 2) }
            30 -> unknown30 = true
            31 -> {
                unknown31a = buffer.readUnsignedShort()
                unknown31b = buffer.readUnsignedShort()
            }
            32 -> unknown32 = true
            33 -> unknown33 = true
            34 -> unknown34 = true
            35 -> unknown35 = buffer.capture { skip(readUnsignedByte() * 3 + 2) }
            36 -> unknown36 = true
            else -> unknown(opcode, buffer)
        }
    }

    private fun Reader.readShortList() = IntArray(readUnsignedByte()) { readUnsignedShort() }

    private companion object {
        const val ARCHIVE = 0
    }
}
