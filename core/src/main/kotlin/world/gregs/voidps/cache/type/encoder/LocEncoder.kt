package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.LocLight
import world.gregs.voidps.cache.type.data.LocType

/**
 * Writes an index 16 loc file.
 *
 * Six opcodes spell the contoured ground mode and each carries a different payload width, so which one
 * a file used is only in the record order. The animation odds of opcode 106 are rescaled to 65535ths on
 * read, so the file's own bytes ride alongside them.
 */
class LocEncoder : OpcodeEncoder<LocType>() {

    override fun opcodes(definition: LocType): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelIds != null) {
            opcodes.add(1)
        }
        if (definition.name != "null") {
            opcodes.add(2)
        }
        if (definition.sizeX != 1) {
            opcodes.add(14)
        }
        if (definition.sizeY != 1) {
            opcodes.add(15)
        }
        if (!definition.blocksSky) {
            opcodes.add(if (definition.solid == 0) 17 else 18)
        }
        if (definition.interactive != -1) {
            opcodes.add(19)
        }
        if (definition.contouredGround.toInt() == 1) {
            opcodes.add(21)
        }
        if (definition.delayShading) {
            opcodes.add(22)
        }
        if (definition.culling == 1) {
            opcodes.add(23)
        }
        if (definition.singleAnimation != -1) {
            opcodes.add(24)
        }
        if (definition.solid == 1) {
            opcodes.add(27)
        }
        if (definition.offsetMultiplier != 64) {
            opcodes.add(28)
        }
        if (definition.ambient != 0) {
            opcodes.add(29)
        }
        val options = definition.options
        if (options != null) {
            for (index in 0 until 5) {
                if (options[index] != null) {
                    opcodes.add(30 + index)
                }
            }
        }
        if (definition.contrast != 0) {
            opcodes.add(39)
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
        if (definition.mirrored) {
            opcodes.add(62)
        }
        if (!definition.castsShadow) {
            opcodes.add(64)
        }
        if (definition.modelSizeX != 128) {
            opcodes.add(65)
        }
        if (definition.modelSizeZ != 128) {
            opcodes.add(66)
        }
        if (definition.modelSizeY != 128) {
            opcodes.add(67)
        }
        if (definition.blockFlag != 0) {
            opcodes.add(69)
        }
        if (definition.offsetX != 0) {
            opcodes.add(70)
        }
        if (definition.offsetZ != 0) {
            opcodes.add(71)
        }
        if (definition.offsetY != 0) {
            opcodes.add(72)
        }
        if (definition.blocksLand) {
            opcodes.add(73)
        }
        if (definition.ignoreOnRoute) {
            opcodes.add(74)
        }
        if (definition.supportItems != -1) {
            opcodes.add(75)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 208 else 207)
        }
        if (definition.unknown78a != -1) {
            opcodes.add(78)
        }
        if (definition.unknown79c != null) {
            opcodes.add(79)
        }
        if (definition.contouredGround.toInt() == 2) {
            opcodes.add(81)
        }
        if (definition.hideMinimap) {
            opcodes.add(82)
        }
        if (!definition.unknown88) {
            opcodes.add(88)
        }
        if (!definition.animateImmediately) {
            opcodes.add(89)
        }
        if (definition.isMembers) {
            opcodes.add(91)
        }
        if (definition.contouredGround.toInt() == 3) {
            opcodes.add(93)
        }
        if (definition.contouredGround.toInt() == 4) {
            opcodes.add(94)
        }
        if (definition.contouredGround.toInt() == 5) {
            opcodes.add(95)
        }
        if (definition.unknown97) {
            opcodes.add(97)
        }
        if (definition.unknown98) {
            opcodes.add(98)
        }
        if (definition.unknown101 != 0) {
            opcodes.add(101)
        }
        if (definition.mapscene != -1) {
            opcodes.add(102)
        }
        if (definition.culling == 0) {
            opcodes.add(103)
        }
        if (definition.unknown104 != 255) {
            opcodes.add(104)
        }
        if (definition.invertMapScene) {
            opcodes.add(105)
        }
        if (definition.rawPercents != null) {
            opcodes.add(106)
        }
        if (definition.mapDefinitionId != -1) {
            opcodes.add(107)
        }
        if (definition.unknown160 != null) {
            opcodes.add(160)
        }
        if (definition.unknown163a.toInt() != 0 || definition.unknown163b.toInt() != 0 ||
            definition.unknown163c.toInt() != 0 || definition.unknown163d.toInt() != 0
        ) {
            opcodes.add(163)
        }
        if (definition.unknown164 != 0) {
            opcodes.add(164)
        }
        if (definition.unknown165 != 0) {
            opcodes.add(165)
        }
        if (definition.unknown166 != 0) {
            opcodes.add(166)
        }
        if (definition.unknown167 != 0) {
            opcodes.add(167)
        }
        if (definition.unknown173a != 256 || definition.unknown173b != 256) {
            opcodes.add(173)
        }
        if (definition.unknown178 != 0) {
            opcodes.add(178)
        }
        if (definition.dynamicTint) {
            opcodes.add(189)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: LocType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val models = definition.modelIds ?: emptyArray()
                val types = definition.modelTypes ?: ByteArray(0)
                writeByte(models.size)
                for (index in models.indices) {
                    writeByte(types[index].toInt())
                    writeByte(models[index].size)
                    for (model in models[index]) {
                        writeBigSmart(model)
                    }
                }
            }
            2 -> writeString(definition.name)
            14 -> writeByte(definition.sizeX)
            15 -> writeByte(definition.sizeY)
            17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 82, 88, 89, 91, 94, 97, 98, 103, 105, 189 -> Unit
            19 -> writeByte(definition.interactive)
            24 -> writeBigSmart(definition.singleAnimation)
            28 -> writeByte(definition.offsetMultiplier ushr 2)
            29 -> writeByte(definition.ambient - AMBIENT_BIAS)
            in 30..34 -> writeString(definition.options!![opcode - 30])
            39 -> writeByte(definition.contrast / CONTRAST_STEP)
            40 -> writeColours(definition)
            41 -> writeTextures(definition)
            42 -> writePalette(definition.recolourPalette)
            65 -> writeShort(definition.modelSizeX)
            66 -> writeShort(definition.modelSizeZ)
            67 -> writeShort(definition.modelSizeY)
            69 -> writeByte(definition.blockFlag)
            70 -> writeShort(definition.offsetX ushr 2)
            71 -> writeShort(definition.offsetZ ushr 2)
            72 -> writeShort(definition.offsetY ushr 2)
            75 -> writeByte(definition.supportItems)
            77, 92 -> writeSmartTransforms(definition, opcode == 92, wideVarbit = false)
            207, 208 -> writeSmartTransforms(definition, opcode == 208, wideVarbit = true)
            78 -> {
                writeShort(definition.unknown78a)
                writeByte(definition.unknown78b)
            }
            79 -> {
                writeShort(definition.unknown79a)
                writeShort(definition.unknown79b)
                writeByte(definition.unknown79d)
                val values = definition.unknown79c ?: IntArray(0)
                writeByte(values.size)
                for (value in values) {
                    writeShort(value)
                }
            }
            81 -> writeByte(definition.contouredGroundValue / CONTOUR_STEP)
            93, 95 -> writeShort(definition.contouredGroundValue)
            101 -> writeByte(definition.unknown101)
            102 -> writeShort(definition.mapscene)
            104 -> writeByte(definition.unknown104)
            106 -> {
                val animations = definition.animations ?: IntArray(0)
                val percents = definition.rawPercents ?: IntArray(0)
                writeByte(animations.size)
                for (index in animations.indices) {
                    writeBigSmart(animations[index])
                    writeByte(percents[index])
                }
            }
            107 -> writeShort(definition.mapDefinitionId)
            in 150..154 -> writeString(definition.options!![opcode - 150])
            160 -> {
                val values = definition.unknown160 ?: IntArray(0)
                writeByte(values.size)
                for (value in values) {
                    writeShort(value)
                }
            }
            162 -> writeInt(definition.contouredGroundValue)
            163 -> {
                writeByte(definition.unknown163a.toInt())
                writeByte(definition.unknown163b.toInt())
                writeByte(definition.unknown163c.toInt())
                writeByte(definition.unknown163d.toInt())
            }
            164 -> writeShort(definition.unknown164)
            165 -> writeShort(definition.unknown165)
            166 -> writeShort(definition.unknown166)
            167 -> writeShort(definition.unknown167)
            173 -> {
                writeShort(definition.unknown173a)
                writeShort(definition.unknown173b)
            }
            178 -> writeByte(definition.unknown178)
            206 -> {
                writeUnused(definition, opcode, occurrence)
                val lights = definition.lights ?: emptyList()
                writeByte(lights.size)
                for (light in lights) {
                    writeLight(light)
                }
            }
            249 -> writeParams(definition)
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private fun Writer.writeLight(light: LocLight) {
        var flags = 0
        if (light.flag0) {
            flags = flags or 0x1
        }
        if (light.flag1) {
            flags = flags or 0x2
        }
        if (light.flag2) {
            flags = flags or 0x4
        }
        writeByte(flags)
        writeFloat(light.x)
        writeFloat(-light.y)
        writeFloat(light.z)
        writeByte(light.type)
        writeFloat(light.intensity)
        writeMedium(light.colour)
        writeShort(light.unknownShort)
        writeShort(light.unknownSigned)
        writeFloat(light.unknownX)
        writeFloat(light.unknownY)
        writeFloat(light.unknownZ)
    }

    private companion object {
        const val AMBIENT_BIAS = 64
        const val CONTRAST_STEP = 5
        const val CONTOUR_STEP = 256
    }
}
