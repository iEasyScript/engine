package world.gregs.voidps.cache.cs2

import world.gregs.voidps.cache.cs2.ir.VarSpace
import world.gregs.voidps.cache.Index
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the position an integer would decode to is somewhere the world actually has.
 *
 * A coordinate falling in a mapsquare the cache carries no map for is not a place, so it is no
 * competition for the component the same integer names. Squares are remembered because the corpus
 * asks about the same handful of them over and over.
 */
private object MappedSquares {

    private val known = ConcurrentHashMap<Int, Boolean>()

    fun forget() = known.clear()

    fun holds(value: Int): Boolean {
        val square = Cs2Packing.mapSquareOf(value)
        return known.getOrPut(square) { Cs2Records.cache().exists(Index.MAPS, square) }
    }
}

/** What holds up reading a bare integer as an interface component. */
enum class Cs2ComponentBasis {
    /** Nothing else the value could be: the dictionary's reading is the only one on offer. */
    UNAMBIGUOUS,

    /** The value reads as a position too, and only its shape picks the component. */
    SHAPE,
}

/**
 * Renders CS2 operands as the dev names Jagex itself uses, taken from the gameval (RSCM) tables.
 *
 * Only a name that exists in a gameval table is ever emitted. An id with no name keeps its numeric
 * form rather than acquiring an invented one — a plausible-looking wrong name is worse than a
 * number, because it reads as knowledge.
 *
 * Names are unique within a type, so every rendering here is reversible: [idOf] recovers the id the
 * emitter started from, which is what lets a renamed script still recompile.
 */
object Cs2Gamevals {

    /** Drop what the previous cache's map squares answered; see [Cs2Records]. */
    fun forget() = MappedSquares.forget()

    /** Gameval table backing each argument type, or null where Jagex publishes no names for it. */
    private val TABLES: Map<ArgType, String> = mapOf(
        ArgType.ITEM to Gameval.OBJ,
        ArgType.NPC to Gameval.NPC,
        ArgType.LOC to Gameval.LOC,
        ArgType.SEQ to Gameval.SEQ,
        ArgType.ENUM to Gameval.ENUM,
        ArgType.STRUCT to Gameval.STRUCT,
        ArgType.PARAM to Gameval.PARAM,
        ArgType.INV to Gameval.INV,
        ArgType.GRAPHIC to Gameval.GRAPHIC,
        ArgType.MODEL to Gameval.MODEL,
        ArgType.QUEST to QUEST,
        ArgType.FONTMETRICS to FONTMETRICS,
        ArgType.INTERFACE to Gameval.INTERFACE,
        ArgType.VARBIT to Gameval.VARBIT_PLAYER,
        ArgType.SOUND to Gameval.SOUND,
        ArgType.CURSOR to CURSOR,
        ArgType.ACHIEVEMENT to ACHIEVEMENT,
        ArgType.MATERIAL to MATERIAL,
        ArgType.BAS to BAS,
        ArgType.MAPELEMENT to MAPELEMENT,
    )

    /** Published gameval tables the cache library carries no constant for. */
    private const val QUEST = "quest"
    private const val FONTMETRICS = "fontmetrics"
    private const val CURSOR = "cursor"
    private const val ACHIEVEMENT = "achievement"
    private const val MATERIAL = "material"
    private const val BAS = "bas"
    private const val MAPELEMENT = "mapelement"

    /**
     * Gameval table naming each variable space.
     *
     * Names are unique per table and, as it happens, across all of these tables too, so a variable
     * can be spelled by its bare dev name without the space having to be repeated in the identifier.
     */
    private val VAR_TABLES: Map<VarSpace, String> = mapOf(
        VarSpace.VARP to Gameval.VAR_PLAYER,
        VarSpace.VARN to Gameval.VAR_NPC,
        VarSpace.VARC to Gameval.VAR_CLIENT,
        VarSpace.VAROBJ to Gameval.VAR_OBJECT,
        VarSpace.VARCLAN to Gameval.VAR_CLAN,
        VarSpace.VARCLANSETTING to Gameval.VAR_CLAN_SETTING,
        VarSpace.VARGROUP to Gameval.VAR_PLAYER_GROUP,
        VarSpace.VARBIT to Gameval.VARBIT_PLAYER,
        VarSpace.VARNBIT to Gameval.VARBIT_NPC,
        VarSpace.CLAN to Gameval.VAR_CLAN,
        VarSpace.CLAN_SETTING to Gameval.VAR_CLAN_SETTING,
    )

