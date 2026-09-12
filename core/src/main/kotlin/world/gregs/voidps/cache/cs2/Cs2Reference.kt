package world.gregs.voidps.cache.cs2

import java.io.File

/**
 * A name Jagex's own debug symbols carry for a handler, matched onto one of this
 * build's opcodes by what that handler does.
 *
 * The name is Jagex's; the build it was read off is not the one being decompiled
 * and the match between the two is this toolchain's. That is why it lands in
 * [Cs2Naming.reference] and never in [Cs2Naming.canonical], which is reserved
 * for text this build carries itself.
 */
data class Cs2Reference(
    val id: Int,
    val name: String,
    /** The whole int-argument shape, or null where the row described none of it. */
    val argTypes: List<ArgType>?,
    val unapplied: List<String>,
    val confidence: String,
)

object Cs2ReferenceImport {

    /**
     * Markers naming something other than an int argument, so they occupy no
     * slot. A hook's callback block is a string, its bound arguments and a script
     * id, all of which [OpKind.HOOK] already accounts for.
     */
    private val NOT_AN_ARGUMENT = setOf("HOOK")

    /** How a row spells "this opcode takes no int argument at all". */
    private const val NONE = "(NONE)"

    fun file(): File = File("./data/cs2/opcode-reference-names.csv")

    fun read(file: File): Map<Int, Cs2Reference> = Cs2Csv.read(file).mapNotNull { row ->
        val id = row["opcode"]?.toIntOrNull() ?: return@mapNotNull null
        val name = row["name"]?.ifBlank { null } ?: return@mapNotNull null
        val tokens = row["argTypes"].orEmpty().split('|')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != NONE }
        Cs2Reference(
            id = id,
            name = name,
            argTypes = if (row["argTypes"].isNullOrBlank()) null else tokens.mapNotNull(::argType),
            unapplied = tokens.filter { argType(it) == null },
            confidence = row["matchConfidence"].orEmpty(),
        )
    }.associateBy { it.id }

    /** An unrecognised token buys nothing an invented type would not, so it is dropped. */
    private fun argType(token: String): ArgType? =
        if (token in NOT_AN_ARGUMENT) null else runCatching { ArgType.valueOf(token) }.getOrNull()
}
