package com.projectx.script.impl.devin.zuk

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.game.interfaces.parseAllActionBarItems
import com.projectx.script.api.inventory
import world.gregs.voidps.cache.Cache
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.highlight.InterfaceHighlight
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.AdrenalineType
import org.projectx.core.game.combat.CombatContext

/**
 * Resolves which abilities on the player's own action bars answer the current prompt, so the coach
 * names abilities the player actually has rather than a generic instruction. Every predicate reads
 * cache params — nothing here is a hardcoded ability list.
 */
object ZukAbilities {

    private const val MAX_SUGGESTIONS = 3
    private const val HIGHLIGHT_PREFIX = "zuk-ability-"

    private var highlighted = emptySet<String>()

    @Volatile
    var primarySlot: IFSlot? = null
        private set

    fun pressPrimary(): Boolean {
        val slot = primarySlot ?: return false
        return runCatching { slot.click() }.getOrDefault(false)
    }

    /**
     * Highlights the matching slots on the player's real action bar and returns their names for the
     * HUD. A prescribed ability wins over the generic tier match — "press Resonance" is a better
     * instruction than "press an ultimate" — and only off-cooldown abilities are shown: pointing at
     * one the player cannot press is worse than pointing at nothing.
     */
    fun suggestionsFor(action: ZukAction?, prescribed: List<String>): List<String> {
        if (prescribed.isNotEmpty()) {
            val named = resolvePrescribed(prescribed)
            if (named.isNotEmpty()) return highlight(named, action)
        }
        val predicate = predicateFor(action)
        if (predicate == null) {
            clearHighlights()
            return emptyList()
        }
        val matches = runCatching {
            parseAllActionBarAbilities()
                .filterKeys(predicate)
                .entries
                .filter { it.key.offCd() }
                .sortedBy { it.key.name }
                .take(MAX_SUGGESTIONS)
        }.getOrDefault(emptyList())
        return highlight(matches, action)
    }

    /** Order follows the prescription, so the preferred ability is listed and highlighted first. */
    private fun resolvePrescribed(names: List<String>): List<Map.Entry<AbilityType, IFSlot>> = runCatching {
        val bar = parseAllActionBarAbilities()
        val wanted = names.mapNotNull { AbilityRegistry.byCacheName(it)?.structId }
        bar.entries
            .filter { it.key.structId in wanted && it.key.offCd() }
            .sortedBy { wanted.indexOf(it.key.structId) }
            .take(MAX_SUGGESTIONS)
    }.getOrDefault(emptyList())

    private fun highlight(matches: List<Map.Entry<AbilityType, IFSlot>>, action: ZukAction?): List<String> {
        primarySlot = matches.firstOrNull()?.value
        val wanted = HashSet<String>(matches.size)
        for ((ability, slot) in matches) {
            val key = "$HIGHLIGHT_PREFIX${ability.structId}"
            wanted += key
            runCatching {
                InterfaceHighlight.add(key, slot, color = highlightColor(action), label = null, pulse = true, chevron = false)
            }
        }
        (highlighted - wanted).forEach { runCatching { InterfaceHighlight.remove(it) } }
        highlighted = wanted

        return matches.mapNotNull { it.key.name.takeIf(String::isNotBlank) }.distinct()
    }

    fun clearHighlights() {
        highlighted.forEach { runCatching { InterfaceHighlight.remove(it) } }
        highlighted = emptySet()
        primarySlot = null
        highlightVulnBomb(false)
        highlightConsumables(false)
    }

    private var vulnHighlighted = emptySet<String>()

    /**
     * The bomb is an item, not a barred ability, so the VULN prompt marks its backpack slot and,
     * when it also sits on the action bar, that bar slot too — pointing at the thing to click
     * whether or not the auto-throw toggle is on.
     */
    fun highlightVulnBomb(wanted: Boolean) {
        val slots = if (!wanted) emptyMap() else buildMap {
            backpackSlot(VULN_FAMILY)?.let { put("$VULN_KEY-inv", it) }
            barItemSlots(VULN_FAMILY).firstOrNull()?.let { put("$VULN_KEY-bar", it) }
        }
        vulnHighlighted = swapHighlights(vulnHighlighted, slots, ZukAction.VULN)
    }

    private var consumablesHighlighted = emptySet<String>()

