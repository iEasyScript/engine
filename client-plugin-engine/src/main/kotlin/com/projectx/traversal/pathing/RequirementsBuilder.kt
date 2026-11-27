package com.projectx.traversal.pathing

import com.projectx.game.bootstrap.GameThread
import com.projectx.game.cs2.CS2Executor
import com.projectx.game.items.Item
import com.projectx.game.magic.SpellTeleports
import com.projectx.quest.runtime.CacheQuests
import com.projectx.script.api.AttackMemory
import com.projectx.script.api.TeleportSpell
import com.projectx.script.api.abilityCooldownTicks
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.coinPouch
import com.projectx.script.api.diveAbility
import com.projectx.script.api.equipment
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.traversal.MasterFarmerHat
import com.projectx.traversal.nodes.DramenStaff
import com.projectx.traversal.nodes.FairyRingNode
import com.projectx.traversal.nodes.PouchItems
import com.projectx.traversal.nodes.SkillcapePerks
import com.projectx.traversal.nodes.SpiritTreeRerooterNode
import com.projectx.webwalker.AbyssPassage
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

/**
 * The account as navpath's `profile.requirements` needs it: every key its rows are gated on, valued from live game
 * state. Read on the game thread; a key left out counts as unmet there.
 *
 * The base and spell keys always go; item keys with `useItems`. What a hit breaks (a lodestone cast, a teleport that
 * opens a menu) goes only while nothing has attacked the player recently.
 */
object RequirementsBuilder {

    enum class Block { BASE, SPELL, ITEM, LODESTONE, IF_SLOT, MENU_ITEM, POA }

    /** What the player carries, read once for a whole profile. */
    internal class Carried(val backpack: List<Item>, val worn: List<Item>) {
        fun inBackpack(vararg names: String): Boolean = names.any { name -> objId(name)?.let { id -> backpack.any { it.id == id } } == true }

        fun wearing(vararg names: String): Boolean = names.any { name -> objId(name)?.let { id -> worn.any { it.id == id } } == true }

        fun anywhere(vararg names: String): Boolean = inBackpack(*names) || wearing(*names)

        fun count(name: String): Int = objId(name)?.let { id -> backpack.filter { it.id == id }.sumOf { it.amount } } ?: 0

        /** The stack of the first backpack item named exactly [name]. */
        fun amountNamed(name: String): Int = backpack.firstOrNull { runCatching { it.name }.getOrNull() == name }?.amount ?: 0

        fun nameContains(needle: String): Boolean = backpack.any { runCatching { it.name }.getOrNull()?.lowercase()?.contains(needle) == true }

        fun backpackItem(name: String): Item? = objId(name)?.let { id -> backpack.firstOrNull { it.id == id } }

        fun wornItem(name: String): Item? = objId(name)?.let { id -> worn.firstOrNull { it.id == id } }

        companion object {
            fun now() = Carried(
                runCatching { inventory.toList() }.getOrDefault(emptyList()),
                runCatching { equipment.toList() }.getOrDefault(emptyList()),
            )
        }
    }

    internal class Req(val key: String, val value: (Carried) -> Int)

    /** Kept out of a walk-only profile: they open a teleport network from anywhere rather than a way on foot. */
    val WALK_ONLY_DROPPED = setOf("hasQuickTele", FAIRY_RING_STAFF_ACCESS)

    const val FAIRY_RING_STAFF_ACCESS = "fairyRingStaffAccess"
    const val SPIRIT_TREE_ACCESS = "spiritTreeAccess"
    const val SPELL_KEY_PREFIX = "spell_"

    private const val QUEST = "quest"
    private const val TICK_MS = 600L
    private const val SURGE_AGILITY = 5
    private const val DIVE_AGILITY = 30

    /** navpath's rows were measured against a fixed combat level. */
    private const val COMBAT_LEVEL = 60

    // The game works the Temple Trekking level out in a client script that takes no arguments and has no gameval name.
    private const val TEMPLE_TREKKING_SCRIPT = 6034

    // The Wicked hood's per-altar charge pools, Air to Blood in button order. These varbits have no gameval names.
    private val WICKED_HOOD_ALTAR_POOLS = listOf(
        "Air" to 14834, "Mind" to 14835, "Water" to 14836, "Earth" to 14837, "Fire" to 14838, "Body" to 14839, "Cosmic" to 14840,
        "Chaos" to 14841, "Astral" to 14842, "Nature" to 14843, "Law" to 14844, "Death" to 14845, "Blood" to 14846,
    )

