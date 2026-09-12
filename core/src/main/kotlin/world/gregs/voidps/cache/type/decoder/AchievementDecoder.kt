package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.ACHIEVEMENT_DEF
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.AchievementLink
import world.gregs.voidps.cache.type.data.AchievementProgressRequirement
import world.gregs.voidps.cache.type.data.AchievementSkillRequirement
import world.gregs.voidps.cache.type.data.AchievementType
import world.gregs.voidps.cache.type.data.AchievementVarValue
import world.gregs.voidps.cache.type.encoder.AchievementEncoder

class AchievementDecoder : TypeDecoder<AchievementType>(ACHIEVEMENT_DEF) {

    private val encoder = AchievementEncoder()

    override fun create(size: Int) = Array(size) { AchievementType(it) }

    override fun getArchive(id: Int) = id ushr 7

    override fun getFile(id: Int) = id and 0x7f

    override fun size(cache: Cache): Int {
        val lastArchive = cache.lastArchiveId(index)
        return lastArchive * 128 + cache.fileCount(index, lastArchive)
    }

    override fun canonicalOpcodes(definition: AchievementType): IntArray = encoder.opcodes(definition)

    override fun AchievementType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> name = buffer.readVersionedString()
            2 -> repeat(buffer.readUnsignedByte()) {
                val key = buffer.readUnsignedByte()
                texts[key] = buffer.readVersionedString()
            }
            3 -> category = buffer.readUnsignedShort()
            4 -> graphicId = buffer.readBigSmart()
            5 -> points = buffer.readUnsignedByte()
            6 -> unknown6 = buffer.readUnsignedShort()
            7 -> reward = buffer.readVersionedString()
            8 -> levelRequirements = buffer.readLevels()
            9 -> varpRequirements = buffer.readProgress(Reader::readUnsignedShort)
            10 -> varbitRequirements = buffer.readProgress(Reader::readUnsignedShort)
            11 -> previousAchievementLinks = buffer.readLinks()
            12 -> levelSubRequirements = buffer.readLevels()
            13 -> varpSubRequirements = buffer.readProgress(Reader::readUnsignedShort)
            14 -> varbitSubRequirements = buffer.readProgress(Reader::readUnsignedShort)
            15 -> subAchievementLinks = buffer.readLinks()
            16 -> subcategory = buffer.readUnsignedShort()
            17 -> hidden = true
            18 -> unknown18 = buffer.readUnsignedByte()
            19 -> unknown19 = false
            20 -> questRequirements = buffer.readLinks()
            21 -> questSubRequirements = buffer.readLinks()
            22 -> varpValueRequirements = buffer.readVarValues(Reader::readUnsignedShort)
            23 -> varpValueSubRequirements = buffer.readVarValues(Reader::readUnsignedShort)
            24 -> varbitValueRequirements = buffer.readVarValues(Reader::readUnsignedShort)
            25 -> varbitValueSubRequirements = buffer.readVarValues(Reader::readUnsignedShort)
            26 -> unknown26 = buffer.readUnsignedShort()
            27 -> unknown27 = true
            28 -> requirementGroupTargets = buffer.readTargets()
            29 -> requirementGroups = buffer.readUnsignedByte()
            30 -> subRequirementGroupTargets = buffer.readTargets()
            31 -> subRequirementGroups = buffer.readUnsignedByte()
            32 -> {
                unknown32a = buffer.readUnsignedByte()
                unknown32b = buffer.readUnsignedByte()
                unknown32c = buffer.readUnsignedByte()
            }
            33 -> varbitRequirements = buffer.readProgress(Reader::readUnsignedMedium)
            34 -> varbitValueRequirements = buffer.readVarValues(Reader::readUnsignedMedium)
            35 -> varbitSubRequirements = buffer.readProgress(Reader::readUnsignedMedium)
            36 -> varbitValueSubRequirements = buffer.readVarValues(Reader::readUnsignedMedium)
            else -> unknown(opcode, buffer)
        }
    }

    private inline fun <T> Reader.readRecords(record: () -> T) = List(readSmart()) { record() }

    private fun Reader.readLevels() = readRecords {
        val group = readUnsignedByte()
        val level = readUnsignedByte()
        val text = readVersionedString()
        AchievementSkillRequirement(group, List(readSmart()) { readUnsignedShort() }, level, text)
    }

    private fun Reader.readProgress(varId: Reader.() -> Int) = readRecords {
        val group = readUnsignedByte()
        val target = readBigSmart()
        val text = readVersionedString()
        AchievementProgressRequirement(group, List(readSmart()) { varId() }, target, text)
    }

    private fun Reader.readLinks() = readRecords {
        AchievementLink(readUnsignedByte(), readUnsignedShort())
    }

    private fun Reader.readVarValues(varId: Reader.() -> Int) = readRecords {
        AchievementVarValue(
            readUnsignedByte(),
            varId(),
            readUnsignedByte(),
            readVersionedString(),
            readUnsignedByte(),
        )
    }

    private fun Reader.readTargets() = readRecords { readSmart() }
}
