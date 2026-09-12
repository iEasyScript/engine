package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.AchievementLink
import world.gregs.voidps.cache.type.data.AchievementProgressRequirement
import world.gregs.voidps.cache.type.data.AchievementSkillRequirement
import world.gregs.voidps.cache.type.data.AchievementType
import world.gregs.voidps.cache.type.data.AchievementVarValue

class AchievementEncoder : OpcodeEncoder<AchievementType>() {

    override fun opcodes(definition: AchievementType): IntArray {
        val opcodes = IntArrayList()
        if (definition.hidden) {
            opcodes.add(17)
        }
        if (definition.category != -1) {
            opcodes.add(3)
        }
        if (definition.subcategory != -1) {
            opcodes.add(16)
        }
        if (definition.points != null) {
            opcodes.add(5)
        }
        if (!definition.unknown19) {
            opcodes.add(19)
        }
        if (definition.unknown6 != -1) {
            opcodes.add(6)
        }
        if (definition.reward != "null") {
            opcodes.add(7)
        }
        if (definition.unknown26 != -1) {
            opcodes.add(26)
        }
        if (definition.name != "null") {
            opcodes.add(1)
        }
        if (definition.texts.isNotEmpty()) {
            opcodes.add(2)
        }
        if (definition.graphicId != -1) {
            opcodes.add(4)
        }
        if (definition.unknown18 != -1) {
            opcodes.add(18)
        }
        if (definition.requirementGroupTargets != null) {
            opcodes.add(28)
        }
        if (definition.requirementGroups != -1) {
            opcodes.add(29)
        }
        if (definition.levelRequirements != null) {
            opcodes.add(8)
        }
        if (definition.varpRequirements != null) {
            opcodes.add(9)
        }
        if (definition.varbitRequirements != null) {
            opcodes.add(33)
        }
        if (definition.previousAchievementLinks != null) {
            opcodes.add(11)
        }
        if (definition.questRequirements != null) {
            opcodes.add(20)
        }
        if (definition.varpValueRequirements != null) {
            opcodes.add(22)
        }
        if (definition.varbitValueRequirements != null) {
            opcodes.add(34)
        }
        if (definition.subRequirementGroupTargets != null) {
            opcodes.add(30)
        }
        if (definition.subRequirementGroups != -1) {
            opcodes.add(31)
        }
        if (definition.unknown32a != -1) {
            opcodes.add(32)
        }
        if (definition.levelSubRequirements != null) {
            opcodes.add(12)
        }
        if (definition.varpSubRequirements != null) {
            opcodes.add(13)
        }
        if (definition.varbitSubRequirements != null) {
            opcodes.add(35)
        }
        if (definition.subAchievementLinks != null) {
            opcodes.add(15)
        }
        if (definition.questSubRequirements != null) {
            opcodes.add(21)
        }
        if (definition.varpValueSubRequirements != null) {
            opcodes.add(23)
        }
        if (definition.varbitValueSubRequirements != null) {
            opcodes.add(36)
        }
        if (definition.unknown27) {
            opcodes.add(27)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: AchievementType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeVersionedString(definition.name)
            2 -> {
                writeByte(definition.texts.size)
                for ((key, text) in definition.texts) {
                    writeByte(key)
                    writeVersionedString(text)
                }
            }
            3 -> writeShort(definition.category)
            4 -> writeBigSmart(definition.graphicId)
            5 -> writeByte(definition.points ?: 0)
            6 -> writeShort(definition.unknown6)
            7 -> writeVersionedString(definition.reward)
            8 -> writeLevels(definition.levelRequirements)
            9 -> writeProgress(definition.varpRequirements, Writer::writeShort)
            10 -> writeProgress(definition.varbitRequirements, Writer::writeShort)
            11 -> writeLinks(definition.previousAchievementLinks)
            12 -> writeLevels(definition.levelSubRequirements)
            13 -> writeProgress(definition.varpSubRequirements, Writer::writeShort)
            14 -> writeProgress(definition.varbitSubRequirements, Writer::writeShort)
            15 -> writeLinks(definition.subAchievementLinks)
            16 -> writeShort(definition.subcategory)
            17, 19, 27 -> Unit
            18 -> writeByte(definition.unknown18)
            20 -> writeLinks(definition.questRequirements)
            21 -> writeLinks(definition.questSubRequirements)
            22 -> writeVarValues(definition.varpValueRequirements, Writer::writeShort)
            23 -> writeVarValues(definition.varpValueSubRequirements, Writer::writeShort)
            24 -> writeVarValues(definition.varbitValueRequirements, Writer::writeShort)
            25 -> writeVarValues(definition.varbitValueSubRequirements, Writer::writeShort)
            26 -> writeShort(definition.unknown26)
            28 -> writeTargets(definition.requirementGroupTargets)
            29 -> writeByte(definition.requirementGroups)
            30 -> writeTargets(definition.subRequirementGroupTargets)
            31 -> writeByte(definition.subRequirementGroups)
            32 -> {
                writeByte(definition.unknown32a)
                writeByte(definition.unknown32b)
                writeByte(definition.unknown32c)
            }
            33 -> writeProgress(definition.varbitRequirements, Writer::writeMedium)
            34 -> writeVarValues(definition.varbitValueRequirements, Writer::writeMedium)
            35 -> writeProgress(definition.varbitSubRequirements, Writer::writeMedium)
            36 -> writeVarValues(definition.varbitValueSubRequirements, Writer::writeMedium)
            else -> error("Unhandled achievement opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeLevels(records: List<AchievementSkillRequirement>?) {
        val entries = records ?: emptyList()
        writeSmart(entries.size)
        for (entry in entries) {
            writeByte(entry.group)
            writeByte(entry.level)
            writeVersionedString(entry.text)
            writeSmart(entry.skills.size)
            for (skill in entry.skills) {
                writeShort(skill)
            }
        }
    }

    private fun Writer.writeProgress(records: List<AchievementProgressRequirement>?, varId: Writer.(Int) -> Unit) {
        val entries = records ?: emptyList()
        writeSmart(entries.size)
        for (entry in entries) {
            writeByte(entry.group)
            writeBigSmart(entry.target)
            writeVersionedString(entry.text)
            writeSmart(entry.vars.size)
            for (id in entry.vars) {
                varId(id)
            }
        }
    }

    private fun Writer.writeLinks(records: List<AchievementLink>?) {
        val entries = records ?: emptyList()
        writeSmart(entries.size)
        for (entry in entries) {
            writeByte(entry.group)
            writeShort(entry.id)
        }
    }

    private fun Writer.writeVarValues(records: List<AchievementVarValue>?, varId: Writer.(Int) -> Unit) {
        val entries = records ?: emptyList()
        writeSmart(entries.size)
        for (entry in entries) {
            writeByte(entry.group)
            varId(entry.varId)
            writeByte(entry.amount)
            writeVersionedString(entry.text)
            writeByte(entry.value)
        }
    }

    private fun Writer.writeTargets(targets: List<Int>?) {
        val entries = targets ?: emptyList()
        writeSmart(entries.size)
        for (target in entries) {
            writeSmart(target)
        }
    }
}
