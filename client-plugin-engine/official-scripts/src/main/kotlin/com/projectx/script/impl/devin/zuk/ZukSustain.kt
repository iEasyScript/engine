package com.projectx.script.impl.devin.zuk

import com.projectx.game.interfaces.parseAllActionBarAbilities
import com.projectx.script.api.getCurrentLevel
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.healthPercent
import com.projectx.script.api.inventory
import com.projectx.script.api.prayerPercent
import org.projectx.core.game.skill.Skill

/**
 * Opt-in eating and potion sips, gate-paced like every other automation.
 *
 * Food goes through the game's own `Eat Food` actionbar ability, which picks the best food itself.
 * Brews and restores are matched loosely by name family, so every dose and both potion and flask
 * forms resolve. Food is skipped at the normal threshold while Searing Pain stacks are up —
 * drained Constitution halves it and the restore is the documented counter — but the emergency
 * threshold eats anyway rather than preserve a rule on a corpse.
 *
 * Pacing: outside the emergency floor, every consumable re-arms on [REARM_MS], not the game's own
 * 3-tick cooldown. Hard mode pins health under the thresholds for minutes at a time, and sipping
 * at the raw gate cadence turns the helper into an IV drip that empties the backpack while every
 * fish bleeds the adrenaline the damage output needed (the 20:42 HM run: 63 brew doses and 9
 * sailfish by wave 6). Sustain is a safety net, not the primary healer.
 *
 * Restores serve two triggers: low prayer (the configured threshold) and combat-stat drain
 * ([statsDrained]) — brews drain the damage stats and searing drains Constitution, and both are
 * cured by the same sip.
 */
object ZukSustain {

    fun tick(eatBelowPercent: Int, brewBelowPercent: Int, restoreBelowPercent: Int, searingStacks: Int) {
        val hp = runCatching { healthPercent }.getOrNull() ?: return
        if (hp <= EMERGENCY_HP_PERCENT) {
            if (eatFood(CONSUME_MS)) return
            if (sip(BREW_FAMILY, "sustain:brew", CONSUME_MS)) return
        }
        if (statsDrained() && sip(RESTORE_FAMILY, "sustain:restore", REARM_MS)) return
        if (brewBelowPercent > 0 && hp <= brewBelowPercent) {
            if (sip(BREW_FAMILY, "sustain:brew", REARM_MS)) return
        }
        if (eatBelowPercent > 0 && hp <= eatBelowPercent && searingStacks == 0) {
            if (eatFood(REARM_MS)) return
        }
        if (restoreBelowPercent > 0) {
            val prayer = runCatching { prayerPercent }.getOrNull() ?: return
            if (prayer <= restoreBelowPercent) sip(RESTORE_FAMILY, "sustain:restore", REARM_MS)
        }
    }

    private fun eatFood(cooldownMs: Long): Boolean {
        if (!hasFood()) return false
        val slot = runCatching {
            parseAllActionBarAbilities().entries.firstOrNull { it.key.structId == EAT_FOOD_STRUCT }?.value
        }.getOrNull() ?: return false
        if (!ZukInputGate.allow("sustain:food", cooldownMs)) return false
        val clicked = runCatching { slot.click() }.getOrDefault(false)
        if (clicked) ZukCapture.sustain("food")
        return clicked
    }

    private fun sip(family: List<String>, gateKey: String, cooldownMs: Long): Boolean {
        val item = runCatching {
            inventory.firstOrNull { candidate -> family.any { candidate.name.startsWith(it, ignoreCase = true) } }
        }.getOrNull() ?: return false
        if (!ZukInputGate.allow(gateKey, cooldownMs)) return false
        val clicked = runCatching { item.click(1) }.getOrDefault(false)
        if (clicked) ZukCapture.sustain(gateKey.substringAfter(':'))
        return clicked
    }