    private val HOUSE_TABLETS = listOf(
        "Rimmington" to "poh_tablet_rimmingtonteleport",
        "Taverley" to "poh_tablet_taverleyteleport",
        "Pollnivneach" to "poh_tablet_pollnivneachteleport",
        "Rellekka" to "poh_tablet_rellekkateleport",
        "Brimhaven" to "poh_tablet_brimhaventeleport",
        "Yanille" to "poh_tablet_yanilleteleport",
        "Prifddinas" to "poh_tablet_elfcityteleport",
        "Otot" to "poh_tablet_mazcabteleport",
        "Menaphos" to "poh_tablet_menaphosteleport",
        "Anachronia" to "poh_tablet_dinoislandteleport",
    )

    // A tablet's "Modify" asks how many to turn into another; with only one held that question never comes.
    private const val TABLETS_TO_MODIFY = 2

    private const val SENTINEL_TELEPORTS_A_DAY = 20
    private val SENTINEL_OUTFIT = arrayOf(
        "trh113_elite_woodcutting_v4_head", "trh113_elite_woodcutting_v4_body", "trh113_elite_woodcutting_v4_legs",
        "trh113_elite_woodcutting_v4_hands", "trh113_elite_woodcutting_v4_feet",
    )

    private val WILDERNESS_SWORD_4 = arrayOf("atwild_reward_elite", "atwild_reward_elite_augmented", "atwild_reward_elite_augmented_uncharged")

    private const val METAL_KEY_ON_RING = 1 shl 2
    private const val CYCLOSIS_VOYAGE = 1

    private val SKILLS = mapOf(
        "miningLevel" to Skill.MINING,
        "agilityLevel" to Skill.AGILITY,
        "magicLevel" to Skill.MAGIC,
        "archeologyLevel" to Skill.ARCHAEOLOGY,
        "dungLevel" to Skill.DUNGEONEERING,
        "rangeLevel" to Skill.RANGED,
        "fishingLevel" to Skill.FISHING,
        "craftingLevel" to Skill.CRAFTING,
    )

    /** Generated at build time from upstream's map. */
    private const val VAR_KEYS_RESOURCE = "/navpath/requirement-var-keys.txt"

    private val upstreamVarKeys: List<String> by lazy {
        RequirementsBuilder::class.java.getResourceAsStream(VAR_KEYS_RESOURCE)?.bufferedReader()?.use { it.readLines() }
            ?.map(String::trim)?.filter(String::isNotEmpty)
            ?: emptyList<String>().also { println("[RequirementsBuilder] $VAR_KEYS_RESOURCE is missing; var-gated rows will be treated as unmet") }
    }

    private val POA_KEY = Regex("(?i)(Red|Purple|Green|Yellow)POA_\\w+")

    fun requirements(
        useItems: Boolean = true,
        assumeNotAttacked: Boolean = false,
        assumeAttacked: Boolean = false,
        withoutSpells: Set<TeleportSpell> = emptySet(),
    ): List<Pair<String, Int>> {
        val carried = Carried.now()
        val out = LinkedHashMap<String, Int>()
        collect(out, base(), carried)
        collect(out, spells(withoutSpells), carried)
        if (useItems) collect(out, items(), carried)
        if (AttackMemory.hitBreakablesUsable(assumeNotAttacked, assumeAttacked)) {
            collect(out, lodestones(), carried)
            collect(out, ifSlots(), carried)
            if (useItems) {
                collect(out, menuItems(), carried)
                out += passages(carried)
            }
        }
        return out.toList()
    }

    /**
     * The whole `/route` request for this account from [start] to [goal] under [options]: its profile, Surge and Dive
     * as the action bar and Agility allow, and its stable seed. [withoutSpells] go as uncastable.
     */
    fun request(
        start: Tile,
        goal: Tile,
        options: NavOptions,
        withoutSpells: Set<TeleportSpell> = emptySet(),
        exclude: Set<NavMacro> = emptySet(),
    ): NavRequest {
        val planned = options.copy().also(::applyAbilityToggles)
        val requirements = if (planned.teleports) requirements(planned.useItems, withoutSpells = withoutSpells) else walkOnlyRequirements()
        return NavRequest(
            start = start,
            goal = goal,
            requirements = requirements,
            surge = planned.surge,
            dive = planned.dive,
            seed = planned.seed.takeIf { it > 0 } ?: NavPathClient.generateSeed(),
            exclude = exclude,
            onlyActions = planned.jsonActionsOnly,
            returnGeometry = planned.wantsHopWalks,
        )
    }

