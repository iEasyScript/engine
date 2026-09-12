package world.gregs.voidps.cache.cs2

import java.io.File

/**
 * What reading a handler's behaviour established about one opcode.
 *
 * Both fields are this toolchain's reading rather than the client's own text, so
 * the name belongs in [Cs2Naming.structural]. A slot the read could not settle is
 * carried as [ArgType.INT] so the argument renders as the number it is: a
 * plausible-looking wrong name reads as knowledge, and a gap is not evidence.
 */
data class Cs2Behaviour(
    val id: Int,
    val name: String?,
    val argTypes: List<ArgType>,
    val confidence: String,
)

object Cs2BehaviourImport {

    /** Names the read uses for tables this toolchain spells differently. */
    private val ALIASES = mapOf("OBJ" to ArgType.ITEM, "SPRITE" to ArgType.GRAPHIC)

    fun file(): File = File("./data/cs2/opcode-behaviour.csv")

    fun read(file: File): Map<Int, Cs2Behaviour> = Cs2Csv.read(file).mapNotNull { row ->
        val id = row["opcode"]?.toIntOrNull() ?: return@mapNotNull null
        Cs2Behaviour(
            id = id,
            name = row["name"]?.ifBlank { null },
            argTypes = row["argTypes"].orEmpty().split('|').filter { it.isNotBlank() }.map(::argType),
            confidence = row["confidence"].orEmpty(),
        )
    }.associateBy { it.id }

    private fun argType(name: String): ArgType {
        val trimmed = name.trim().uppercase()
        return ALIASES[trimmed] ?: runCatching { ArgType.valueOf(trimmed) }.getOrDefault(ArgType.INT)
    }
}