    /**
     * Drill-only: marks everything the sustain automation would click — the Eat Food bar slot,
     * plus brews and restores in both the backpack and any bar slot they sit on.
     */
    fun highlightConsumables(wanted: Boolean) {
        val slots = if (!wanted) emptyMap() else buildMap {
            eatFoodSlot()?.let { put("zuk-test-food", it) }
            for (family in ZukSustain.BREW_FAMILY + ZukSustain.RESTORE_FAMILY) {
                backpackSlot(listOf(family))?.let { put("zuk-test-inv-$family", it) }
                barItemSlots(listOf(family)).firstOrNull()?.let { put("zuk-test-bar-$family", it) }
            }
        }
        consumablesHighlighted = swapHighlights(consumablesHighlighted, slots, null)
    }

    private fun swapHighlights(current: Set<String>, slots: Map<String, IFSlot>, action: ZukAction?): Set<String> {
        (current - slots.keys).forEach { runCatching { InterfaceHighlight.remove(it) } }
        slots.forEach { (key, slot) ->
            runCatching {
                InterfaceHighlight.add(key, slot, color = highlightColor(action), label = null, pulse = true, chevron = false)
            }
        }
        return slots.keys
    }

    private fun eatFoodSlot(): IFSlot? = runCatching {
        parseAllActionBarAbilities().entries.firstOrNull { it.key.structId == ZukSustain.EAT_FOOD_STRUCT }?.value
    }.getOrNull()

    private fun backpackSlot(families: List<String>): IFSlot? = runCatching {
        inventory.firstOrNull { item -> families.any { item.name.startsWith(it, ignoreCase = true) } }?.slot
    }.getOrNull()

    private fun barItemSlots(families: List<String>): List<IFSlot> = runCatching {
        parseAllActionBarItems().mapNotNull { (itemId, slot) ->
            val name = Cache.obj(itemId)?.name ?: return@mapNotNull null
            slot.takeIf { families.any { family -> name.startsWith(family, ignoreCase = true) } }
        }
    }.getOrDefault(emptyList())

    private val VULN_FAMILY = listOf("Vulnerability bomb")
    private const val VULN_KEY = "zuk-vuln-bomb"

    private fun highlightColor(action: ZukAction?): Int =
        ImGuiColors.rgba(
            ((action?.rgb ?: 0xFFC24A) ushr 16) and 0xFF,
            ((action?.rgb ?: 0xFFC24A) ushr 8) and 0xFF,
            (action?.rgb ?: 0xFFC24A) and 0xFF
        )

    /**
     * SURVIVE has no generic fallback on purpose: while the wave-15 challenge is intact only
     * Barricade preserves it, and the caller prescribes exactly what may be pressed — highlighting
     * "any defensive" would invite the Resonance press that breaks the challenge.
     */
    private fun predicateFor(action: ZukAction?): ((AbilityType) -> Boolean)? = when (action) {
        ZukAction.STUN -> { it -> it.stuns }
        // Conjures carry adrenaline_type THRESHOLD too — only offensive abilities break shields.
        ZukAction.THRESHOLD -> { it -> it.adrenalineTier == AdrenalineType.THRESHOLD && damaging(it) }
        ZukAction.BIG_HIT, ZukAction.BURST -> { it -> it.adrenalineTier in HEAVY_TIERS && damaging(it) }
        else -> null
    }

    /**
     * The `combatv2_ability_target_type` param did not filter conjures in play (23:27 run pressed
     * one 5×), so the summon/re-activation name families are excluded directly; the `ABILITY`
     * capture line records each candidate's params to find the real discriminator.
     */
    private fun damaging(ability: AbilityType): Boolean {
        val name = ability.name
        val excluded = SELF_FAMILIES.any { name.startsWith(it, ignoreCase = true) }
        if (loggedCandidates.add(ability.structId)) {
            println("[ZukCap] ABILITY $name struct=${ability.structId} adren=${ability.adrenalineType} target=${ability.targetType} excluded=$excluded")
        }
        return !excluded && ability.targetType != SELF_TARGET
    }

    private val loggedCandidates = HashSet<Int>()
    private val SELF_FAMILIES = listOf("Conjure ", "Command ", "Invoke ", "Life Transfer")
    private const val SELF_TARGET = 3

    private val HEAVY_TIERS = setOf(AdrenalineType.ULTIMATE, AdrenalineType.SPECIAL_ATTACK)
}

internal fun AbilityType.offCd(): Boolean =
    runCatching { offCd(CombatContext.current) }.getOrDefault(true)
