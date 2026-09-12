package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.ObjType

/**
 * Writes an index 19 obj file.
 *
 * The ground options and the inventory options each keep one entry at a non null default - "Take",
 * "Examine" and "Drop" - so a record is written for an option only when it differs from that.
 */
class ObjEncoder : OpcodeEncoder<ObjType>() {

    override fun opcodes(definition: ObjType): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelId != 0) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.examine != null) {
            opcodes.add(3)
        }
        if (definition.zoom2d != 2000) {
            opcodes.add(4)
        }
        if (definition.xan2d != 0) {
            opcodes.add(5)
        }
        if (definition.yan2d != 0) {
            opcodes.add(6)
        }
        if (definition.xof2d != 0) {
            opcodes.add(7)
        }
        if (definition.yof2d != 0) {
            opcodes.add(8)
        }
        if (definition.modelIds != null) {
            opcodes.add(9)
        }
        if (definition.stackable == 1) {
            opcodes.add(11)
        }
        if (definition.wearPos != -1) {
            opcodes.add(13)
        }
        if (definition.wearPos2 != -1) {
            opcodes.add(14)
        }
        if (definition.members) {
            opcodes.add(16)
        }
        if (definition.multiStackSize != -1) {
            opcodes.add(18)
        }
        if (definition.primaryMaleModel != -1) {
            opcodes.add(23)
        }
        if (definition.secondaryMaleModel != -1) {
            opcodes.add(24)
        }
        if (definition.primaryFemaleModel != -1) {
            opcodes.add(25)
        }
        if (definition.secondaryFemaleModel != -1) {
            opcodes.add(26)
        }
        if (definition.wearPos3 != -1) {
            opcodes.add(27)
        }
        for (index in 0 until 5) {
            if (definition.floorOptions.getOrNull(index) != floorOption(index)) {
                opcodes.add(30 + index)
            }
        }
        for (index in 0 until 5) {
            if (definition.options.getOrNull(index) != option(index)) {
                opcodes.add(35 + index)
            }
        }
        if (definition.originalColours != null) {
            opcodes.add(40)
        }
        if (definition.originalTextureColours != null) {
            opcodes.add(41)
        }
        if (definition.recolourPalette != null) {
            opcodes.add(42)
        }
        if (definition.exchangeable) {
            opcodes.add(65)
        }
        if (definition.geBuyLimit != 0) {
            opcodes.add(69)
        }
        if (definition.tertiaryMaleModel != -1) {
            opcodes.add(78)
        }
        if (definition.tertiaryFemaleModel != -1) {
            opcodes.add(79)
        }
        if (definition.primaryMaleDialogueHead != -1) {
            opcodes.add(90)
        }
        if (definition.primaryFemaleDialogueHead != -1) {
            opcodes.add(91)
        }
        if (definition.secondaryMaleDialogueHead != -1) {
            opcodes.add(92)
        }
        if (definition.secondaryFemaleDialogueHead != -1) {
            opcodes.add(93)
        }
        if (definition.category != -1) {
            opcodes.add(94)
        }
        if (definition.zan2d != 0) {
            opcodes.add(95)
        }
        if (definition.dummyItem != 0) {
            opcodes.add(96)
        }
        if (definition.floorScaleX != 128) {
            opcodes.add(110)
        }
        if (definition.floorScaleZ != 128) {
            opcodes.add(111)
        }
        if (definition.floorScaleY != 128) {
            opcodes.add(112)
        }
        if (definition.ambience != 0) {
            opcodes.add(113)
        }
        if (definition.diffusion != 0) {
            opcodes.add(114)
        }
        if (definition.team != 0) {
            opcodes.add(115)
        }
        if (definition.maleWieldX != 0 || definition.maleWieldZ != 0 || definition.maleWieldY != 0) {
            opcodes.add(125)
        }
        if (definition.femaleWieldX != 0 || definition.femaleWieldZ != 0 || definition.femaleWieldY != 0) {
            opcodes.add(126)
        }
        if (definition.campaigns != null) {
            opcodes.add(132)
        }
        if (definition.pickSizeShift != 0) {
            opcodes.add(134)
        }
        val heads = definition.headModels
        if (heads != null) {
            for (index in 0 until 5) {
                if (heads[index] != -1) {
                    opcodes.add(142 + index)
                }
            }
        }
        val cursors = definition.groundCursors
        if (cursors != null) {
            for (index in cursors.indices) {
                if (cursors[index] != -1) {
                    opcodes.add(150 + index)
                }
            }
        }
        if (definition.tradeable) {
            opcodes.add(156)
        }
        if (definition.searchable) {
            opcodes.add(157)
        }
        if (definition.shardCombineAmount != 0) {
            opcodes.add(163)
        }
        if (definition.shardName != null) {
            opcodes.add(164)
        }
        if (definition.stackable == 2) {
            opcodes.add(165)
        }
        if (definition.cost != 1L) {
            opcodes.add(181)
        }
        val stackIds = definition.stackIds
        if (stackIds != null) {
            for (index in stackIds.indices) {
                if (stackIds[index] != 0) {
                    opcodes.add(190 + index)
                }
            }
        }
        if (definition.noteId != -1) {
            opcodes.add(201)
        }
        if (definition.notedTemplateId != -1) {
            opcodes.add(202)
        }
        if (definition.lendId != -1) {
            opcodes.add(203)
        }
        if (definition.lendTemplateId != -1) {
            opcodes.add(204)
        }
        if (definition.bindId != -1) {
            opcodes.add(205)
        }
        if (definition.boundTemplateId != -1) {
            opcodes.add(206)
        }
        if (definition.shardItemId != -1) {
            opcodes.add(207)
        }
        if (definition.shardTemplateId != -1) {
            opcodes.add(208)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: ObjType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.modelId)
            2 -> writeString(definition.name)
            3 -> writeString(definition.examine)
            4 -> writeShort(definition.zoom2d)
            5 -> writeShort(definition.xan2d)
            6 -> writeShort(definition.yan2d)
            7 -> writeShort(definition.xof2d and SHORT)
            8 -> writeShort(definition.yof2d and SHORT)
            9 -> {
                val models = definition.modelIds ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            11, 156, 157, 165 -> Unit
            12 -> writeInt(definition.cost.toInt())
            13 -> writeByte(definition.wearPos)
            14 -> writeByte(definition.wearPos2)
            16 -> Unit
            18 -> writeShort(definition.multiStackSize)
            23 -> writeBigSmart(definition.primaryMaleModel)
            24 -> writeBigSmart(definition.secondaryMaleModel)
            25 -> writeBigSmart(definition.primaryFemaleModel)
            26 -> writeBigSmart(definition.secondaryFemaleModel)
            27 -> writeByte(definition.wearPos3)
            in 30..34 -> writeString(definition.floorOptions[opcode - 30])
            in 35..39 -> writeString(definition.options[opcode - 35])
            40 -> writeColours(definition)
            41 -> writeTextures(definition)
            42 -> writePalette(definition.recolourPalette)
            in 46..56 -> writeBigSmart(definition.modelIds!![opcode - 45])
            65 -> Unit
            69 -> writeInt(definition.geBuyLimit)
            78 -> writeBigSmart(definition.tertiaryMaleModel)
            79 -> writeBigSmart(definition.tertiaryFemaleModel)
            90 -> writeBigSmart(definition.primaryMaleDialogueHead)
            91 -> writeBigSmart(definition.primaryFemaleDialogueHead)
            92 -> writeBigSmart(definition.secondaryMaleDialogueHead)
            93 -> writeBigSmart(definition.secondaryFemaleDialogueHead)
            94 -> writeShort(definition.category)
            95 -> writeShort(definition.zan2d)
            96 -> writeByte(definition.dummyItem)
            110 -> writeShort(definition.floorScaleX)
            111 -> writeShort(definition.floorScaleZ)
            112 -> writeShort(definition.floorScaleY)
            113 -> writeByte(definition.ambience)
            114 -> writeByte(definition.diffusion / DIFFUSION_STEP)
            115 -> writeByte(definition.team)
            125 -> {
                writeByte(definition.maleWieldX shr 2)
                writeByte(definition.maleWieldZ shr 2)
                writeByte(definition.maleWieldY shr 2)
            }
            126 -> {
                writeByte(definition.femaleWieldX shr 2)
                writeByte(definition.femaleWieldZ shr 2)
                writeByte(definition.femaleWieldY shr 2)
            }
            132 -> {
                val campaigns = definition.campaigns ?: IntArray(0)
                writeByte(campaigns.size)
                for (campaign in campaigns) {
                    writeShort(campaign)
                }
            }
            134 -> writeByte(definition.pickSizeShift)
            in 142..146 -> writeShort(definition.headModels!![opcode - 142])
            in 150..154 -> writeShort(definition.groundCursors!![opcode - 150])
            163 -> writeShort(definition.shardCombineAmount)
            164 -> writeString(definition.shardName)
            181 -> writeLong(definition.cost)
            in 190..199 -> {
                writeMedium(definition.stackIds!![opcode - 190])
                writeShort(definition.stackAmounts!![opcode - 190])
            }
            201 -> writeMedium(definition.noteId)
            202 -> writeMedium(definition.notedTemplateId)
            203 -> writeMedium(definition.lendId)
            204 -> writeMedium(definition.lendTemplateId)
            205 -> writeMedium(definition.bindId)
            206 -> writeMedium(definition.boundTemplateId)
            207 -> writeMedium(definition.shardItemId)
            208 -> writeMedium(definition.shardTemplateId)
            249 -> writeParams(definition)
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private fun floorOption(index: Int): String? = if (index == 2) "Take" else null

    private fun option(index: Int): String? = if (index == 4) "Drop" else null

    private companion object {
        const val SHORT = 0xffff
        const val DIFFUSION_STEP = 5
    }
}
