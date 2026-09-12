package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.NpcType

/**
 * Writes an index 18 npc file.
 *
 * Options come from two opcode ranges into one array - 30 to 34 and 150 to 154 - so which range an
 * option was written in is only in the record order, never in the array.
 */
class NpcEncoder : OpcodeEncoder<NpcType>() {

    override fun opcodes(definition: NpcType): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelIds != null) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.size != 1) {
            opcodes.add(12)
        }
        for (index in 0 until 5) {
            if (definition.options[index] != null) {
                opcodes.add(30 + index)
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
        if (definition.dialogueModels != null) {
            opcodes.add(60)
        }
        if (!definition.drawMinimapDot) {
            opcodes.add(93)
        }
        if (definition.combat != -1) {
            opcodes.add(95)
        }
        if (definition.scaleXY != 128) {
            opcodes.add(97)
        }
        if (definition.scaleZ != 128) {
            opcodes.add(98)
        }
        if (definition.priorityRender) {
            opcodes.add(99)
        }
        if (definition.ambient != 0) {
            opcodes.add(100)
        }
        if (definition.contrast != 0) {
            opcodes.add(101)
        }
        if (definition.rotation != 32) {
            opcodes.add(103)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 188 else 187)
        }
        if (!definition.clickable) {
            opcodes.add(107)
        }
        if (!definition.animateIdle) {
            opcodes.add(111)
        }
        if (definition.primaryShadowColour.toInt() != 0 || definition.secondaryShadowColour.toInt() != 0) {
            opcodes.add(113)
        }
        if (definition.primaryShadowModifier.toInt() != -96 || definition.secondaryShadowModifier.toInt() != -16) {
            opcodes.add(114)
        }
        opcodes.add(119)
        if (definition.translations != null) {
            opcodes.add(121)
        }
        if (definition.hitbarGraphic != -1) {
            opcodes.add(122)
        }
        if (definition.height != -1) {
            opcodes.add(123)
        }
        if (definition.respawnDirection.toInt() != 4) {
            opcodes.add(125)
        }
        if (definition.renderEmote != -1) {
            opcodes.add(127)
        }
        if (definition.walkSound != -1 || definition.crawlSound != -1 || definition.idleSound != -1 || definition.runSound != -1) {
            opcodes.add(134)
        }
        if (definition.primaryCursorOp != -1) {
            opcodes.add(135)
        }
        if (definition.secondaryCursorOp != -1) {
            opcodes.add(136)
        }
        if (definition.attackCursor != -1) {
            opcodes.add(137)
        }
        if (definition.armyIcon != -1) {
            opcodes.add(138)
        }
        if (definition.graphicId != -1) {
            opcodes.add(139)
        }
        if (definition.ambientSoundVolume != 255) {
            opcodes.add(140)
        }
        if (definition.visiblePriority) {
            opcodes.add(141)
        }
        if (definition.mapFunction != -1) {
            opcodes.add(142)
        }
        if (definition.invisiblePriority) {
            opcodes.add(143)
        }
        if (definition.hue.toInt() != 0 || definition.saturation.toInt() != 0 ||
            definition.lightness.toInt() != 0 || definition.opacity.toInt() != 0
        ) {
            opcodes.add(155)
        }
        if (definition.mainOptionIndex.toInt() == 1) {
            opcodes.add(158)
        }
        if (definition.mainOptionIndex.toInt() == 0) {
            opcodes.add(159)
        }
        if (definition.unknown160 != null) {
            opcodes.add(160)
        }
        if (definition.unknown163 != -1) {
            opcodes.add(163)
        }
        if (definition.unknown164a != 256 || definition.unknown164b != 256) {
            opcodes.add(164)
        }
        if (definition.unknown165 != 0) {
            opcodes.add(165)
        }
        if (definition.unknown168 != 0) {
            opcodes.add(168)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: NpcType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val models = definition.modelIds ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            2 -> writeString(definition.name)
            12 -> writeByte(definition.size)
            in 30..34 -> writeString(definition.options[opcode - 30])
            39, 101 -> writeByte(definition.contrast / CONTRAST_STEP)
            40 -> writeColours(definition)
            41 -> writeTextures(definition)
            42 -> writePalette(definition.recolourPalette)
            60 -> {
                val models = definition.dialogueModels ?: IntArray(0)
                writeByte(models.size)
                for (model in models) {
                    writeBigSmart(model)
                }
            }
            93, 99, 107, 111, 141, 143, 158, 159 -> Unit
            95 -> writeShort(definition.combat)
            97 -> writeShort(definition.scaleXY)
            98 -> writeShort(definition.scaleZ)
            100 -> writeByte(definition.ambient - AMBIENT_BIAS)
            103 -> writeShort(definition.rotation)
            106, 118 -> writeNpcTransforms(definition, opcode == 118, wideVarbit = false)
            187, 188 -> writeNpcTransforms(definition, opcode == 188, wideVarbit = true)
            113 -> {
                writeShort(definition.primaryShadowColour.toInt())
                writeShort(definition.secondaryShadowColour.toInt())
            }
            114 -> {
                writeByte(definition.primaryShadowModifier.toInt())
                writeByte(definition.secondaryShadowModifier.toInt())
            }
            119 -> writeByte(definition.walkMask.toInt())
            121 -> {
                val translations = definition.translations ?: emptyArray()
                writeByte(translations.size)
                for (translation in translations) {
                    for (value in translation!!) {
                        writeByte(value)
                    }
                }
            }
            122 -> writeShort(definition.hitbarGraphic)
            123 -> writeShort(definition.height)
            125 -> writeByte(definition.respawnDirection.toInt())
            127 -> writeShort(definition.renderEmote)
            134 -> {
                writeShort(if (definition.walkSound == -1) NULL else definition.walkSound)
                writeShort(if (definition.crawlSound == -1) NULL else definition.crawlSound)
                writeShort(if (definition.idleSound == -1) NULL else definition.idleSound)
                writeShort(if (definition.runSound == -1) NULL else definition.runSound)
                writeByte(definition.soundDistance)
            }
            135 -> {
                writeByte(definition.primaryCursorOp)
                writeShort(definition.primaryCursor)
            }
            136 -> {
                writeByte(definition.secondaryCursorOp)
                writeShort(definition.secondaryCursor)
            }
            137 -> writeShort(definition.attackCursor)
            138 -> writeBigSmart(definition.armyIcon)
            139 -> writeBigSmart(definition.graphicId)
            140 -> writeByte(definition.ambientSoundVolume)
            142 -> writeShort(definition.mapFunction)
            in 150..154 -> writeString(definition.options[opcode - 150])
            155 -> {
                writeByte(definition.hue.toInt())
                writeByte(definition.saturation.toInt())
                writeByte(definition.lightness.toInt())
                writeByte(definition.opacity.toInt())
            }
            160 -> {
                val values = definition.unknown160 ?: IntArray(0)
                writeByte(values.size)
                for (value in values) {
                    writeShort(value)
                }
            }
            163 -> writeByte(definition.unknown163)
            164 -> {
                writeShort(definition.unknown164a)
                writeShort(definition.unknown164b)
            }
            165 -> writeByte(definition.unknown165)
            168 -> writeByte(definition.unknown168)
            249 -> writeParams(definition)
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private companion object {
        const val NULL = 65535
        const val AMBIENT_BIAS = 64
        const val CONTRAST_STEP = 5
    }
}