    /** Surge and Dive on when slotted on an action bar and Agility allows them; off altogether without [NavOptions.hops]. */
    fun applyAbilityToggles(options: NavOptions) {
        if (!options.hops) {
            options.surge = SurgeOpt.DISABLED
            options.dive = DiveOpt.DISABLED
            return
        }
        val agility = runCatching { getRealLevel(Skill.AGILITY) }.getOrDefault(0)
        val surge = options.surge.enabled || runCatching { actionbarAbilities[Ability.SURGE.type] != null }.getOrDefault(false) && agility >= SURGE_AGILITY
        val dive = options.dive.enabled || runCatching { diveAbility != null }.getOrDefault(false) && agility >= DIVE_AGILITY
        val charges = if (varbitNamed("dino_agility_double_surge_unlocked") == 1) 2 else options.surge.charges
        val diveReadyInMs = diveAbility?.let { runCatching { abilityCooldownTicks(it, true) }.getOrDefault(0.0) * TICK_MS }?.toLong() ?: 0L
        options.surge = SurgeOpt(surge, charges, options.surge.cooldownMs)
        options.dive = DiveOpt(dive, options.dive.cooldownMs, diveReadyInMs)
    }

    /** A profile for walking only: the quest, skill and area keys doors and shortcuts need, and no teleport at all. */
    fun walkOnlyRequirements(): List<Pair<String, Int>> {
        val out = LinkedHashMap<String, Int>()
        collect(out, base(), Carried.now())
        out.keys.removeAll(WALK_ONLY_DROPPED)
        return out.toList()
    }

    /** The block [key] is collected in, or null when this builder never sends it. Keys match ignoring case, as navpath's do. */
    fun blockOf(key: String?): Block? {
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (POA_KEY.matches(k)) return Block.POA
        val blocks = listOf(
            Block.BASE to base(), Block.ITEM to items(), Block.MENU_ITEM to menuItems(), Block.LODESTONE to lodestones(),
            Block.IF_SLOT to ifSlots(), Block.SPELL to spells(emptySet()),
        )
        return blocks.firstOrNull { (_, reqs) -> reqs.any { it.key.equals(k, ignoreCase = true) } }?.first
    }

    /** True for a key left out while under attack: a lodestone, cape, menu item or Passage of the Abyss key. */
    fun skippedUnderAttack(key: String?): Boolean =
        blockOf(key).let { it == Block.LODESTONE || it == Block.IF_SLOT || it == Block.MENU_ITEM || it == Block.POA }

    fun spellKey(spell: TeleportSpell): String = SPELL_KEY_PREFIX + spell.abilityId

    /** The game's in-combat flag, which outlasts a fight by a few seconds. */
    fun inCombat(): Boolean = varbitNamed("combatv2_player_is_in_combat") > 0

    private fun collect(out: MutableMap<String, Int>, reqs: List<Req>, carried: Carried) {
        for (req in reqs) out[req.key] = runCatching { req.value(carried) }.getOrDefault(0)
    }

