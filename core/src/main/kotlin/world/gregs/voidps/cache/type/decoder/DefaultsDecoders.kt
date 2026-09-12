package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.DefaultsCurve
import world.gregs.voidps.cache.type.data.DefaultsGroup10Type
import world.gregs.voidps.cache.type.data.DefaultsGroup12Type
import world.gregs.voidps.cache.type.data.DefaultsGroup1Type
import world.gregs.voidps.cache.type.data.DefaultsGroup2Type
import world.gregs.voidps.cache.type.data.DefaultsGroup3Type
import world.gregs.voidps.cache.type.data.DefaultsGroup4Type
import world.gregs.voidps.cache.type.data.DefaultsGroup5Type
import world.gregs.voidps.cache.type.data.DefaultsGroup6Type
import world.gregs.voidps.cache.type.data.DefaultsGroup7Type
import world.gregs.voidps.cache.type.data.DefaultsGroup8Type
import world.gregs.voidps.cache.type.data.DefaultsGroup9Type
import world.gregs.voidps.cache.type.data.DefaultsLight
import world.gregs.voidps.cache.type.data.DefaultsPair
import world.gregs.voidps.cache.type.data.DefaultsRamp
import world.gregs.voidps.cache.type.data.DefaultsSkill
import world.gregs.voidps.cache.type.encoder.DefaultsGroup10Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup12Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup1Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup2Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup3Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup4Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup5Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup6Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup7Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup8Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup9Encoder

/** Every group of the defaults index is one file of one archive, addressed by the archive id. */
abstract class DefaultsDecoder<T : CacheType>(private val group: Int) : TypeDecoder<T>(Index.DEFAULTS) {
    override fun getArchive(id: Int) = group
    override fun getFile(id: Int) = 0
}

private fun Reader.readCountedBytes() = IntArray(readUnsignedByte()) { readUnsignedByte() }

class DefaultsGroup1Decoder : DefaultsDecoder<DefaultsGroup1Type>(1) {
    private val encoder = DefaultsGroup1Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup1Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup1Type) = encoder.opcodes(definition)
    override fun DefaultsGroup1Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> unknown1 = buffer.readUnsignedShort()
            10 -> unknown10 = buffer.readUnsignedShort()
            else -> unknown(opcode, buffer)
        }
    }
}

class DefaultsGroup2Decoder : DefaultsDecoder<DefaultsGroup2Type>(2) {
    private val encoder = DefaultsGroup2Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup2Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup2Type) = encoder.opcodes(definition)
    override fun DefaultsGroup2Type.read(opcode: Int, buffer: Reader) = unknown(opcode, buffer)
}

