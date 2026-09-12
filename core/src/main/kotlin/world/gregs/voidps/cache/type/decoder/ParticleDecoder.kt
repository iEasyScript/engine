package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.PARTICLES
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.ParticleType
import world.gregs.voidps.cache.type.data.ParticleVector
import world.gregs.voidps.cache.type.encoder.ParticleEncoder

class ParticleDecoder : TypeDecoder<ParticleType>(PARTICLES) {

    private val encoder = ParticleEncoder()

    override fun create(size: Int) = Array(size) { ParticleType(it) }

    override fun getArchive(id: Int) = ARCHIVE

    override fun getFile(id: Int) = id

    override fun size(cache: Cache): Int = cache.lastFileId(index, ARCHIVE)

    override fun canonicalOpcodes(definition: ParticleType): IntArray = encoder.opcodes(definition)

    override fun ParticleType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> unknown1 = buffer.readUnsignedShort()
            2 -> unknown2 = true
            3 -> vector = ParticleVector(buffer.readInt(), buffer.readInt(), buffer.readInt())
            4 -> {
                unknown4Type = buffer.readUnsignedByte()
                unknown4Value = buffer.readInt()
            }
            6 -> unknown6 = true
            8 -> unknown8 = true
            9 -> unknown9 = true
            10 -> unknown10 = true
            else -> unknown(opcode, buffer)
        }
    }

    private companion object {
        const val ARCHIVE = 1
    }
}
