package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.QUICK_CHAT
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.QuickChatCatType
import world.gregs.voidps.cache.type.encoder.QuickChatCatEncoder

private const val CATEGORIES = 0

class QuickChatCatDecoder : TypeDecoder<QuickChatCatType>(QUICK_CHAT) {

    private val encoder = QuickChatCatEncoder()

    override fun create(size: Int) = Array(size) { QuickChatCatType(it) }

    override fun canonicalOpcodes(definition: QuickChatCatType): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = CATEGORIES

    override fun size(cache: Cache) = cache.lastFileId(index, CATEGORIES)

    override fun QuickChatCatType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> title = buffer.readString()
            2 -> {
                val count = buffer.readUnsignedByte()
                val ids = IntArray(count)
                subCategoryKeys = CharArray(count) { entry ->
                    ids[entry] = buffer.readUnsignedShort()
                    buffer.hotkey()
                }
                subCategoryIds = ids
            }
            3 -> {
                val count = buffer.readUnsignedByte()
                val ids = IntArray(count)
                phraseKeys = CharArray(count) { entry ->
                    ids[entry] = buffer.readUnsignedShort()
                    buffer.hotkey()
                }
                phraseIds = ids
            }
            4 -> flag = true
            else -> unknown(opcode, buffer)
        }
    }

    private fun Reader.hotkey(): Char {
        val value = readByte().toByte()
        return if (value.toInt() != 0) byteToChar(value) else Char.MIN_VALUE
    }
}