    private fun base(): List<Req> = buildList {
        add(Req("hasQuickTele") { flag(varbitNamed("quick_tele_charges") > 0 && varbitNamed("quick_tele_by_default") > 0) })
        add(Req("coins") { coinPouch.firstOrNull()?.amount ?: 0 })
        varbit("quest_rebirth_sanctum_main")
        skill("miningLevel")
        varbit("lunar_quest_main")
        varbit("deserttreasure")
        varbit("death_quest")
        // navpath's key names the quest's varp, but its rows were written against the varbit of the same id.
        varpId("zombiequeen")?.let { id -> add(Req("varp_$id") { varps.getVarBit(id) }) }
        skill("agilityLevel")
        varbit("lumbcat_quest")
        skill("magicLevel")
        skill("archeologyLevel")
        varbit("arch_orthen_mq_portals")
        varbit("arch_mystery_orthen_moksha_memories_completed")
        add(Req("combatLevel") { COMBAT_LEVEL })
        skill("dungLevel")
        varbit("ft3_quest")
        varp("zanaris")
        add(Req(FAIRY_RING_STAFF_ACCESS) { flag(DramenStaff.fairyRingStaffAccess(false)) })
        varbit("gwd2_miniquest_main")
        add(Req("xmasEvent") { 1 })
        varbit("motwl_main")
        varbit("giantdwarf_veldaban_introduced")
        varp("biohazard")
        varp("heroquest")
        varp("onesmallfavour")
        varbit("glo3_main")
        varbit("me3_quest")
        varbit("elfcity_access")
        varbit("elr1_lore_bottle_07")
        varbit("quest_wizard_child_main")
        varbit("miniquest_duel_arena_removal_rubble_pile1_cleared")
        varbit("atvar_easy_done")
        varbit("atvar_elite_done")
        varbit("atseer_hard_done")
        varbit("atar_hard_done")
        varbit("elfcity_max_guild_skill_portal_a")
        varbit("elfcity_max_guild_skill_portal_b")
        varbit("elfcity_max_guild_pvm_portal")
        varbit("elfcity_max_guild_tele_toggle")
        varbit("pvm_hub_portal_1")
        varbit("pvm_hub_portal_2")
        varbit("bslay_target")
        varbit("bslay_kills_left")
        for (altar in listOf("air", "mind", "water", "earth", "fire", "body", "cosmic", "chaos", "astral", "nature", "law", "death", "blood", "soul")) {
            varbit("hooks3_consumed_$altar")
        }
        varbit("farming_spirit_tree_varbit_1")
        varbit("farming_spirit_tree_varbit_2")
        varbit("farming_spirit_tree_varbit_3")
        varbit("farming_spirit_tree_varbit_4")
        varbit("pog_spirit")
        varbit("elfcity_spirit_tree_unlocked")
        varbit("elr0_miniquest")
        varbit("thigui_quest")
        varbit("fris_quest")
        varp("viking")
        varp("troll_quest")
        varbit("atfrem_hard_reward")
        varbit("atfrem_elite_reward")
        varp("fever_quest")
        varp("regicide_quest")
        add(Req("hasDustyKey") { flag(it.inBackpack("dusty_key")) })
        varp("routequest")
        varbit("quest_wendle_secrets_main")
        varp("legendsquest")
        varbit("fairyring_kharazi_unlocked")
        varbit("tmf_main")
        varbit("horrorquest")
        varbit("swansong")
        skill("rangeLevel")
        skill("fishingLevel")
        skill("craftingLevel")
        varbit("mdaughter_quest_var")
        varp("elemental_workshop_bits")
        // The old Trollheim row clicks the Standard book itself: no swap, not the action bar.
        add(Req("TrollHeimBool") { flag(SpellTeleports.check(TeleportSpell.TROLLHEIM).plan?.let { !it.needsSwap && !it.fromBar } == true) })
        varbit("myreque_2_quest")
        varbit("sum2_quest")
        varbit("ash1_main")
        varp("treequest")
        add(Req("hasRope") { it.amountNamed("Rope") })
        add(Req("hasWaterFallKey") { it.amountNamed("A key") })
        add(Req("NatureSpiritQuest") { flag(CacheQuests.completed("Nature Spirit")) })
        add(Req("TempleTrekkingLevel") { templeTrekkingLevel() })
        add(Req("questHeartsOfSanguine") { flag(CacheQuests.completed("Hearts of Sanguine")) })
        add(Req("questUndergroundPass") { CacheQuests.progress("Underground Pass").coerceAtLeast(0) })
        add(Req("questDODCoinRealm") { questProgress("q200_intro") })
        add(Req("TreeGnomeVillageProgress") { questProgress("tree_gnome_village") })
        varbit("arch_kharidet_prison_door_status")
        varbit("arch_kharidet_mq_fort_access")
        varbit("arch_kharidet_mq_cell_controller")
        add(Req("hasLegatusPendant") { flag(varbitNamed("toolbelt_legatus_pendant") > 0) })
        add(Req("MonksFriendQuestProgress") { questProgress("monks_friend") })
        varp("priestperil")
        varp("mm_main")
        varbit("afr_quest")
        add(Req("hasMiningCampAccess") { carried ->
            val keyRing = carried.anywhere("favour_key_ring") || varbitNamed("toolbelt_keyring") > 0
            val metalKeyOnRing = keyRing && varpNamed("favour_keyring") and METAL_KEY_ON_RING != 0
            flag(varpNamed("desertrescue") >= 30 && (carried.anywhere("metal_key") || metalKeyOnRing))
        })
        varbit("morytania_entry_allowed")
        add(Req("questAShadowOverAshdale") { flag(questCompleted("ash1")) })
        add(Req("questHeraldsOfCrimson") { flag(questCompleted("quest_wendle_heralds")) })
        varbit("myarm")
        varbit("swamp_caves_roped_entrance")
        varbit("dino_base_camp_tutorial")
        varbit("wildy_events_rewards_enchantment_luck")
        varbit("ics_little_var")
        add(Req("hasPontifexRing") {
            flag(it.anywhere("arch_pontifex_signet_ring", "quest_azzanadra_ring", "quest_senntisten_ring") || varbitNamed("toolbelt_pontifex_ring") > 0)
        })
        val taken = mapTo(HashSet()) { it.key.lowercase() } + gatedKeys()
        for (key in upstreamVarKeys) if (key.lowercase() !in taken && !POA_KEY.matches(key)) add(Req(key) { rawVar(key) })
    }