    fun hasEatFoodBarred(): Boolean = runCatching {
        parseAllActionBarAbilities().keys.any { it.structId == EAT_FOOD_STRUCT }
    }.getOrDefault(false)

    /**
     * Real food = anything in the backpack with an `Eat` option, read off the item's own cache
     * definition. The Eat Food ability stays barred and pressable with an empty larder — the 20:42
     * run kept pressing it after the last fish, spending the global input budget on nothing — so
     * the backpack, not the bar, is the guard.
     */
    fun hasFood(): Boolean = supplies().food
    fun hasBrew(): Boolean = supplies().brews
    fun hasRestore(): Boolean = supplies().restores

    fun testFood(): Boolean = eatFood(CONSUME_MS)
    fun testBrew(): Boolean = sip(BREW_FAMILY, "sustain:brew", CONSUME_MS)
    fun testRestore(): Boolean = sip(RESTORE_FAMILY, "sustain:restore", CONSUME_MS)

    class Supplies(val food: Boolean, val brews: Boolean, val restores: Boolean)

    @Volatile
    private var cachedSupplies = Supplies(food = false, brews = false, restores = false)

    @Volatile
    private var suppliesCheckedAt = 0L

    /** Scanned at most once a second — the HUD banner asks every frame. */
    private fun supplies(): Supplies {
        val now = System.currentTimeMillis()
        if (now - suppliesCheckedAt >= SUPPLY_CHECK_MS) {
            cachedSupplies = runCatching {
                var food = false
                var brews = false
                var restores = false
                for (item in inventory) {
                    val name = item.name
                    when {
                        !brews && BREW_FAMILY.any { name.startsWith(it, ignoreCase = true) } -> brews = true
                        !restores && RESTORE_FAMILY.any { name.startsWith(it, ignoreCase = true) } -> restores = true
                        !food && item.invOps.filterNotNull().any { it.equals("Eat", ignoreCase = true) } -> food = true
                    }
                    if (food && brews && restores) break
                }
                Supplies(food, brews, restores)
            }.getOrDefault(cachedSupplies)
            suppliesCheckedAt = now
        }
        return cachedSupplies
    }

    private const val SUPPLY_CHECK_MS = 1_000L

    /** `Eat Food#44225` in the 17:45 `BAR` line — the live bar's own struct id. */
    const val EAT_FOOD_STRUCT = 44225

    /**
     * Saradomin brew pays for its healing by draining every non-Defence combat stat, and Searing
     * Pain drains Constitution. Drained deep enough, damage abilities refuse their own level
     * requirements — the 20:42 HM run logged four `You require level N Necromancy` refusals
     * mid-challenge after 63 unrestored doses. A restore therefore outranks the next brew in
     * [tick], so heavy brewing self-corrects instead of compounding; boosts never trigger this
     * (only current BELOW real counts).
     */
    private fun statsDrained(): Boolean = runCatching {
        DRAIN_WATCHED.any { getCurrentLevel(it) <= getRealLevel(it) - DRAIN_TRIGGER_LEVELS }
    }.getOrDefault(false)

    private val DRAIN_WATCHED = listOf(
        Skill.ATTACK, Skill.STRENGTH, Skill.MAGIC, Skill.RANGED, Skill.NECROMANCY, Skill.CONSTITUTION
    )

    /** Roughly two brew doses of drain on a high stat — pairs restores 1:2-ish with heavy brewing. */
    private const val DRAIN_TRIGGER_LEVELS = 15

    /** Below this, dying outranks both the searing no-eat rule and the re-arm pacing. */
    private const val EMERGENCY_HP_PERCENT = 25

    /** The consumable global cooldown is three ticks — the floor, used only by the emergency lane. */
    private const val CONSUME_MS = 1_800L

    /** Twelve ticks between non-emergency doses; see the pacing note above. */
    private const val REARM_MS = 7_200L

    val BREW_FAMILY = listOf("Saradomin brew")
    val RESTORE_FAMILY = listOf("Super restore")
}