    private val VAR_SPACES: List<VarSpace> = VAR_TABLES.keys.toList()

    fun table(type: ArgType): String? = TABLES[type]

    fun nameOf(type: ArgType, id: Int): String? = TABLES[type]?.let { Gameval.name(it, id) }

    fun idOf(type: ArgType, name: String): Int? = TABLES[type]?.let { Gameval.id(it, name) }

    fun varTable(space: VarSpace): String? = VAR_TABLES[space]

    fun varName(space: VarSpace, id: Int): String? = VAR_TABLES[space]?.let { Gameval.name(it, id) }

    /** The space and id a bare variable dev name stands for, or null when no table holds it. */
    fun varOf(name: String): Pair<VarSpace, Int>? {
        for (space in VAR_SPACES) {
            val id = Gameval.id(VAR_TABLES.getValue(space), name) ?: continue
            return space to id
        }
        return null
    }

    /** `meslayer:mes_text` for a packed component hash, when both halves resolve. */
    fun componentName(hash: Int): String? =
        Gameval.component(Cs2Packing.interfaceOf(hash), Cs2Packing.componentOf(hash))

    fun interfaceName(id: Int): String? = Gameval.name(Gameval.INTERFACE, id)

    /**
     * Member-access form for a named value — `obj.coins`. The table name doubles as the declared
     * object in `cs2.d.ts`, so an editor completes and renames these like any other symbol.
     */
    fun member(type: ArgType, id: Int): String? {
        val table = TABLES[type] ?: return null
        val name = Gameval.name(table, id) ?: return null
        return if (identifierSafe(name)) "$table.$name" else "$table[${quote(name)}]"
    }

    fun isTable(name: String): Boolean = name in TABLES.values

    /** The id a `<table>.<name>` or `<interface>.<component>` member access stands for. */
    fun memberId(owner: String, name: String): Int? =
        if (isTable(owner)) Gameval.id(owner, name) else Gameval.componentHash("$owner:$name")

    /**
     * Component form — `component(meslayer.mes_text)` when named, otherwise the numeric pair.
     * The colon in a gameval component name is not valid in an identifier, so the two halves are
     * emitted as a path.
     */
    fun component(hash: Int): String = "component(${componentArgument(hash)})"

    /**
     * What goes inside a `component(...)`, so a caller can put a note in front of it.
     *
     * The colon in a gameval component name is not valid in an identifier, so the two halves are
     * emitted as a path.
     */
    fun componentArgument(hash: Int): String {
        val name = componentName(hash)
        if (name != null) {
            val interfaceName = name.substringBefore(':')
            val componentName = name.substringAfter(':')
            if (identifierSafe(interfaceName) && identifierSafe(componentName)) {
                return "$interfaceName.$componentName"
            }
            return quote(name)
        }
        return "${Cs2Packing.interfaceOf(hash)}, ${Cs2Packing.componentOf(hash)}"
    }

    /**
     * A constant spelled as the component it addresses, or null when nothing vouches for that
     * reading.
     *
     * The evidence is Jagex's own component dictionary: the value's two halves have to name a pair
     * that actually exists. A constant that merely *looks* packed proves nothing and stays a number.
     */
    fun componentOrNull(value: Int): String? = if (componentBasis(value) == null) null else component(value)

    /**
     * What holds the value's component reading up, or null where it has none at all.
     *
     * The dictionary is the same either way; what differs is whether anything competes with it. A
     * value that reads equally well as a position has two candidate meanings and only its shape to
     * pick between them, which is a guess however often the guess has turned out right.
     */
    fun componentBasis(value: Int): Cs2ComponentBasis? = when {
        !Cs2Packing.isPackedComponent(value) || componentName(value) == null -> null
        Cs2Packing.looksLikeCoord(value) && MappedSquares.holds(value) -> Cs2ComponentBasis.SHAPE
        else -> Cs2ComponentBasis.UNAMBIGUOUS
    }

    fun identifierSafe(name: String): Boolean =
        name.isNotEmpty() && name[0].isJavaIdentifierStart() && name.all { it.isJavaIdentifierPart() }

    private fun quote(name: String): String = "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
