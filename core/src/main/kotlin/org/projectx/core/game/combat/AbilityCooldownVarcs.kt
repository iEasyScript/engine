package org.projectx.core.game.combat

import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.ConcurrentHashMap

/**
 * The client var holding the cycle each ability's cooldown ends on. The game names these
 * `combatv2_cooldown_<skill>_<ability>_end_client`, so abilities missing from the table are matched to one by name;
 * the table holds the ones whose var is named differently (Dive shares Bladed Dive's) and pins the rest.
 */
object AbilityCooldownVarcs {
    private val endVarcByStruct: Map<Int, Int> = mapOf(
        1488 to 6038,
        47129 to 6038,
        14726 to 2194,
        14665 to 2172,
        14663 to 2168,
        14664 to 2170,
        14666 to 2174,
        14668 to 2178,
        14669 to 2180,
        14670 to 2182,
        14671 to 2184,
        14678 to 2096,
        14679 to 2098,
        14682 to 2104,
        14684 to 2108,
        14685 to 2110,
        14686 to 2112,
        14688 to 2116,
        14690 to 2166,
        14700 to 2122,
        14701 to 2124,
        14704 to 2130,
        14707 to 2136,
        14712 to 2146,
        14715 to 2152,
        14725 to 2192,
        14727 to 2196,
        14728 to 2198,
        14729 to 2200,
        14730 to 2202,
        14731 to 2204,
        14733 to 2208,
        14735 to 2212,
        14736 to 2214,
        19342 to 2728,
        19343 to 2730,
        28177 to 4180,
        28180 to 4182,
        28429 to 4236,
        28927 to 6992,
        31649 to 7773,
        31820 to 7792,
        31982 to 7775,
        31983 to 7777,
        31984 to 7779,
        31985 to 4981,
        31986 to 4979,
        37199 to 6598,
        37200 to 6600,
        37201 to 6602,
        37202 to 6604,
        37203 to 6606,
        37204 to 6608,
        37205 to 6610,
        37206 to 6594,
        37207 to 6596,
        44244 to 8381,
        45800 to 6992,
        48296 to 7226,
        48297 to 7243,
        48298 to 7245,
        48299 to 7248,
        48301 to 7250,
        48302 to 7228,
        48303 to 7230,
        48304 to 7233,
        48306 to 7238,
        48308 to 7252,
        48309 to 7254,
        48311 to 7256,
        48312 to 7258,
        48313 to 7260,
        48314 to 7262,
        48324 to 7264,
        48326 to 7269,
        48327 to 7271,
        48328 to 7267,
        48329 to 7273,
        48330 to 7276,
        48331 to 7279,
        48332 to 7282,
        49072 to 7348,
        52781 to 8379,
        52788 to 8383,
        52789 to 8385,
        52799 to 8391,
    )

    /** The client var [structId]'s cooldown end cycle is kept in, or -1 when the game keeps none for it. */
    fun endVarc(structId: Int): Int = endVarcByStruct[structId] ?: endVarcNamed(structId)

    private val namedEndVarcs = ConcurrentHashMap<Int, Int>()

    private val endVarcByAbility: Map<String, Int> by lazy {
        val found = HashMap<String, MutableList<Int>>()
        for ((varc, name) in Gameval.entries(Gameval.VAR_CLIENT)) {
            val ability = COOLDOWN_END.matchEntire(name)?.groupValues?.get(1) ?: continue
            found.getOrPut(ability) { mutableListOf() } += varc
        }
        found.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }

    private fun endVarcNamed(structId: Int): Int {
        namedEndVarcs[structId]?.let { return it }
        val name = AbilityType(structId).name
        if (name.isBlank()) return -1
        val varc = slugs(name).firstNotNullOfOrNull { endVarcByAbility[it] } ?: -1
        namedEndVarcs[structId] = varc
        return varc
    }

    private fun slugs(name: String): List<String> {
        val plain = name.replace("<nbsp>", " ").lowercase()
        return listOf(plain.replace("'", ""), plain.replace("'s", ""))
            .map { it.replace(NON_WORD, "_").trim('_') }
            .distinct()
    }

    private val COOLDOWN_END = Regex("combatv2_cooldown_[a-z]+_(.+)_end_client")
    private val NON_WORD = Regex("[^a-z0-9]+")

    val structByEndVarc: Map<Int, Int> by lazy {
        endVarcByStruct.entries.associate { (struct, varc) -> varc to struct }
    }
}