class DefaultsGroup3Decoder : DefaultsDecoder<DefaultsGroup3Type>(3) {
    private val encoder = DefaultsGroup3Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup3Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup3Type) = encoder.opcodes(definition)

    private fun readRamps(buffer: Reader) = Array(ROWS * COLUMNS) {
        val value = buffer.readUnsignedShort()
        DefaultsRamp(value, IntArray(buffer.readUnsignedShort()) { buffer.readUnsignedShort() })
    }

    override fun DefaultsGroup3Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                val count = if (pairCount == -1) DEFAULT_PAIRS else pairCount
                pairs = Array(count) { DefaultsPair(buffer.readShort(), buffer.readShort()) }
            }
            2 -> unknown2 = buffer.readBigSmart()
            3 -> pairCount = buffer.readUnsignedByte()
            4 -> unknown4 = true
            5 -> unknown5 = buffer.readUnsignedMedium()
            6 -> unknown6 = buffer.readUnsignedMedium()
            7 -> ramps7 = readRamps(buffer)
            8 -> unknown8 = true
            9 -> unknown9 = buffer.readUnsignedByte()
            10 -> unknown10 = true
            11 -> unknown11 = buffer.readUnsignedByte()
            12 -> {
                unknown12First = buffer.readUnsignedShort()
                unknown12Second = buffer.readUnsignedShort()
            }
            13 -> unknown13 = buffer.readUnsignedByte()
            14 -> unknown14 = buffer.readUnsignedByte()
            15 -> unknown15 = buffer.readUnsignedByte()
            16 -> unknown16 = true
            17 -> unknown17 = buffer.readInt()
            18 -> unknown18 = buffer.readInt()
            19 -> unknown19 = buffer.readInt()
            20 -> {
                unknown20First = buffer.readUnsignedShort()
                unknown20Second = buffer.readUnsignedByte()
            }
            21 -> unknown21 = buffer.readUnsignedByte()
            22 -> {
                unknown22Head = IntArray(6) { buffer.readBigSmart() }
                unknown22FirstOffset = buffer.readByte()
                unknown22SecondOffset = buffer.readByte()
                unknown22Tail = IntArray(7) { buffer.readBigSmart() }
            }
            23 -> ramps23 = readRamps(buffer)
            24 -> unknown24 = buffer.readInt()
            25 -> unknown25 = IntArray(6) { buffer.readBigSmart() }
            26 -> unknown26 = buffer.readInt()
            27 -> unknown27 = buffer.readInt()
            28 -> unknown28 = buffer.readInt()
            29 -> {
                unknown29First = buffer.readInt()
                unknown29Second = buffer.readInt()
            }
            else -> unknown(opcode, buffer)
        }
    }

    companion object {
        const val ROWS = 10
        const val COLUMNS = 4
        const val DEFAULT_PAIRS = 4
    }
}

class DefaultsGroup4Decoder : DefaultsDecoder<DefaultsGroup4Type>(4) {
    private val encoder = DefaultsGroup4Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup4Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup4Type) = encoder.opcodes(definition)
    override fun DefaultsGroup4Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> unknown1 = buffer.readInt()
            else -> unknown(opcode, buffer)
        }
    }
}

class DefaultsGroup5Decoder : DefaultsDecoder<DefaultsGroup5Type>(5) {
    private val encoder = DefaultsGroup5Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup5Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup5Type) = encoder.opcodes(definition)
    override fun DefaultsGroup5Type.read(opcode: Int, buffer: Reader) = unknown(opcode, buffer)
}

class DefaultsGroup6Decoder : DefaultsDecoder<DefaultsGroup6Type>(6) {
    private val encoder = DefaultsGroup6Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup6Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup6Type) = encoder.opcodes(definition)
    override fun DefaultsGroup6Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> slots = buffer.readCountedBytes()
            3 -> unknown3 = buffer.readUnsignedByte()
            4 -> unknown4 = buffer.readUnsignedByte()
            5 -> unknown5 = buffer.readCountedBytes()
            6 -> unknown6 = buffer.readCountedBytes()
            7 -> unknown7 = IntArray(slots?.size ?: 0) { buffer.readUnsignedByte() }
            else -> unknown(opcode, buffer)
        }
    }
}

class DefaultsGroup7Decoder : DefaultsDecoder<DefaultsGroup7Type>(7) {
    private val encoder = DefaultsGroup7Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup7Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup7Type) = encoder.opcodes(definition)

    private fun readLight(buffer: Reader): DefaultsLight = when (val kind = buffer.readUnsignedByte()) {
        0 -> DefaultsLight(kind, buffer.readByte(), buffer.readUnsignedByte(), buffer.readCountedBytes())
        1 -> DefaultsLight(kind, buffer.readUnsignedByte(), buffer.readUnsignedByte(), null)
        2 -> DefaultsLight(kind, 0, 0, buffer.readCountedBytes())
        else -> DefaultsLight(kind, 0, 0, null)
    }

    override fun DefaultsGroup7Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> light1 = readLight(buffer)
            2 -> light2 = readLight(buffer)
            3 -> light3 = readLight(buffer)
            4 -> light4 = readLight(buffer)
            5 -> ids5 = buffer.readCountedBytes()
            6 -> ids6 = buffer.readCountedBytes()
            7 -> ids7 = buffer.readCountedBytes()
            8 -> discarded8 = readLight(buffer)
            9 -> discarded9 = readLight(buffer)
            10 -> discarded10 = readLight(buffer)
            11 -> unknown11 = true
            12 -> unknown12 = buffer.readInt()
            13 -> unknown13 = buffer.readInt()
            else -> unknown(opcode, buffer)
        }
    }
}

