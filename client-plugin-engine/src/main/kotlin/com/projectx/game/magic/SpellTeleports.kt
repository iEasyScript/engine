package com.projectx.game.magic

import com.projectx.game.interfaces.IFSlot
import com.projectx.quest.runtime.CacheQuests
import com.projectx.script.Script
import com.projectx.script.api.Spellbook
import com.projectx.script.api.TeleportSpell
import com.projectx.script.api.actionbarAbilities
import com.projectx.script.api.castAbility
import com.projectx.script.api.equipment
import com.projectx.script.api.getCurrentLevel
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.util.FeatureIds
import com.projectx.util.nonProfiledGaussian
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.type.Tile

internal enum class CastResult { STARTED, NOT_USABLE, NOT_LISTED, SWAP_FAILED, NOT_STARTED, STOPPED }

/** Checks and casts teleport spells on the live game: from an action bar or the open magic book, swapping book first. */
internal object SpellTeleports {
    private const val SWAP_MS = 3_000L
    private const val READY_MS = 1_800L
    private const val START_MS = 2_400L
    private const val LANDING_MS = 8_000L
    private const val ARRIVAL_ANIMATION_MS = 3_000L
    private const val LANDED_TILES = 2
    private const val REACTION_MS = 200
    private const val REACTION_VARIANCE = 40 * 40

    private class Ids(val spellbookVarbit: Int, val bookInterface: Int, val bookComponent: Int)

    private val ids = FeatureIds("SpellTeleports") {
        val book = Gameval.requireComponentHash("toplevel_v2_window_ability_book_magic:abilities_layer")
        Ids(Gameval.requireId(Gameval.VARBIT, "zaros_spellbook"), book ushr 16, book and 0xFFFF)
    }

    fun currentBook(): Spellbook? {
        val known = ids.get() ?: return null
        return when (varps.getVarBit(known.spellbookVarbit)) {
            0 -> Spellbook.STANDARD
            1 -> Spellbook.ANCIENT
            2 -> Spellbook.LUNAR
            else -> null
        }
    }

    fun state(): MagicState {
        val onBar = actionbarAbilities.keys.mapNotNullTo(HashSet()) { SpellCatalogue.abilityIdOfStruct(it.structId) }
        return MagicState(
            book = currentBook(),
            magicLevel = getCurrentLevel(Skill.MAGIC),
            runes = CarriedRunes.supply(inventory.toList(), equipment.toList()),
            onBar = onBar,
            bookOpen = bookOpen(),
            met = ::unlockMet,
        )
    }

    fun check(spell: TeleportSpell): SpellCheck = check(spell, state())

    /** Every spell checked against one sample of the game. */
    fun checkAll(): Map<TeleportSpell, SpellCheck> {
        val state = state()
        return TeleportSpell.entries.associateWith { check(it, state) }
    }

    private fun check(spell: TeleportSpell, state: MagicState): SpellCheck =
        SpellChecks.check(spell, SpellCatalogue.info(spell.abilityId), state, SpellCatalogue::swap)

    /** Casts [spell], a Spellbook Swap first when it needs one; [CastResult.STARTED] once the teleport visibly began. */
    suspend fun cast(script: Script, spell: TeleportSpell, stop: () -> Boolean): CastResult {
        var check = check(spell)
        var plan = check.plan
        if (!check.usable || plan == null) {
            println("[Spell] $spell: ${check.reason}")
            return CastResult.NOT_USABLE
        }
        val swap = plan.swap
        val target = plan.spell.book
        if (swap != null && target != null) {
            if (!click(swap, plan.swapFromBar)) return CastResult.NOT_LISTED
            script.delayUntil(jittered(SWAP_MS)) { stop() || currentBook() == target }
            if (stop()) return CastResult.STOPPED
            if (currentBook() != target) return CastResult.SWAP_FAILED
            check = check(spell)
            plan = check.plan
            if (!check.usable || plan == null || plan.needsSwap) {
                println("[Spell] $spell after ${swap.name}: ${check.reason}")
                return CastResult.NOT_USABLE
            }
        }
        val ability = AbilityType(plan.spell.structId)
        script.delayUntil(jittered(READY_MS)) { stop() || !localPlayer.isAnimating && ability.offCd() }
        if (stop()) return CastResult.STOPPED
        script.delay(REACTION_MS, REACTION_VARIANCE)
        val from = localPlayer.tile
        if (!click(plan.spell, plan.fromBar)) return CastResult.NOT_LISTED
        script.delayUntil(jittered(START_MS)) { stop() || localPlayer.isAnimating || localPlayer.tile != from }
        if (stop()) return CastResult.STOPPED
        return if (localPlayer.isAnimating || localPlayer.tile != from) CastResult.STARTED else CastResult.NOT_STARTED
    }

    /** After a cast: true once the player has left [from] and the arrival animation has ended. */
    suspend fun awaitLanding(script: Script, from: Tile, stop: () -> Boolean): Boolean {
        val landed = { localPlayer.tile.let { it.plane != from.plane || it.getDistance(from) > LANDED_TILES } }
        script.delayUntil(jittered(LANDING_MS)) { stop() || landed() }
        if (!landed()) return false
        script.delayUntil(jittered(ARRIVAL_ANIMATION_MS)) { stop() || !localPlayer.isAnimating }
        return true
    }

    private fun click(spell: SpellInfo, fromBar: Boolean): Boolean {
        if (fromBar) return castAbility(AbilityType(spell.structId))
        val known = ids.get() ?: return false
        return IFSlot(known.bookInterface, known.bookComponent, spell.abilityId).click(1)
    }

    private fun bookOpen(): Boolean {
        val known = ids.get() ?: return false
        return interfaces.getComponent(known.bookInterface, known.bookComponent) != null ||
            interfaces.getComponentRaw(known.bookInterface, known.bookComponent)?.visible == true
    }

    private fun unlockMet(unlock: SpellUnlock): Boolean = when (unlock) {
        is SpellUnlock.Quest -> CacheQuests.completed(unlock.name)
        is SpellUnlock.Varbit -> Gameval.id(Gameval.VARBIT, unlock.name)?.let { varps.getVarBit(it) >= unlock.atLeast } == true
        is SpellUnlock.Varp -> Gameval.id(Gameval.VAR_PLAYER, unlock.name)?.let { varps.getVar(it) >= unlock.atLeast } == true
        else -> false
    }

    private fun jittered(meanMs: Long): Long {
        val sd = (meanMs / 8).toInt()
        return nonProfiledGaussian(meanMs.toInt(), sd * sd).toLong().coerceIn(meanMs * 3 / 4, meanMs * 3 / 2)
    }
}
