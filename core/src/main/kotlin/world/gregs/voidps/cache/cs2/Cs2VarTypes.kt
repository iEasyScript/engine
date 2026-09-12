package world.gregs.voidps.cache.cs2

import world.gregs.voidps.buffer.Cp1252
import world.gregs.voidps.gameval.Gameval
import java.io.File

/** The stack a script variable of a given type lives on. */
enum class Cs2VarBase {
    INT,
    LONG,
    STRING,

    /** A sixteen-byte payload the client keeps off all three operand stacks. */
    WIDE,
}

/**
 * One entry of the client's script-variable type table.
 *
 * The client carries no human name for these - only the legacy type character,
 * which is also what the cache stores in an enum's key/value type, a param's
 * type and a hook's argument spec. That character is therefore the identity, and
 * [label] is only a convenience derived from data of our own.
 */
data class Cs2VarType(
    val id: Int,
    /** CP1252 byte of the legacy type character. */
    val tag: Int,
    val base: Cs2VarBase,
    /** The client's initial value for a variable of this type, verbatim. */
    val default: String,
) {
    val printable: String
        get() = if (tag in 0x20..0x7E) "'${tag.toChar()}'" else "0x%02x".format(tag)
}

/** What the cache's enums say about the ids one type character stands for. */
data class TagEvidence(val samples: Int, val covering: List<String>)

object Cs2VarTypes {

    private var types: List<Cs2VarType> = emptyList()
    private var byTag: Map<Int, Cs2VarType> = emptyMap()
    private var byId: Map<Int, Cs2VarType> = emptyMap()

    val all: List<Cs2VarType> get() = types

    fun file(): File = File("./data/cs2/script-var-types.csv")

    /** Two entries can share a tag with different defaults; the base type is the same either way. */
    fun install(installed: List<Cs2VarType>) {
        types = installed
        byTag = installed.associateBy { it.tag }
        byId = installed.associateBy { it.id }
    }

    fun read(file: File): List<Cs2VarType> = Cs2Csv.read(file).map { row ->
        Cs2VarType(
            id = row.getValue("id").toInt(),
            tag = row.getValue("legacy_char_dec").toInt(),
            base = when (val base = row.getValue("base_type")) {
                "int" -> Cs2VarBase.INT
                "long" -> Cs2VarBase.LONG
                "string" -> Cs2VarBase.STRING
                else -> if (base.startsWith("basetag")) Cs2VarBase.WIDE else error("Unknown base type '$base'")
            },
            default = row.getValue("default_value"),
        )
    }

    fun of(tag: Char): Cs2VarType? = byteOf(tag)?.let { byTag[it] }

    fun ofTag(tag: Int): Cs2VarType? = byTag[tag]

    /**
     * The type a numeric type id names.
     *
     * This is how the cache spells a param's or an enum's type: the legacy type
     * character is a second, older encoding that this revision never writes.
     */
    fun ofId(id: Int): Cs2VarType? = byId[id]

    fun baseOfId(id: Int): Cs2VarBase? = byId[id]?.base

    /** Which stack a value of this type is passed on; ints where the type is unknown. */
    fun baseOf(tag: Char): Cs2VarBase = of(tag)?.base ?: Cs2VarBase.INT

    /**
     * Gameval table each type character indexes, where the cache proves it.
     *
     * An enum declares its key and value types with these same characters, so the
     * ids it holds are a sample of the type's domain. A table that contains every
     * sampled id, and is the only one that does, is that type's table. Anything
     * less decisive is left unlabelled rather than guessed at.
     */
    fun evidence(): Map<Int, TagEvidence> {
        val candidates = GAMEVAL_TABLES.associateWith { Gameval.entries(it).keys }
            .filterValues { it.isNotEmpty() }
        return sample().mapValues { (_, ids) ->
            TagEvidence(ids.size, candidates.filterValues { entries -> ids.all { it in entries } }.keys.sorted())
        }
    }

    fun tables(): Map<Int, String> = evidence()
        .filterValues { it.samples >= MIN_SAMPLES && it.covering.size == 1 }
        .mapValues { it.value.covering.first() }

    /** Ids the cache's enums attribute to each type character. */
    private fun sample(): Map<Int, MutableSet<Int>> {
        val samples = HashMap<Int, MutableSet<Int>>()
        for (enum in Cs2Records.enums()) {
            val map = enum.map ?: continue
            val keyTag = tagOf(enum.keyTypeId, enum.keyType)
            val valueTag = tagOf(enum.valueTypeId, enum.valueType)
            for ((key, value) in map) {
                if (keyTag != null && key > 0) samples.getOrPut(keyTag) { HashSet() }.add(key)
                if (valueTag != null && value is Int && value > 0) {
                    samples.getOrPut(valueTag) { HashSet() }.add(value)
                }
            }
        }
        return samples
    }

    /** The numeric type id where the cache carries one, the legacy character otherwise. */
    private fun tagOf(typeId: Int, legacy: Char): Int? = byId[typeId]?.tag ?: byteOf(legacy)

    private fun byteOf(tag: Char): Int? =
        Cp1252.encode(tag.toString()).firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it != 0 }

    private const val MIN_SAMPLES = 8

    private val GAMEVAL_TABLES = listOf(
        Gameval.OBJ, Gameval.NPC, Gameval.LOC, Gameval.SEQ, Gameval.GRAPHIC, Gameval.STRUCT,
        Gameval.ENUM, Gameval.PARAM, Gameval.INV, Gameval.INTERFACE, Gameval.CATEGORY,
        Gameval.SOUND, Gameval.MIDI, Gameval.MODEL, Gameval.DBROW, Gameval.DBTABLE,
        Gameval.VAR_PLAYER, Gameval.VARBIT_PLAYER, Gameval.VAR_CLIENT,
    )
}
