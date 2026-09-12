package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.ANIMATIONS
import world.gregs.voidps.cache.type.data.SeqType
import world.gregs.voidps.cache.type.encoder.SeqEncoder

class SeqDecoder : TypeDecoder<SeqType>(ANIMATIONS) {
    override fun create(size: Int) = Array(size) { SeqType(it) }

    private val encoder = SeqEncoder()

    override fun canonicalOpcodes(definition: SeqType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0x7f

    override fun getArchive(id: Int) = id ushr 7

    override fun size(cache: Cache): Int {
        return cache.lastArchiveId(index) * 128 + (cache.fileCount(index, cache.lastArchiveId(index)))
    }

    override fun SeqType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                val length = buffer.readUnsignedShort()
                durations = IntArray(length) { buffer.readUnsignedShort() }
                frames = IntArray(length) { buffer.readUnsignedShort() }
                for (count in 0 until length) {
                    frames!![count] = (buffer.readUnsignedShort() shl 16) + frames!![count]
                }
            }
            2 -> loopOffset = buffer.readUnsignedShort()
            3 -> keep(opcode, buffer.capture { repeat(readSmart()) { readSmart() } })
            5 -> priority = buffer.readUnsignedByte()
            6 -> leftHandItem = buffer.readUnsignedShort()
            7 -> rightHandItem = buffer.readUnsignedShort()
            8 -> maxLoops = buffer.readUnsignedByte()
            9 -> animatingPrecedence = buffer.readUnsignedByte()
            10 -> walkingPrecedence = buffer.readUnsignedByte()
            11 -> replayMode = buffer.readUnsignedByte()
            12 -> keep(opcode, buffer.capture { skip(readUnsignedByte() * 4) })
            13 -> keep(opcode, buffer.capture {
                repeat(readUnsignedShort()) {
                    val size = readUnsignedByte()
                    if (size > 0) {
                        skip(3 + (size - 1) * 2)
                    }
                }
            })
            14, 15, 16, 18 -> keep(opcode, buffer.capture { })
            19 -> {
                if (volumes == null) {
                    volumes = IntArray(sounds!!.size)
                    for (index in sounds!!.indices) {
                        volumes!![index] = 255
                    }
                }
                volumes!![buffer.readUnsignedByte()] = buffer.readUnsignedByte()
            }
            20 -> {
                if (primarySpeeds == null || secondarySpeeds == null) {
                    primarySpeeds = IntArray(sounds!!.size)
                    secondarySpeeds = IntArray(sounds!!.size)
                    for (index in sounds!!.indices) {
                        primarySpeeds!![index] = 256
                        secondarySpeeds!![index] = 256
                    }
                }
                val length = buffer.readUnsignedByte()
                primarySpeeds!![length] = buffer.readShort()
                secondarySpeeds!![length] = buffer.readShort()
            }
            22 -> keep(opcode, buffer.capture { skip(1) })
            23 -> keep(opcode, buffer.capture { skip(2) })
            24 -> keep(opcode, buffer.capture { skip(2) })
            25 -> keep(opcode, buffer.capture { skip(2) })
            26 -> keep(opcode, buffer.capture { skip(4) })
            27 -> keep(opcode, buffer.capture { skip(1) })
            112 -> keep(opcode, buffer.capture { skip(readUnsignedShort() * 4) })
            119 -> keep(opcode, buffer.capture { skip(3) })
            120 -> keep(opcode, buffer.capture { skip(6) })
            249 -> readParameters(buffer)
            else -> unknown(opcode, buffer)
        }
    }

    override fun changeValues(definitions: Array<SeqType>, definition: SeqType) {
        if (definition.walkingPrecedence == -1) {
            definition.walkingPrecedence = if (definition.interleaveOrder == null) 0 else 2
        }
        if (definition.animatingPrecedence == -1) {
            definition.animatingPrecedence = if (definition.interleaveOrder == null) 0 else 2
        }
    }
}