    private fun spells(withoutSpells: Set<TeleportSpell>): List<Req> {
        var checks: Map<TeleportSpell, Boolean>? = null
        return TeleportSpell.entries.map { spell ->
            Req(spellKey(spell)) {
                val usable = checks ?: SpellTeleports.checkAll().mapValues { it.value.usable }.also { checks = it }
                flag(usable[spell] == true && spell !in withoutSpells)
            }
        }
    }

    private fun items(): List<Req> = buildList {
        add(Req("hasArchJournal") { flag(it.anywhere("arch_journal")) })
        add(Req("hasWS4Equiped") { flag(it.wearing(*WILDERNESS_SWORD_4)) })
        add(Req("hasWS1Equiped") { flag(it.wearing("atwild_reward_easy")) })
        add(Req("hasSkullSceptreEquiped") { flag(it.wearing("sos_skull_sceptre")) })
        add(Req("hasScepterOfTheGodsEquiped") { flag(charged(it.wornItem("hlr4m_sceptre"), "hlr4m_sceptre_charge")) })
        add(Req("hasWickedHood") { flag(it.anywhere("hooks3_hood")) })
        add(Req("hasEctophial") { flag(it.inBackpack("ectophial")) })
        add(Req("hasKG4") { flag(it.anywhere("atjun_gloves_elite")) })
        add(Req("hasLOTD") { flag(it.wearing("lucky_ring_dwarves")) })
        add(Req("hasHazelmereSignet") { flag(it.wearing("lucky_hazelmeres_ring")) })
        add(Req("hasDA3") { flag(it.inBackpack("atdes_amulet_hard")) })
        add(Req("hasDA4Equiped") { flag(it.wearing("atdes_amulet_elite")) })
        add(Req("hasLeelasFavourEquiped") { flag(it.wearing("quest_desert_leela_reward_bracelet")) })
        add(Req("hasROK") { flag(it.anywhere("rand_mission_contract")) })
        add(Req("hasFFT") { it.amountNamed("Fort Forinthry Teleport") })
        add(Req("hasROF") { flag(it.anywhere("lucky_onyx_ring")) })
        add(Req("hasDorgeshSphere") { flag(it.inBackpack("dorgesh_teleport_artifact")) })
        add(Req("hasFremBoots4") { flag(it.inBackpack("atfrem_boots_elite")) })
        add(Req("hasCOL") { flag(it.inBackpack("cape_of_legends")) })
        add(Req("hasDragonTeleport") { flag(it.inBackpack("dungeon_dragonfort_teletab")) })
        add(Req("hasExplorersRing4") { flag(it.anywhere("atlum_ring_elite")) })
        add(Req("hasMysticalSandSeed") { flag(it.inBackpack("desert_farm_teleport_permanent")) })
        add(Req("hasMagicalMetorChunk") { flag(it.inBackpack("quest_star_magical_meteorite_chunk")) })
        // Only while the currency pouch draws them: the item node clicks the pouch slot.
        add(Req("hasMemoryStrands") { flag(PouchItems.has(PouchItems.memoryStrand, "Teleport")) })
        add(Req("hasDramenStaff") { flag(DramenStaff.carried()) })
        add(Req(FAIRY_RING_STAFF_ACCESS) { flag(DramenStaff.fairyRingStaffAccess(true)) })
        for ((place, tablet) in HOUSE_TABLETS) add(Req("has${place}Tablet") { flag(it.inBackpack(tablet)) })
        add(Req("hasMenaphosJournalEquiped") { flag(it.wearing("menaphos_collections_journal")) })
        for ((key, varbit) in listOf(
            "menaphosJournalImperial" to "menaphos_imperial_teleport_charges",
            "menaphosJournalMerchant" to "menaphos_merchant_teleport_charges",
            "menaphosJournalPort" to "menaphos_port_teleport_charges",
            "menaphosJournalWorker" to "menaphos_worker_teleport_charges",
            "menaphosJournalSlayerDungeon" to "menaphos_slayer_teleport_charges",
            "menaphosJournalShiftingTombs" to "menaphos_tombs_teleport_charges",
        )) add(Req(key) { flag(varbitNamed(varbit) > 0 && it.anywhere("menaphos_collections_journal")) })
        add(Req("hasQuiverEquiped") { flag(it.wearing("atelf_quiver_elite")) })
        add(Req("hasGraceOfTheElvesEquiped") { flag(it.wearing("alch_onyx_skill_necklace_used")) })
        add(Req("hasNaturesSentinelTeleport") {
            flag(varbitNamed("trh113_elite_woodcutting_daily_teleports") < SENTINEL_TELEPORTS_A_DAY && SENTINEL_OUTFIT.all { name -> it.wearing(name) })
        })
        add(Req("hasNatureAltarTeleport") { flag(it.inBackpack("rcguild_tablet_nature")) })
        add(Req("hasJujuTeleportSpiritbag") { flag(it.inBackpack("ecosystem_teleport_bag")) })
        add(Req("hasHeartTeleport") { flag(it.inBackpack("poh_tablet_godwars2teleport")) })
        // Without the hard Wilderness achievements the obelisk sends you to a random obelisk, not the chosen one.
        add(Req("hasPortableObelisk") { flag(it.inBackpack("wildy_events_rewards_portable_obelisk") && varbitNamed("atwild_reward_hard") > 0) })
        add(Req("hasMasterFarmerHabitat") { flag(MasterFarmerHat.habitatUsable()) })
        // By its gameval name: by journal name the untracked holiday event of the same name comes first.
        add(Req("StolenHeartsQuestCompleted") { flag(questCompleted("ozan1")) })
    }