class DefaultsGroup8Decoder : DefaultsDecoder<DefaultsGroup8Type>(8) {
    private val encoder = DefaultsGroup8Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup8Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup8Type) = encoder.opcodes(definition)
    override fun DefaultsGroup8Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> unknown1 = buffer.readUnsignedByte()
            13 -> unknown13 = buffer.readUnsignedByte()
            else -> unknown(opcode, buffer)
        }
    }
}

class DefaultsGroup9Decoder : DefaultsDecoder<DefaultsGroup9Type>(9) {
    private val encoder = DefaultsGroup9Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup9Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup9Type) = encoder.opcodes(definition)
    override fun DefaultsGroup9Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> skills = Array(buffer.readUnsignedByte()) {
                val skill = buffer.readUnsignedByte()
                val levelCap = buffer.readUnsignedShort()
                val flags = buffer.readUnsignedByte()
                DefaultsSkill(
                    skill,
                    levelCap,
                    flags,
                    if (flags and 2 != 0) buffer.readUnsignedByte() else -1,
                    if (flags and 4 != 0) buffer.readUnsignedByte() else -1,
                    if (flags and 8 != 0) buffer.readByte() else 1,
                    buffer.readUnsignedByte()
                )
            }
            2 -> {
                curveCount = buffer.readUnsignedByte()
                val curves = ArrayList<DefaultsCurve>()
                while (true) {
                    val index = buffer.readUnsignedByte()
                    if (index == CURVE_TERMINATOR) {
                        break
                    }
                    curves.add(DefaultsCurve(index, IntArray(buffer.readUnsignedShort()) { buffer.readInt() }))
                }
                this.curves = curves.toTypedArray()
            }
            else -> unknown(opcode, buffer)
        }
    }

    companion object {
        const val CURVE_TERMINATOR = 255
    }
}

class DefaultsGroup10Decoder : DefaultsDecoder<DefaultsGroup10Type>(10) {
    private val encoder = DefaultsGroup10Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup10Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup10Type) = encoder.opcodes(definition)
    override fun DefaultsGroup10Type.read(opcode: Int, buffer: Reader) {
        when {
            opcode == 1 -> unknown1 = buffer.readInt()
            opcode == 2 -> colour2 = buffer.readInt()
            opcode == 3 -> colour3 = buffer.readInt()
            opcode == 4 -> unknown4 = buffer.readUnsignedByte()
            opcode == 5 -> unknown5 = buffer.readUnsignedByte()
            opcode == 6 -> unknown6 = buffer.readInt()
            opcode == 7 -> colour7 = buffer.readInt()
            opcode >= GRID_BASE -> {
                val cells = grid ?: IntArray(GRID_ROWS * GRID_COLUMNS) { -1 }.also { grid = it }
                val offset = opcode - GRID_BASE
                cells[(offset shr 3) + (offset and 7) * GRID_ROWS] = buffer.readUnsignedShort()
            }
            else -> unknown(opcode, buffer)
        }
    }

    companion object {
        const val GRID_BASE = 100
        const val GRID_ROWS = 5
        const val GRID_COLUMNS = 3
    }
}

class DefaultsGroup12Decoder : DefaultsDecoder<DefaultsGroup12Type>(12) {
    private val encoder = DefaultsGroup12Encoder()
    override fun create(size: Int) = Array(size) { DefaultsGroup12Type(it) }
    override fun canonicalOpcodes(definition: DefaultsGroup12Type) = encoder.opcodes(definition)
    override fun DefaultsGroup12Type.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                unknown1First = buffer.readBigSmart()
                unknown1Second = buffer.readBigSmart()
            }
            2 -> unknown2 = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedShort() }
            else -> unknown(opcode, buffer)
        }
    }
}
