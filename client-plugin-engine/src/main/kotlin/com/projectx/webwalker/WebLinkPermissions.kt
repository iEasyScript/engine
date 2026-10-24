package com.projectx.webwalker

import com.projectx.game.items.Item
import com.projectx.script.api.coinPouch
import com.projectx.script.api.equipment
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.gameval.Gameval
import java.util.concurrent.ConcurrentHashMap

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
 *
 * A link that uses an item - a teleport item, the Passage of the Abyss, an item used on an object - is also only
 * allowed while the item is carried: in the backpack, or worn for a teleport.
 */
class WebLinkPermissions private constructor(
    private val blocked: Set<WebRequirement>,
    private val missingItems: Set<WebLink> = emptySet(),
) {

    fun allows(link: WebLink): Boolean = link.requirements.none { it in blocked } && link !in missingItems

    companion object {
        /** Nothing is blocked; for tests and for planning when game state is not available. */
        val UNRESTRICTED = WebLinkPermissions(emptySet())

        private val ITEM_KINDS = setOf(WebLinkKind.ITEM, WebLinkKind.POA, WebLinkKind.USE_ON)

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
            "rangeLevel" to Skill.RANGED,
            "magicLevel" to Skill.MAGIC,
            "fishingLevel" to Skill.FISHING,
            "strengthLevel" to Skill.STRENGTH,
        )

        /**
         * Evaluates every requirement the link set carries. Call on the game thread; the result is then safe to
         * hand to the planner.
         */
        fun snapshot(): WebLinkPermissions {
            val blocked = HashSet<WebRequirement>()
            val carriedBackpack = runCatching { inventory.toList() }.getOrDefault(emptyList())
            val carriedWorn = runCatching { equipment.toList() }.getOrDefault(emptyList())
            for (requirement in WebLinks.all.flatMap(WebLink::requirements).distinct()) {
                val have = valueOf(requirement.key, carriedBackpack, carriedWorn) ?: continue
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
            val backpack = runCatching { inventory.toList() }.getOrDefault(emptyList())
            val worn = runCatching { equipment.toList() }.getOrDefault(emptyList())
            val portableRing = portableRingUsable(backpack, worn)
            val missing = WebLinks.all.filterTo(HashSet()) { link ->
                val target = link.target
                link.portable && !portableRing || link.kind in ITEM_KINDS && target != null &&
                    !carries(backpack, target) && (link.kind == WebLinkKind.USE_ON || !carries(worn, target))
            }
            return WebLinkPermissions(blocked, missing)
        }

        /** An active portable fairy ring carried or worn, or an inactive one in the backpack to activate first. */
        internal fun portableRingUsable(backpack: List<Item>, worn: List<Item>): Boolean {
            val ids = portableRingIds.get() ?: return false
            if (backpack.any { it.id == ids.first } || worn.any { it.id == ids.first }) return true
            return !activeRingOutOfReach && backpack.any { it.id == ids.second }
        }

        /** An Activate was refused with no active ring carried: the active one is banked or elsewhere. */
        @Volatile
        internal var activeRingOutOfReach = false

        /** The active and the inactive portable fairy ring. */
        internal val portableRingIds = FeatureIds("WebLinkPermissions") {
            Gameval.requireId(Gameval.OBJ, "invent_fairy_ring") to Gameval.requireId(Gameval.OBJ, "invent_fairy_ring_inactive")
        }

        private enum class Where { BACKPACK, WORN, ANYWHERE }

        private class Carried(val where: Where, vararg val names: String)

        // Opaque navpath flags the link set carries, as the upstream requirement profile evaluates them.
        private val CARRIED = mapOf(
            "hasDustyKey" to Carried(Where.BACKPACK, "dusty_key"),
            "hasLOTD" to Carried(Where.WORN, "lucky_ring_dwarves"),
            "hasArchJournal" to Carried(Where.ANYWHERE, "arch_journal"),
            "hasWS4inInv" to Carried(Where.BACKPACK, "atwild_reward_elite", "atwild_reward_elite_augmented", "atwild_reward_elite_augmented_uncharged"),
            "hasWS4Equiped" to Carried(Where.WORN, "atwild_reward_elite", "atwild_reward_elite_augmented", "atwild_reward_elite_augmented_uncharged"),
            "hasWS1inInv" to Carried(Where.BACKPACK, "atwild_reward_easy"),
            "hasWS1Equiped" to Carried(Where.WORN, "atwild_reward_easy"),
            "hasWickedHood" to Carried(Where.ANYWHERE, "hooks3_hood"),
            "hasEctophial" to Carried(Where.BACKPACK, "ectophial"),
            "hasKG4" to Carried(Where.ANYWHERE, "atjun_gloves_elite"),
            "hasDA3" to Carried(Where.BACKPACK, "atdes_amulet_hard"),
            "hasDA4" to Carried(Where.BACKPACK, "atdes_amulet_elite"),
            "hasROK" to Carried(Where.ANYWHERE, "rand_mission_contract"),
            "hasROF" to Carried(Where.ANYWHERE, "lucky_onyx_ring"),
            "hasDorgeshSphere" to Carried(Where.BACKPACK, "dorgesh_teleport_artifact"),
            "hasFremBoots4" to Carried(Where.BACKPACK, "atfrem_boots_elite"),
            "hasCOL" to Carried(Where.BACKPACK, "cape_of_legends"),
            "hasDragonTeleport" to Carried(Where.BACKPACK, "dungeon_dragonfort_teletab"),
            "hasAttunedSeed" to Carried(Where.ANYWHERE, "mourning_teleport_crystal_perm"),
            "hasSkullSceptre" to Carried(Where.BACKPACK, "sos_skull_sceptre"),
            "hasDM" to Carried(Where.ANYWHERE, "myq5_drakan_pendant"),
            "hasQuiver" to Carried(Where.ANYWHERE, "atelf_quiver_elite"),
            "hasMaxCape" to Carried(Where.ANYWHERE, "skillcape_max", "skillcape_max_hooded", "skillcape_120_all", "skillcape_120_all_hooded", "poh_dummy_skillcape_max"),
            "hasSlayerCape" to Carried(
                Where.ANYWHERE, "skillcape_slayer_trimmed", "skillcape_120_slayer", "skillcape_slayer_hooded", "skillcape_slayer_trimmed_hooded",
                "skillcape_inverted_120_slayer", "skillcape_inverted_slayer_hooded", "skillcape_inverted_slayer_trimmed_hooded",
            ),
            "hasDungCape" to Carried(
                Where.ANYWHERE, "skillcape_dungeoneering", "skillcape_dungeoneering_trimmed", "skillcape_120_dungeoneering",
                "skillcape_dungeoneering_hooded", "skillcape_dungeoneering_trimmed_hooded", "skillcape_inverted_120_dungeoneering",
                "skillcape_inverted_dungeoneering_hooded", "skillcape_inverted_dungeoneering_trimmed_hooded",
            ),
        )

        /** Flags counted by item name in the backpack: an amount, or a name that only starts the same. */
        private val BY_NAME = mapOf(
            "hasFFT" to "Fort Forinthry Teleport",
            "hasRope" to "Rope",
            "hasWaterFallKey" to "A key",
        )

        private val BY_NAME_PREFIX = mapOf(
            "hasGamesNeck" to "games necklace (",
            "hasCombatBracelet" to "combat bracelet (",
            "hasRingOfDuelling" to "ring of duelling (",
            "hasSkillsNecklace" to "skills necklace (",
        )

        private val carriedIds = ConcurrentHashMap<String, Set<Int>>()

        private fun carriedValue(key: String, backpack: List<Item>, worn: List<Item>): Int? {
            val carried = CARRIED[key] ?: return null
            val ids = carriedIds.getOrPut(key) {
                carried.names.mapNotNull { runCatching { Gameval.requireId(Gameval.OBJ, it) }.getOrNull() }.toSet()
            }
            if (ids.isEmpty()) return null
            val inBackpack = backpack.any { it.id in ids }
            val wearing = worn.any { it.id in ids }
            val has = when (carried.where) {
                Where.BACKPACK -> inBackpack
                Where.WORN -> wearing
                Where.ANYWHERE -> inBackpack || wearing
            }
            return if (has) 1 else 0
        }

        private fun namedValue(key: String, backpack: List<Item>): Int? {
            BY_NAME[key]?.let { name -> return backpack.filter { runCatching { it.name }.getOrNull() == name }.sumOf { it.amount } }
            BY_NAME_PREFIX[key]?.let { prefix ->
                return if (backpack.any { runCatching { it.name }.getOrNull()?.lowercase()?.startsWith(prefix) == true }) 1 else 0
            }
            return null
        }

        private fun carries(items: List<Item>, target: WebTarget): Boolean =
            items.any { item -> target.matches(item.id, runCatching { item.name }.getOrNull()) }

        /** The account's current value for a requirement key, or null when this engine cannot evaluate it. */
        private fun valueOf(key: String, backpack: List<Item>, worn: List<Item>): Int? = runCatching {
            when {
                key.startsWith("varbit_") -> key.removePrefix("varbit_").toIntOrNull()?.let(varps::getVarBit)
                key.startsWith("varp_") -> key.removePrefix("varp_").toIntOrNull()?.let(varps::getVar)
                key == "coins" -> coinPouch.firstOrNull()?.amount ?: 0
                key in SKILLS -> SKILLS[key]?.let(::getRealLevel)
                else -> carriedValue(key, backpack, worn) ?: namedValue(key, backpack)
            }
        }.getOrNull()
    }
}