    private fun lodestones(): List<Req> = buildList {
        for (name in listOf("karamja", "al_kharid", "falador", "taverley", "draynor", "varrock")) {
            varbitId("thas_active_$name")?.let { id -> add(Req("varbit_$id") { flag(varps.getVarBit(id) > 0) }) }
        }
        for (name in listOf(
            "thas_active_edgeville", "thas_active_wilderness", "thas_active_port_sarim", "thas_active_cityofum", "thas_active_lumbridge",
            "thas_active_catherby", "thas_active_ardougne", "thas_active_seers", "thas_active_fremennik", "thas_active_eagles",
            "thas_active_yanille", "thas_active_ooglog", "thas_active_ashdale", "thas_active_burthorpe",
            "dino_base_camp_building_tier_lodestone", "thas_active_canifis", "player_fort_lodestone", "thas_active_menaphos",
            "thas_active_prifddinas", "thas_active_tirranwn", "thas_active_wendlewick",
        )) varbit(name)
    }

    private fun ifSlots(): List<Req> = buildList {
        add(Req("hasMaxCape") {
            flag(it.anywhere("poh_dummy_skillcape_max", "skillcape_max", "skillcape_max_hooded", "skillcape_120_all", "skillcape_120_all_hooded"))
        })
        add(Req("hasSlayerCape") {
            flag(
                it.anywhere(
                    "skillcape_slayer_trimmed", "skillcape_120_slayer", "skillcape_slayer_hooded", "skillcape_slayer_trimmed_hooded",
                    "skillcape_inverted_120_slayer", "skillcape_inverted_slayer_hooded", "skillcape_inverted_slayer_trimmed_hooded",
                ) || SkillcapePerks.usable(SkillcapePerks.SLAYER),
            )
        })
        add(Req("hasDungCape") {
            flag(
                it.anywhere(
                    "skillcape_dungeoneering", "skillcape_dungeoneering_trimmed", "skillcape_120_dungeoneering", "skillcape_dungeoneering_hooded",
                    "skillcape_dungeoneering_trimmed_hooded", "skillcape_inverted_120_dungeoneering", "skillcape_inverted_dungeoneering_hooded",
                    "skillcape_inverted_dungeoneering_trimmed_hooded",
                ) || SkillcapePerks.usable(SkillcapePerks.DUNGEONEERING),
            )
        })
        add(Req("hasQuestCape") {
            flag(it.anywhere("skillcape_qp", "skillcape_qp_hooded", "skillcape_mqc", "skillcape_inverted_qp_hooded") || SkillcapePerks.usable(SkillcapePerks.QUEST_POINT))
        })
        // The glider map, the Land of Snow portal list, the spirit tree map and the Arc's sailing map are menus.
        varp("grandtree")
        varbit("vib2_quest_main")
        add(Req(SPIRIT_TREE_ACCESS) { varpNamed("treequest") })
        add(Req("arcSailing") { varbitNamed("elr0_miniquest") })
        add(Req("arcCyclosisUnlocked") { varpNamed("pop_island_tracker") and CYCLOSIS_VOYAGE })
    }

