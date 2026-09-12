package world.gregs.voidps.cache.cs2

import world.gregs.voidps.gameval.Gameval

/**
 * Jagex's own clientscript names, recovered from the name hashes JS5 index 12 carries.
 *
 * A name is stored in the form that hashes — `[clientscript,meslayer_mode2]` — because the category
 * is part of the string Jagex hashed, and two scripts routinely share everything after the comma.
 * The bare body is what an editor wants to see, so [identifier] hands out that half, falling back to
 * the category-qualified form for the pairs that would otherwise clash and refusing anything a
 * reader could mistake for an opcode, a variable or a generated name.
 */
object Cs2ScriptNames {

    private val NAME = Regex("""^\[(\w+),(.+)]$""")

    private val identifiers: Map<Int, String> by lazy { resolve() }

    private val ids: Map<String, Int> by lazy { identifiers.entries.associate { (id, name) -> name to id } }

    /** The full `[category,name]` string, or null for a script whose hash is still uncracked. */
    fun name(id: Int): String? = Gameval.name(Gameval.CLIENTSCRIPT, id)

    fun identifier(id: Int): String? = identifiers[id]

    fun idOf(identifier: String): Int? = ids[identifier]

    private fun resolve(): Map<Int, String> {
        val parsed = Gameval.entries(Gameval.CLIENTSCRIPT).mapNotNull { (id, name) ->
            val match = NAME.matchEntire(name) ?: return@mapNotNull null
            Triple(id, match.groupValues[1], match.groupValues[2])
        }
        val shared = parsed.groupingBy { it.third }.eachCount()
        val out = HashMap<Int, String>(parsed.size)
        val taken = HashSet<String>()
        for ((id, category, body) in parsed.sortedBy { it.first }) {
            val candidate = if (shared.getValue(body) == 1) body else "${category}_$body"
            if (!usable(candidate) || !taken.add(candidate)) continue
            out[id] = candidate
        }
        return out
    }

    private val generated = Regex("^(int|str|long|tmpInt|tmpStr|tmpLong|array|script)\\d")

    private fun usable(name: String): Boolean =
        Cs2Gamevals.identifierSafe(name) &&
            !generated.containsMatchIn(name) &&
            !Cs2Gamevals.isTable(name) &&
            Cs2Opcodes.byTsName(name) == null &&
            Cs2Opcodes.byName(name) == null &&
            Cs2Symbols.variableOf(name) == null &&
            Gameval.id(Gameval.INTERFACE, name) == null &&
            name != "hook" && name != "noHook" && name != "component" && name != "discard"
}
