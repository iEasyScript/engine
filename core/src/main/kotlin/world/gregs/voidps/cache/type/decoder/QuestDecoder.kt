package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.QUESTS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.QuestSkillRequirement
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.cache.type.data.QuestVarProgress
import world.gregs.voidps.cache.type.data.QuestVarRequirement
import world.gregs.voidps.cache.type.encoder.QuestEncoder

class QuestDecoder : ConfigDecoder<QuestType>(QUESTS) {

    override fun create(size: Int) = Array(size) { QuestType(it) }

    private val encoder = QuestEncoder()

    override fun canonicalOpcodes(definition: QuestType): IntArray = encoder.opcodes(definition)

    override fun QuestType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> name = buffer.readVersionedString()
            2 -> sortName = buffer.readVersionedString()
            3 -> varpProgress = buffer.readVarProgress(Reader::readUnsignedShort)
            4 -> varbitProgress = buffer.readVarProgress(Reader::readUnsignedShort)
            5 -> parentQuest = buffer.readUnsignedShort()
            6 -> unknown6 = buffer.readUnsignedByte()
            7 -> difficulty = buffer.readUnsignedByte()
            8 -> unknown8 = true
            9 -> questPoints = buffer.readUnsignedByte()
            10 -> startTiles = IntArray(buffer.readUnsignedByte()) { buffer.readInt() }
            12 -> unknownTile = buffer.readInt()
            13 -> questRequirements = buffer.readList { buffer.readUnsignedShort() }
            14 -> skillRequirements = buffer.readList {
                QuestSkillRequirement(buffer.readUnsignedByte(), buffer.readUnsignedByte())
            }
            15 -> questPointRequirement = buffer.readUnsignedShort()
            17 -> graphicId = buffer.readBigSmart()
            18 -> varpRequirements = buffer.readVarRequirements()
            19 -> varbitRequirements = buffer.readVarRequirements()
            22 -> varbitProgress = buffer.readVarProgress(Reader::readUnsignedMedium)
            249 -> readParameters(buffer)
            else -> unknown(opcode, buffer)
        }
    }

    override fun changeValues(definitions: Array<QuestType>, definition: QuestType) {
        if (definition.sortName.isNullOrEmpty()) {
            definition.sortName = definition.name
        }
    }

    private inline fun <T> Reader.readList(entry: () -> T) = List(readUnsignedByte()) { entry() }

    private fun Reader.readVarProgress(varId: Reader.() -> Int) = readList {
        QuestVarProgress(varId(), readInt(), readInt())
    }

    private fun Reader.readVarRequirements() = readList {
        QuestVarRequirement(readInt(), readInt(), readInt(), readString())
    }

}
