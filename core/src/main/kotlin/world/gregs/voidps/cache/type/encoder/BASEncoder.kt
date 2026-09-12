package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.BASType

class BASEncoder : OpcodeEncoder<BASType>() {

    override fun opcodes(definition: BASType): IntArray {
        val opcodes = IntArrayList()
        if (definition.standAnim != -1 || definition.standTurnAnim != -1) {
            opcodes.add(1)
        }
        addAnimation(opcodes, 2, definition.walkAnim)
        addAnimation(opcodes, 3, definition.runAnim)
        addAnimation(opcodes, 4, definition.turnAroundAnim)
        addAnimation(opcodes, 5, definition.turnRightAnim)
        addAnimation(opcodes, 6, definition.walkBackAnim)
        addAnimation(opcodes, 7, definition.walkLeftAnim)
        addAnimation(opcodes, 8, definition.walkRightAnim)
        addAnimation(opcodes, 9, definition.crawlAnim)
        if (definition.renderOffsetX != 0 || definition.renderOffsetY != 0) {
            opcodes.add(26)
        }
        addAnimation(opcodes, 38, definition.anim38)
        addAnimation(opcodes, 39, definition.anim39)
        addAnimation(opcodes, 40, definition.anim40)
        addAnimation(opcodes, 41, definition.anim41)
        addAnimation(opcodes, 42, definition.anim42)
        addAnimation(opcodes, 43, definition.anim43)
        addAnimation(opcodes, 44, definition.anim44)
        if (definition.field45 != 0) {
            opcodes.add(45)
        }
        addAnimation(opcodes, 46, definition.anim46)
        addAnimation(opcodes, 47, definition.anim47)
        addAnimation(opcodes, 48, definition.anim48)
        addAnimation(opcodes, 49, definition.anim49)
        addAnimation(opcodes, 50, definition.anim50)
        addAnimation(opcodes, 51, definition.anim51)
        if (definition.renderOffsetX2 != 0 || definition.renderOffsetY2 != 0) {
            opcodes.add(54)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: BASType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeBigSmart(definition.standAnim)
                writeBigSmart(definition.standTurnAnim)
            }
            2 -> writeBigSmart(definition.walkAnim)
            3 -> writeBigSmart(definition.runAnim)
            4 -> writeBigSmart(definition.turnAroundAnim)
            5 -> writeBigSmart(definition.turnRightAnim)
            6 -> writeBigSmart(definition.walkBackAnim)
            7 -> writeBigSmart(definition.walkLeftAnim)
            8 -> writeBigSmart(definition.walkRightAnim)
            9 -> writeBigSmart(definition.crawlAnim)
            26 -> {
                writeByte(definition.renderOffsetX ushr 2)
                writeByte(definition.renderOffsetY ushr 2)
            }
            38 -> writeBigSmart(definition.anim38)
            39 -> writeBigSmart(definition.anim39)
            40 -> writeBigSmart(definition.anim40)
            41 -> writeBigSmart(definition.anim41)
            42 -> writeBigSmart(definition.anim42)
            43 -> writeBigSmart(definition.anim43)
            44 -> writeBigSmart(definition.anim44)
            45 -> writeShort(definition.field45)
            46 -> writeBigSmart(definition.anim46)
            47 -> writeBigSmart(definition.anim47)
            48 -> writeBigSmart(definition.anim48)
            49 -> writeBigSmart(definition.anim49)
            50 -> writeBigSmart(definition.anim50)
            51 -> writeBigSmart(definition.anim51)
            53 -> Unit
            54 -> {
                writeByte(definition.renderOffsetX2 ushr 2)
                writeByte(definition.renderOffsetY2 ushr 2)
            }
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private fun addAnimation(opcodes: IntArrayList, opcode: Int, animation: Int) {
        if (animation != -1) {
            opcodes.add(opcode)
        }
    }
}
