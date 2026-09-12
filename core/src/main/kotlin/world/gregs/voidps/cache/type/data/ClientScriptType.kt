package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.ClientScriptCodec
import world.gregs.voidps.cache.type.Extra

/** One `case key: -> jump` entry of a clientscript switch table. */
data class ClientScriptSwitchCase(val key: Int, val offset: Int)

/**
 * A clientscript as its container spells it out. Table order and within-table case order are
 * preserved because byte-identical re-encoding depends on them even though the client does not.
 *
 * [instructions] stays as bytes: see [ClientScriptCodec].
 */
class ClientScriptType(
    override var id: Int = -1,
    override var stringId: String = "",
    var name: String? = null,
    var instructions: ByteArray = EMPTY_INSTRUCTIONS,
    var instructionCount: Int = 0,
    var intLocalCount: Int = 0,
    var stringLocalCount: Int = 0,
    var longLocalCount: Int = 0,
    var intArgumentCount: Int = 0,
    var stringArgumentCount: Int = 0,
    var longArgumentCount: Int = 0,
    var switchTables: List<List<ClientScriptSwitchCase>> = emptyList(),
    override var extras: Map<String, Any>? = null,
) : CacheType, Extra {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClientScriptType) return false
        return id == other.id &&
            name == other.name &&
            instructions.contentEquals(other.instructions) &&
            instructionCount == other.instructionCount &&
            intLocalCount == other.intLocalCount &&
            stringLocalCount == other.stringLocalCount &&
            longLocalCount == other.longLocalCount &&
            intArgumentCount == other.intArgumentCount &&
            stringArgumentCount == other.stringArgumentCount &&
            longArgumentCount == other.longArgumentCount &&
            switchTables == other.switchTables
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + instructions.contentHashCode()
        result = 31 * result + instructionCount
        result = 31 * result + intLocalCount
        result = 31 * result + stringLocalCount
        result = 31 * result + longLocalCount
        result = 31 * result + intArgumentCount
        result = 31 * result + stringArgumentCount
        result = 31 * result + longArgumentCount
        result = 31 * result + switchTables.hashCode()
        return result
    }

    override fun toString(): String =
        "ClientScriptType(id=$id, name=$name, instructions=$instructionCount, " +
            "args=$intArgumentCount/$stringArgumentCount/$longArgumentCount, " +
            "locals=$intLocalCount/$stringLocalCount/$longLocalCount, switches=${switchTables.size})"

    companion object {
        private val EMPTY_INSTRUCTIONS = ByteArray(0)
        val EMPTY = ClientScriptType(-1)
    }
}
