package com.projectx.webwalker

import com.projectx.script.api.coinPouch
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.varps
import org.projectx.core.game.skill.Skill

/**
 * Which links the account may use, decided once before a search starts.
 *
 * Requirements read live game state - varbits, skill levels, the money pouch - and that may only be touched on
 * the game thread, while planning runs on a background thread. So the answer for every requirement a link carries
 * is worked out up front and the search consults this snapshot instead.
 *
 * A requirement this engine does not understand counts as met. The alternative, treating it as unmet, would hide
 * whole regions behind one unrecognised flag; being optimistic costs at most a re-plan when the walker reaches
 * the object and cannot use it.
 */
class WebLinkPermissions private constructor(private val blocked: Set<WebRequirement>) {

    fun allows(link: WebLink): Boolean = link.requirements.none { it in blocked }

    companion object {
        /** Nothing is blocked; for tests and for planning when game state is not available. */
        val UNRESTRICTED = WebLinkPermissions(emptySet())

        private val SKILLS = mapOf(
            "agilityLevel" to Skill.AGILITY,
            "archeologyLevel" to Skill.ARCHAEOLOGY,
            "archaeologyLevel" to Skill.ARCHAEOLOGY,
            "dungLevel" to Skill.DUNGEONEERING,
            "miningLevel" to Skill.MINING,
            "thievingLevel" to Skill.THIEVING,
            "constructionLevel" to Skill.CONSTRUCTION,
            "farmingLevel" to Skill.FARMING,
            "slayerLevel" to Skill.SLAYER,
            "summoningLevel" to Skill.SUMMONING,
            "woodcuttingLevel" to Skill.WOODCUTTING,
            "firemakingLevel" to Skill.FIREMAKING,
            "rangedLevel" to Skill.RANGED,
            "magicLevel" to Skill.MAGIC,
            "strengthLevel" to Skill.STRENGTH,
        )

        /**
         * Evaluates every requirement the link set carries. Call on the game thread; the result is then safe to
         * hand to the planner.
         */
        fun snapshot(): WebLinkPermissions {
            val blocked = HashSet<WebRequirement>()
            for (requirement in WebLinks.all.flatMap(WebLink::requirements).distinct()) {
                val have = valueOf(requirement.key) ?: continue
                val want = requirement.value.trim().toIntOrNull() ?: continue
                val met = when (requirement.comparison.trim()) {
                    ">=" -> have >= want
                    ">" -> have > want
                    "<=" -> have <= want
                    "<" -> have < want
                    "!=" -> have != want
                    else -> have == want
                }
                if (!met) blocked += requirement
            }
            return WebLinkPermissions(blocked)
        }

        /** The account's current value for a requirement key, or null when this engine cannot evaluate it. */
        private fun valueOf(key: String): Int? = runCatching {
            when {
                key.startsWith("varbit_") -> key.removePrefix("varbit_").toIntOrNull()?.let(varps::getVarBit)
                key.startsWith("varp_") -> key.removePrefix("varp_").toIntOrNull()?.let(varps::getVar)
                key == "coins" -> coinPouch.firstOrNull()?.amount ?: 0
                else -> SKILLS[key]?.let(::getRealLevel)
            }
        }.getOrNull()
    }
}
