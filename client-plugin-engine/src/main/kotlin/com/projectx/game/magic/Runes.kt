package com.projectx.game.magic

import com.projectx.game.items.Item
import com.projectx.util.FeatureIds
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

/** A rune a spell can cost, keyed by the word the cache's `combatv2_requires_<key>_runes` params use. */
internal enum class Rune(val key: String, val objName: String, val supplied: Boolean = true) {
    AIR("air", "airrune"),
    WATER("water", "waterrune"),
    EARTH("earth", "earthrune"),
    FIRE("fire", "firerune"),
    MIND("mind", "mindrune"),
    BODY("body", "bodyrune"),
    CHAOS("chaos", "chaosrune"),
    DEATH("death", "deathrune"),
    BLOOD("blood", "bloodrune"),
    SOUL("soul", "soulrune"),
    ASTRAL("astral", "astralrune"),
    NATURE("nature", "naturerune"),
    COSMIC("cosmic", "cosmicrune"),
    LAW("law", "lawrune"),
    ARMADYL("armadyl", "mah5_armadyl_rune", supplied = false),
    TIME("time", "timerune", supplied = false),
    ;

    val requiresParam: String get() = "combatv2_requires_${key}_runes"
    val suppliesParam: String? get() = if (supplied) "magic_supplies_${key}_runes" else null

    override fun toString(): String = key.replaceFirstChar(Char::uppercaseChar) + " rune"

    companion object {
        /** One combination rune counts as one of each of its elements in the same cast. */
        val COMBINATIONS: Map<String, Set<Rune>> = mapOf(
            "steamrune" to setOf(WATER, FIRE),
            "mistrune" to setOf(AIR, WATER),
            "dustrune" to setOf(AIR, EARTH),
            "smokerune" to setOf(AIR, FIRE),
            "mudrune" to setOf(WATER, EARTH),
            "lavarune" to setOf(EARTH, FIRE),
        )
    }
}

/** The runes the player can spend: counted ones from the backpack and rune pouches, and those a worn item supplies without end. */
internal class RuneSupply(private val counts: Map<Rune, Long>, private val unlimited: Set<Rune>) {

    fun available(rune: Rune): Long = if (rune in unlimited) Long.MAX_VALUE else counts[rune] ?: 0L

    /** The first rune [required] needs more of than there is, or null when every one is covered. */
    fun missing(required: Map<Rune, Int>): Rune? = required.entries.firstOrNull { (rune, need) -> available(rune) < need }?.key

    override fun toString(): String = "RuneSupply(counts=$counts, unlimited=$unlimited)"

    companion object {
        val NONE = RuneSupply(emptyMap(), emptySet())

        /** Each stack counts towards every rune it stands for, so a lava rune is one fire and one earth. */
        fun of(stacks: List<Pair<Set<Rune>, Long>>, unlimited: Set<Rune>): RuneSupply {
            val counts = HashMap<Rune, Long>()
            for ((runes, amount) in stacks) {
                if (amount <= 0) continue
                for (rune in runes) counts.merge(rune, amount, Long::plus)
            }
            return RuneSupply(counts, unlimited)
        }
    }
}

/**
 * Reads the runes carried: backpack stacks, the runes inside a rune pouch (its obj vars, decoded through the
 * pouch's own rune table), and the elements a worn staff or tome supplies, all named by gameval.
 */
internal object CarriedRunes {
    private const val POUCH_SLOTS = 4

    private class Ids(
        val runeByObj: Map<Int, Set<Rune>>,
        val requiresParams: Map<Rune, Int>,
        val suppliesParams: Map<Int, Rune>,
        val pouchSizeParam: Int,
        val pouchRuneEnum: Int,
    )

    private val ids = FeatureIds("CarriedRunes") {
        val byObj = HashMap<Int, Set<Rune>>()
        for (rune in Rune.entries) byObj[Gameval.requireId(Gameval.OBJ, rune.objName)] = setOf(rune)
        for ((name, runes) in Rune.COMBINATIONS) byObj[Gameval.requireId(Gameval.OBJ, name)] = runes
        Ids(
            runeByObj = byObj,
            requiresParams = Rune.entries.associateWith { Gameval.requireId(Gameval.PARAM, it.requiresParam) },
            suppliesParams = Rune.entries.mapNotNull { rune -> rune.suppliesParam?.let { Gameval.requireId(Gameval.PARAM, it) to rune } }.toMap(),
            pouchSizeParam = Gameval.requireId(Gameval.PARAM, "pvpwild_runepouch_size"),
            pouchRuneEnum = Gameval.requireId(Gameval.ENUM, "pvpwild_int_to_rune"),
        )
    }

    /** The runes a spell struct's params ask for; null when the rune params cannot be named, so no cost is unknown. */
    fun required(params: (Int) -> Int): Map<Rune, Int>? {
        val known = ids.get() ?: return null
        return known.requiresParams.mapNotNull { (rune, param) -> params(param).takeIf { it > 0 }?.let { rune to it } }.toMap()
    }

    /** What [backpack] and [worn] give to spend; nothing at all when the names behind it are missing. */
    fun supply(backpack: List<Item>, worn: List<Item>): RuneSupply {
        val known = ids.get() ?: return RuneSupply.NONE
        val stacks = ArrayList<Pair<Set<Rune>, Long>>()
        for (item in backpack + worn) {
            known.runeByObj[item.id]?.let { stacks += it to item.amount.toLong() }
            if (isPouch(known, item.id)) stacks += pouchContents(known, item)
        }
        val unlimited = HashSet<Rune>()
        for (item in worn) {
            val params = Cache.obj(item.id)?.params ?: continue
            for ((param, rune) in known.suppliesParams) if ((params[param] as? Int ?: 0) > 0) unlimited += rune
        }
        return RuneSupply.of(stacks, unlimited)
    }

    private fun isPouch(known: Ids, objId: Int): Boolean = (Cache.obj(objId)?.params?.get(known.pouchSizeParam) as? Int ?: 0) > 0

    private fun pouchContents(known: Ids, pouch: Item): List<Pair<Set<Rune>, Long>> {
        val vars = pouch.varDomain ?: return emptyList()
        val table = Cache.enum(known.pouchRuneEnum) ?: return emptyList()
        return (1..POUCH_SLOTS).mapNotNull { slot ->
            val type = Cache.objectVarbit("pvpwild_runetype$slot") ?: return@mapNotNull null
            val amount = Cache.objectVarbit("pvpwild_rune_amount$slot") ?: return@mapNotNull null
            val runeObj = table.values[type.getValue(vars.getVar(type.baseVar))] as? Int ?: return@mapNotNull null
            val runes = known.runeByObj[runeObj] ?: return@mapNotNull null
            runes to amount.getValue(vars.getVar(amount.baseVar)).toLong()
        }
    }
}
