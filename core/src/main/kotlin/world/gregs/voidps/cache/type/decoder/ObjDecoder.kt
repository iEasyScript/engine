package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.ITEMS
import world.gregs.voidps.cache.type.data.ObjType
import world.gregs.voidps.cache.type.encoder.ObjEncoder

class ObjDecoder : TypeDecoder<ObjType>(ITEMS) {
    override fun create(size: Int) = Array(size) { ObjType(it) }

    private val encoder = ObjEncoder()

    override fun canonicalOpcodes(definition: ObjType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun ObjType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> modelId = buffer.readBigSmart()
            2 -> name = buffer.readString()
            3 -> examine = buffer.readString()
            4 -> zoom2d = buffer.readUnsignedShort()
            5 -> xan2d = buffer.readUnsignedShort()
            6 -> yan2d = buffer.readUnsignedShort()
            7 -> {
                xof2d = buffer.readUnsignedShort()
                if (xof2d > 32767) {
                    xof2d -= 65536
                }
            }
            8 -> {
                yof2d = buffer.readUnsignedShort()
                if (yof2d > 32767) {
                    yof2d -= 65536
                }
            }
            9 -> {
                val length = buffer.readUnsignedByte()
                modelIds = IntArray(length) { buffer.readBigSmart() }
            }
            10 -> keep(opcode, buffer.capture { skip(2) })
            11 -> stackable = 1
            12 -> cost = buffer.readInt().toLong()
            13 -> wearPos = buffer.readUnsignedByte()
            14 -> wearPos2 = buffer.readUnsignedByte()
            15 -> keep(opcode, buffer.capture { })
            16 -> members = true
            18 -> multiStackSize = buffer.readUnsignedShort()
            23 -> primaryMaleModel = buffer.readBigSmart()
            24 -> secondaryMaleModel = buffer.readBigSmart()
            25 -> primaryFemaleModel = buffer.readBigSmart()
            26 -> secondaryFemaleModel = buffer.readBigSmart()
            27 -> wearPos3 = buffer.readUnsignedByte()
            in 30..34 -> floorOptions[opcode - 30] = buffer.readString()
            in 35..39 -> options[opcode - 35] = buffer.readString()
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            42 -> readColourPalette(buffer)
            43 -> notedId = buffer.readInt()
            44, 45 -> keep(opcode, buffer.capture { skip(2) })
            in 46..56 -> {
                val models = modelIds?.takeIf { it.size >= 12 }
                    ?: IntArray(12) { i -> modelIds?.getOrElse(i) { -1 } ?: -1 }.also { modelIds = it }
                models[opcode - 45] = buffer.readBigSmart()
            }
            65 -> exchangeable = true
            69 -> geBuyLimit = buffer.readInt()
            78 -> tertiaryMaleModel = buffer.readBigSmart()
            79 -> tertiaryFemaleModel = buffer.readBigSmart()
            90 -> primaryMaleDialogueHead = buffer.readBigSmart()
            91 -> primaryFemaleDialogueHead = buffer.readBigSmart()
            92 -> secondaryMaleDialogueHead = buffer.readBigSmart()
            93 -> secondaryFemaleDialogueHead = buffer.readBigSmart()
            94 -> category = buffer.readUnsignedShort()
            95 -> zan2d = buffer.readUnsignedShort()
            96 -> dummyItem = buffer.readUnsignedByte()
            97 -> noteId = buffer.readUnsignedShort()
            98 -> notedTemplateId = buffer.readUnsignedShort()
            in 100..109 -> {
                if (stackIds == null) {
                    stackAmounts = IntArray(10)
                    stackIds = IntArray(10)
                }
                stackIds!![opcode - 100] = buffer.readUnsignedShort()
                stackAmounts!![opcode - 100] = buffer.readUnsignedShort()
            }
            110 -> floorScaleX = buffer.readUnsignedShort()
            111 -> floorScaleZ = buffer.readUnsignedShort()
            112 -> floorScaleY = buffer.readUnsignedShort()
            113 -> ambience = buffer.readByte()
            114 -> diffusion = buffer.readByte() * 5
            115 -> team = buffer.readUnsignedByte()
            121 -> lendId = buffer.readUnsignedShort()
            122 -> lendTemplateId = buffer.readUnsignedShort()
            125 -> {
                maleWieldX = buffer.readByte() shl 2
                maleWieldZ = buffer.readByte() shl 2
                maleWieldY = buffer.readByte() shl 2
            }
            126 -> {
                femaleWieldX = buffer.readByte() shl 2
                femaleWieldZ = buffer.readByte() shl 2
                femaleWieldY = buffer.readByte() shl 2
            }
            in 127..130 -> keep(opcode, buffer.capture { skip(2) })
            132 -> {
                val length = buffer.readUnsignedByte()
                campaigns = IntArray(length) { buffer.readUnsignedShort() }
            }
            134 -> pickSizeShift = buffer.readUnsignedByte()
            139 -> bindId = buffer.readUnsignedShort()
            140 -> boundTemplateId = buffer.readUnsignedShort()
            in 142..146 -> {
                if (headModels == null) {
                    headModels = IntArray(6) { -1 }
                }
                headModels!![opcode - 142] = buffer.readUnsignedShort()
            }
            in 150..154 -> {
                if (groundCursors == null) {
                    groundCursors = IntArray(5) { -1 }
                }
                groundCursors!![opcode - 150] = buffer.readUnsignedShort()
            }
            156 -> tradeable = true
            157 -> searchable = true
            161 -> shardItemId = buffer.readUnsignedShort()
            162 -> shardTemplateId = buffer.readUnsignedShort()
            163 -> shardCombineAmount = buffer.readUnsignedShort()
            164 -> shardName = buffer.readString()
            165 -> stackable = 2
            167, 168 -> keep(opcode, buffer.capture { })
            178 -> {
                stackable = 0
                keep(opcode, buffer.capture { })
            }
            181 -> cost = buffer.readLong()
            182 -> keep(opcode, buffer.capture { readUnsignedMedium() })
            in 190..199 -> {
                if (stackIds == null) {
                    stackAmounts = IntArray(10)
                    stackIds = IntArray(10)
                }
                stackIds!![opcode - 190] = buffer.readUnsignedMedium()
                stackAmounts!![opcode - 190] = buffer.readUnsignedShort()
            }
            201 -> noteId = buffer.readUnsignedMedium()
            202 -> notedTemplateId = buffer.readUnsignedMedium()
            203 -> lendId = buffer.readUnsignedMedium()
            204 -> lendTemplateId = buffer.readUnsignedMedium()
            205 -> bindId = buffer.readUnsignedMedium()
            206 -> boundTemplateId = buffer.readUnsignedMedium()
            207 -> shardItemId = buffer.readUnsignedMedium()
            208 -> shardTemplateId = buffer.readUnsignedMedium()
            in 242..248 -> keep(opcode, buffer.capture { })
            249 -> readParameters(buffer)
            else -> unknown(opcode, buffer)
        }
    }

    override fun changeValues(definitions: Array<ObjType>, definition: ObjType) {
        if (definition.notedTemplateId != -1) {
            definition.toNote(definitions.getOrNull(definition.notedTemplateId), definitions.getOrNull(definition.noteId))
        }
        if (definition.lendTemplateId != -1) {
            definition.toLend(definitions.getOrNull(definition.lendId), definitions.getOrNull(definition.lendTemplateId))
        }
        if (definition.boundTemplateId != -1) {
            definition.toSingleNote(definitions.getOrNull(definition.boundTemplateId), definitions.getOrNull(definition.bindId))
        }
        definition.loadEquippedOps()
    }
}