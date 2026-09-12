package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.type.data.VarDomain
import world.gregs.voidps.cache.type.data.VarDomainType
import world.gregs.voidps.cache.type.decoder.VarClanDecoder
import world.gregs.voidps.cache.type.decoder.VarClanSettingDecoder
import world.gregs.voidps.cache.type.decoder.VarGroupDecoder
import world.gregs.voidps.cache.type.decoder.VarNpcDecoder
import world.gregs.voidps.cache.type.decoder.VarPlayerDecoder
import world.gregs.voidps.cache.type.decoder.VarcDecoder

/**
 * The stack a domain-tagged variable lives on, as its own config record declares it.
 *
 * `push_var` and `pop_var` take their stack from the variable's declared type, and nothing in the
 * instruction stream mentions it. Reading the declaration is therefore the only way to type a
 * variable the corpus never writes.
 */
object Cs2VarDeclarations {

    @Volatile
    private var declarations: Pair<Map<Int, Cs2VarBase>, Map<Int, Int>>? = null

    /** Drop what the previous cache's records declared; see [Cs2Records]. */
    fun forget() {
        synchronized(this) { declarations = null }
    }

    fun baseOf(domain: Int, id: Int): Cs2VarBase? = declarations().first[key(domain, id)]

    /** The script-variable type id a variable's record declares, or null where it declares none. */
    fun declaredOf(domain: Int, id: Int): Int? = declarations().second[key(domain, id)]

    private fun key(domain: Int, id: Int) = (domain shl 16) or id

    private fun declarations(): Pair<Map<Int, Cs2VarBase>, Map<Int, Int>> =
        declarations ?: synchronized(this) { declarations ?: build().also { declarations = it } }

    private fun build(): Pair<Map<Int, Cs2VarBase>, Map<Int, Int>> {
        val cache = Cs2Records.cache()
        val out = HashMap<Int, Cs2VarBase>()
        val declared = HashMap<Int, Int>()
        collect(out, declared, VarDomain.PLAYER, VarPlayerDecoder().load(cache))
        collect(out, declared, VarDomain.NPC, VarNpcDecoder().load(cache))
        collect(out, declared, VarDomain.CLIENT, VarcDecoder().load(cache))
        collect(out, declared, VarDomain.CLAN, VarClanDecoder().load(cache))
        collect(out, declared, VarDomain.CLAN_SETTING, VarClanSettingDecoder().load(cache))
        collect(out, declared, VarDomain.PLAYER_GROUP, VarGroupDecoder().load(cache))
        return out to declared
    }

    private fun collect(
        out: MutableMap<Int, Cs2VarBase>,
        types: MutableMap<Int, Int>,
        domain: VarDomain,
        declarations: Array<out VarDomainType>,
    ) {
        for (id in declarations.indices) {
            val declared = declarations[id].type
            if (declared in UNDECLARED) continue
            types[key(domain.id, id)] = declared
            val base = Cs2VarTypes.baseOfId(declared) ?: continue
            out[key(domain.id, id)] = base
        }
    }

    /** -1 is no record at all; 0 is treated the same, the int it would name being the default anyway. */
    private val UNDECLARED = setOf(-1, 0)
}
