package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.cache.type.data.QuestVarProgress
import world.gregs.voidps.cache.type.data.QuestVarRequirement

class QuestEncoder : OpcodeEncoder<QuestType>() {

    override fun opcodes(definition: QuestType): IntArray {
        val opcodes = IntArrayList()
        if (definition.name != null) {
            opcodes.add(1)
        }
        if (definition.sortName != null) {
            opcodes.add(2)
        }
        if (definition.varpProgress != null) {
            opcodes.add(3)
        }
        if (definition.varbitProgress != null) {
            opcodes.add(22)
        }
        if (definition.parentQuest != -1) {
            opcodes.add(5)
        }
        if (definition.unknown6 != -1) {
            opcodes.add(6)
        }
        if (definition.difficulty != -1) {
            opcodes.add(7)
        }
        if (definition.unknown8) {
            opcodes.add(8)
        }
        if (definition.questPoints != -1) {
            opcodes.add(9)
        }
        if (definition.startTiles != null) {
            opcodes.add(10)
        }
        if (definition.unknownTile != -1) {
            opcodes.add(12)
        }
        if (definition.questRequirements != null) {
            opcodes.add(13)
        }
        if (definition.skillRequirements != null) {
            opcodes.add(14)
        }
        if (definition.questPointRequirement != 0) {
            opcodes.add(15)
        }
        if (definition.graphicId != -1) {
            opcodes.add(17)
        }
        if (definition.varpRequirements != null) {
            opcodes.add(18)
        }
        if (definition.varbitRequirements != null) {
            opcodes.add(19)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: QuestType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeVersionedString(definition.name ?: "")
            2 -> writeVersionedString(definition.sortName ?: "")
            3 -> writeProgress(definition.varpProgress, Writer::writeShort)
            4 -> writeProgress(definition.varbitProgress, Writer::writeShort)
            5 -> writeShort(definition.parentQuest)
            6 -> writeByte(definition.unknown6)
            7 -> writeByte(definition.difficulty)
            8 -> Unit
            9 -> writeByte(definition.questPoints)
            10 -> {
                val tiles = definition.startTiles ?: IntArray(0)
                writeByte(tiles.size)
                for (tile in tiles) {
                    writeInt(tile)
                }
            }
            12 -> writeInt(definition.unknownTile)
            13 -> {
                val quests = definition.questRequirements ?: emptyList()
                writeByte(quests.size)
                for (quest in quests) {
                    writeShort(quest)
                }
            }
            14 -> {
                val skills = definition.skillRequirements ?: emptyList()
                writeByte(skills.size)
                for (skill in skills) {
                    writeByte(skill.skill)
                    writeByte(skill.level)
                }
            }
            15 -> writeShort(definition.questPointRequirement)
            17 -> writeBigSmart(definition.graphicId)
            18 -> writeRequirements(definition.varpRequirements)
            19 -> writeRequirements(definition.varbitRequirements)
            22 -> writeProgress(definition.varbitProgress, Writer::writeMedium)
            249 -> writeParams(definition)
            else -> error("Unhandled quest opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeProgress(progress: List<QuestVarProgress>?, varId: Writer.(Int) -> Unit) {
        val entries = progress ?: emptyList()
        writeByte(entries.size)
        for (entry in entries) {
            varId(entry.varId)
            writeInt(entry.startedValue)
            writeInt(entry.completedValue)
        }
    }

    private fun Writer.writeRequirements(requirements: List<QuestVarRequirement>?) {
        val entries = requirements ?: emptyList()
        writeByte(entries.size)
        for (entry in entries) {
            writeInt(entry.varId)
            writeInt(entry.min)
            writeInt(entry.max)
            writeString(entry.text)
        }
    }
}