    private fun menuItems(): List<Req> = buildList {
        for ((place, tablet) in HOUSE_TABLETS) add(Req("has${place}TabletModify") { flag(it.count(tablet) >= TABLETS_TO_MODIFY) })
        add(Req("hasWS1inInv") { flag(it.inBackpack("atwild_reward_easy")) })
        add(Req("hasWS4inInv") { flag(it.inBackpack(*WILDERNESS_SWORD_4)) })
        add(Req("hasAttunedSeed") { flag(it.anywhere("mourning_teleport_crystal_perm")) })
        // The ring refuses to teleport in combat, menu or not, and needs a charge to teleport at all.
        add(Req("hasTokkulZo") { flag(charged(it.backpackItem("tzhaar2_reward_ring"), "tzhaar2_ring_charge")) })
        add(Req("hasTokkulZoEquiped") { flag(charged(it.wornItem("tzhaar2_reward_ring"), "tzhaar2_ring_charge")) })
        add(Req("hasSkullSceptre") { flag(it.inBackpack("sos_skull_sceptre")) })
        add(Req("hasDM") { flag(it.anywhere("myq5_drakan_pendant")) })
        add(Req("hasDA4") { flag(it.inBackpack("atdes_amulet_elite")) })
        add(Req("hasGamesNeck") { flag(it.nameContains("games necklace (")) })
        add(Req("hasCombatBracelet") { flag(it.nameContains("combat bracelet (")) })
        add(Req("hasRingOfDuelling") { flag(it.nameContains("ring of duelling (")) })
        add(Req("hasSkillsNecklace") { flag(it.nameContains("skills necklace (")) })
        add(Req("hasScepterOfTheGods") { flag(charged(it.backpackItem("hlr4m_sceptre"), "hlr4m_sceptre_charge")) })
        // The full-charge lyre is its own item and keeps no count; the other counts in the sceptre's item var.
        add(Req("hasEnchantedLyre") {
            flag(it.inBackpack("magic_enchanted_strung_lyre_full_charge") || charged(it.backpackItem("magic_enchanted_strung_lyre"), "hlr4m_sceptre_charge"))
        })
        // The backpack copy alone counts whatever its charges: the planner's own precedence.
        add(Req("daveSpellbookWatchTower") {
            flag(varbitNamed("dave_claimed_reward_book_watchtower") > 0 && it.wearing("dave_reward_book") || it.inBackpack("dave_reward_book"))
        })
        for (place in listOf("Camelot", "Falador", "Ardougne", "Lumbridge", "Varrock")) {
            add(Req("daveSpellbook$place") { flag(varbitNamed("dave_claimed_reward_book_${place.lowercase()}") > 0 && it.anywhere("dave_reward_book")) })
        }
        for ((key, varbit) in listOf(
            "GrandExchange" to "grand_exchange", "BanditCamp" to "bandit_camp", "Clocktower" to "clocktower", "GuTanoth" to "gutanoth",
            "Lighthouse" to "lighthouse", "FortForinthry" to "fort_forinthry", "Miscellania" to "miscellania", "PhoenixLair" to "phoenix_lair",
            "Pollnivneach" to "pollnivneach", "TaiBwoWannai" to "tai_bwo_wannai",
        )) add(Req("hasGTAG${key}Charges") { flag(varbitNamed("trail17_teleport_charge_$varbit") > 0 && it.anywhere("trail17_gloves_tier3")) })
        add(Req("hasArchaeologyTeleport") { flag(it.inBackpack("arch_teletab")) })
        add(Req("hasArcJournal") { flag(it.inBackpack("elr1_waiko_tourist_brochure")) })
        for ((key, varbit) in listOf(
            "Waiko" to "waiko", "WhalesMaw" to "whales_maw", "Aminishi" to "aminishi", "Cyclosis" to "cyclosis", "TuaiLeit" to "tuai_leit",
            "Turtles" to "exturtles", "Goshima" to "goshima",
        )) add(Req("arcJournal$key") { flag(varbitNamed("elr1_${varbit}_teleport_charges") > 0 && it.inBackpack("elr1_waiko_tourist_brochure")) })
        // The Soul altar has no pool of its own, so its row keeps the omni key.
        add(Req("hasWickedHoodCharges") { flag(varbitNamed("hooks3_bonus_teleports") > 0 && it.anywhere("hooks3_hood")) })
        for ((altar, pool) in WICKED_HOOD_ALTAR_POOLS) {
            add(Req("hasWickedHood${altar}Charges") {
                flag((varps.getVarBit(pool) > 0 || varbitNamed("hooks3_bonus_teleports") > 0) && it.anywhere("hooks3_hood"))
            })
        }
        add(Req("hasBigBookOPiracy") { flag(it.anywhere("piratefinale_teleport")) })
        add(Req("hasMenaphosJournal") { flag(it.inBackpack("menaphos_collections_journal")) })
        add(Req("hasLeelasFavour") { flag(it.anywhere("quest_desert_leela_reward_bracelet")) })
        add(Req("hasQuiver") { flag(it.anywhere("atelf_quiver_elite")) })
        add(Req("hasPortableFairyRing") { flag(FairyRingNode.portableDevice.usable(it.backpack, it.worn)) })
        add(Req("hasSpiritTreeReRooter") { flag(SpiritTreeRerooterNode.device.usable(it.backpack, it.worn)) })
    }

