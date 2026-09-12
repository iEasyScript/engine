package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.QUICK_CHAT
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.QuickChatParamType
import world.gregs.voidps.cache.type.data.QuickChatPhraseType
import world.gregs.voidps.cache.type.encoder.QuickChatPhraseEncoder

private const val PHRASES = 1

class QuickChatPhraseDecoder : TypeDecoder<QuickChatPhraseType>(QUICK_CHAT) {

    private val encoder = QuickChatPhraseEncoder()

    override fun create(size: Int) = Array(size) { QuickChatPhraseType(it) }

    override fun canonicalOpcodes(definition: QuickChatPhraseType): IntArray = encoder.opcodes(definition)

    override fun getArchive(id: Int) = PHRASES

    override fun size(cache: Cache) = cache.lastFileId(index, PHRASES)

    override fun QuickChatPhraseType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> stringParts = buffer.readString().split('<').toTypedArray()
            2 -> responses = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedShort() }
            3, 5 -> {
                val count = buffer.readUnsignedByte()
                val params = Array(count) { IntArray(0) }
                types = IntArray(count) { param ->
                    val type = buffer.readUnsignedShort()
                    val idCount = QuickChatParamType.getType(type)?.idCount ?: 0
                    params[param] = IntArray(idCount) {
                        if (opcode == 5) buffer.readVarInt() else buffer.readUnsignedShort()
                    }
                    type
                }
                ids = params
            }
            4 -> flag = false
            else -> unknown(opcode, buffer)
        }
    }
}
