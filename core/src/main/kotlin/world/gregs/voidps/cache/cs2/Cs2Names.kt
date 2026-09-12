package world.gregs.voidps.cache.cs2

import java.io.File

/**
 * Where an opcode's rendered name came from.
 *
 * The distinction is load-bearing: only [JAGEX] text is the client's own, and a
 * reader has to be able to tell it apart from a name this toolchain worked out.
 */
enum class Cs2NameOrigin {
    /** The client's own string, recovered whole from the handler's error path. */
    JAGEX,

    /** Mechanical split of one client string the dispatch table shares between two entries. */
    DERIVED,

    /** Jagex's own method name, read off a different build's symbols and matched by behaviour. */
    REFERENCE,

    /** Worked out from the opcode's role in the language; no client string was recovered. */
    STRUCTURAL,

    /** Nothing was recovered; the opcode renders as its number. */
    NUMERIC,
}

/**
 * Every name known for one opcode, kept apart by provenance.
 *
 * [canonical] is the client's text verbatim, including the handful that are not
 * identifiers and the ones several opcodes share. [derived] is this toolchain's
 * split of a shared canonical name and is never presented as the client's.
 * [reference] is Jagex's too, but it is another build's symbol matched onto this
 * one by behaviour, so a name this build carries itself always outranks it.
 */
data class Cs2Naming(
    val canonical: String? = null,
    val derived: String? = null,
    val reference: String? = null,
    val structural: String? = null,
) {
    val origin: Cs2NameOrigin
        get() = when {
            derived != null -> Cs2NameOrigin.DERIVED
            canonical != null -> Cs2NameOrigin.JAGEX
            reference != null -> Cs2NameOrigin.REFERENCE
            structural != null -> Cs2NameOrigin.STRUCTURAL
            else -> Cs2NameOrigin.NUMERIC
        }

    val isEmpty: Boolean get() = origin == Cs2NameOrigin.NUMERIC

    /** Every name recovered for the opcode, whatever tier it came from, for matching one by name. */
    val names: List<String>
        get() = listOfNotNull(canonical, derived, reference, structural?.lowercase())

    companion object {
        val NONE = Cs2Naming()
    }
}

object Cs2NameImport {

    fun read(file: File): Map<Int, Cs2Naming> {
        val out = HashMap<Int, Cs2Naming>()
        for (row in Cs2Csv.read(file)) {
            val id = row["opcode"]?.toIntOrNull() ?: continue
            val naming = Cs2Naming(
                canonical = row["canonical_name"]?.ifEmpty { null },
                derived = row["derived_name"]?.ifEmpty { null },
            )
            if (!naming.isEmpty) out[id] = naming
        }
        return out
    }
}

/**
 * Turns the recovered names into the identifiers the decompiled TypeScript and
 * the recompiler share.
 *
 * Every opcode gets exactly one identifier and no two share it, so a name always
 * resolves back to the opcode it was emitted for. Where a name cannot serve as
 * an identifier - several opcodes carry the same client string, the string is
 * not a valid identifier, or it collides with a TypeScript keyword - the opcode
 * number is appended. That suffix is this toolchain's, not the client's, which
 * is why the unsuffixed string stays available on the opcode.
 */
data class Cs2OpIdentifiers(val opName: String, val tsName: String)

object Cs2Identifiers {

    private val RESERVED = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if",
        "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this", "throw",
        "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static",
        "implements", "interface", "package", "private", "protected", "public", "declare",
    )

    fun assign(namings: Map<Int, Cs2Naming>): Map<Int, Cs2OpIdentifiers> {
        val preferred = namings.mapValues { (_, naming) -> usable(naming) }
        val shared = preferred.values.filterNotNull().groupingBy { it }.eachCount()
        return preferred.mapValues { (id, name) ->
            when {
                name == null -> Cs2OpIdentifiers("OP$id", "op$id")
                shared.getValue(name) > 1 -> Cs2OpIdentifiers("${name.uppercase()}_OP$id", "${name}_op$id")
                // Only the emitted spelling has to dodge TypeScript's keywords;
                // the uppercase name the toolchain matches on is unaffected.
                name in RESERVED -> Cs2OpIdentifiers(name.uppercase(), "${name}_op$id")
                else -> Cs2OpIdentifiers(name.uppercase(), name)
            }
        }
    }

    private fun usable(naming: Cs2Naming): String? {
        val name = naming.derived
            ?: naming.canonical
            ?: naming.reference
            ?: naming.structural?.lowercase()
            ?: return null
        return name.takeIf { isIdentifier(it) }
    }

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() && !name[0].isDigit() && name.all { it == '_' || it.isLetterOrDigit() }
}