    private fun passages(carried: Carried): Map<String, Int> = runCatching { AbyssPassage.keys(carried.backpack, carried.worn) }.getOrDefault(emptyMap())

    /** The keys of the gated blocks, so a raw var key never slips past a gate. */
    private fun gatedKeys(): Set<String> = (items() + menuItems() + lodestones() + ifSlots()).mapTo(HashSet()) { it.key.lowercase() }

    private fun rawVar(key: String): Int = when {
        key.startsWith("varbit_") -> key.removePrefix("varbit_").toIntOrNull()?.let(varps::getVarBit) ?: 0
        else -> key.removePrefix("varp_").toIntOrNull()?.let(varps::getVar) ?: 0
    }

    private fun MutableList<Req>.varbit(name: String) {
        varbitId(name)?.let { id -> add(Req("varbit_$id") { varps.getVarBit(id) }) }
    }

    private fun MutableList<Req>.varp(name: String) {
        varpId(name)?.let { id -> add(Req("varp_$id") { varps.getVar(id) }) }
    }

    private fun MutableList<Req>.skill(key: String) {
        val skill = SKILLS.getValue(key)
        add(Req(key) { getRealLevel(skill) })
    }

    /** The Temple Trekking level, from the game's own script; 0 off the game thread, where it may not run. */
    private fun templeTrekkingLevel(): Int {
        if (!GameThread.isCurrent()) return 0
        return CS2Executor.executeScriptForResultSync(TEMPLE_TREKKING_SCRIPT, emptyList())?.int ?: 0
    }

    private fun charged(item: Item?, chargeVar: String): Boolean {
        item ?: return false
        val varbit = Cache.objectVarbit(chargeVar)?.id ?: return false
        return item.varDomainVarBit(varbit) > 0
    }

    private fun questCompleted(name: String): Boolean = id(QUEST, name)?.let(CacheQuests::completed) == true

    private fun questProgress(name: String): Int = id(QUEST, name)?.let(CacheQuests::progress)?.coerceAtLeast(0) ?: 0

    private val ids = ConcurrentHashMap<String, Int>()

    private fun id(type: String, name: String): Int? {
        val key = "$type:$name"
        ids[key]?.let { return it.takeIf { id -> id >= 0 } }
        val id = runCatching { Gameval.id(type, name) }.getOrNull()
        if (id == null) println("[RequirementsBuilder] no gameval $type named $name; its key is left out")
        ids[key] = id ?: -1
        return id
    }

    private fun objId(name: String): Int? = id(Gameval.OBJ, name)

    private fun varbitId(name: String): Int? = id(Gameval.VARBIT, name)

    private fun varpId(name: String): Int? = id(Gameval.VAR_PLAYER, name)

    private fun varbitNamed(name: String): Int = varbitId(name)?.let(varps::getVarBit) ?: 0

    private fun varpNamed(name: String): Int = varpId(name)?.let(varps::getVar) ?: 0

    private fun flag(value: Boolean) = if (value) 1 else 0
}
