package com.projectx.game.magic

import com.projectx.script.api.Spellbook
import com.projectx.script.api.TeleportSpell

/** A spell as the cache describes it; [book] is null for a spell on every book. */
internal data class SpellInfo(
    val abilityId: Int,
    val structId: Int,
    val name: String,
    val book: Spellbook?,
    val level: Int,
    val runes: Map<Rune, Int>,
)

/** What has to be true before a spell shows as castable rather than locked. */
internal sealed interface SpellUnlock {
    data object Always : SpellUnlock

    data class Quest(val name: String) : SpellUnlock

    data class Varbit(val name: String, val atLeast: Int) : SpellUnlock

    data class Varp(val name: String, val atLeast: Int) : SpellUnlock

    /** No var records it, so the spell sitting on an action bar is the proof: a locked ability cannot be placed there. */
    data class ShownOnBar(val how: String) : SpellUnlock

    data class Both(val first: SpellUnlock, val second: SpellUnlock) : SpellUnlock

    fun describe(): String = when (this) {
        Always -> "nothing"
        is Quest -> "the quest $name"
        is Varbit -> "$name >= $atLeast"
        is Varp -> "$name >= $atLeast"
        is ShownOnBar -> "$how (put it on an action bar to use it)"
        is Both -> "${first.describe()} and ${second.describe()}"
    }
}

internal object SpellUnlocks {
    private val LIVID_FARM = "Livid Farm produce points"

    private val SPELLS: Map<TeleportSpell, SpellUnlock> = mapOf(
        TeleportSpell.WENDLEWICK to SpellUnlock.Quest("Visions of Havenhythe"),
        TeleportSpell.ARDOUGNE to SpellUnlock.Quest("Plague City"),
        TeleportSpell.WATCHTOWER to SpellUnlock.Quest("Watchtower"),
        TeleportSpell.TROLLHEIM to SpellUnlock.Quest("Eadgar's Ruse"),
        TeleportSpell.GOD_WARS_DUNGEON to SpellUnlock.Varbit("tmf_main", 200),
        TeleportSpell.WESTERN_KHARAZI_JUNGLE to SpellUnlock.Varp("legendsquest", 75),
        TeleportSpell.NORTH_WESTERN_ANACHRONIA to SpellUnlock.Varbit("dino_base_camp_tutorial", 30),
        TeleportSpell.EASTERN_ANACHRONIA to SpellUnlock.Varbit("dino_base_camp_tutorial", 30),
        TeleportSpell.WARS_RETREAT to SpellUnlock.Varbit("pvm_hub_teleport_unlocked", 1),
        TeleportSpell.TROLLHEIM_FARM to SpellUnlock.Both(SpellUnlock.Varbit("myarm", 320), SpellUnlock.ShownOnBar(LIVID_FARM)),
        TeleportSpell.SOUTH_FALADOR to SpellUnlock.ShownOnBar(LIVID_FARM),
        TeleportSpell.NORTH_ARDOUGNE to SpellUnlock.ShownOnBar(LIVID_FARM),
        TeleportSpell.MAZCAB to SpellUnlock.ShownOnBar("bought from the Armoursmith on Mazcab"),
        TeleportSpell.ANACHRONIA to SpellUnlock.ShownOnBar("the Totem of the Abyss placed"),
        TeleportSpell.SKELETAL_HORROR to SpellUnlock.ShownOnBar("the Fur 'n Seek wish list"),
        TeleportSpell.KANDARIN_MONASTERY to SpellUnlock.ShownOnBar("the easy Ardougne achievement reward"),
        TeleportSpell.MANOR_FARM to SpellUnlock.ShownOnBar("the medium Ardougne achievement reward"),
    )

    fun of(spell: TeleportSpell): SpellUnlock = SPELLS[spell] ?: SpellUnlock.Always

    /** Being able to swap onto a book: Ancient Magicks come with Desert Treasure, the Lunar book with Lunar Diplomacy. */
    fun book(book: Spellbook): SpellUnlock = when (book) {
        Spellbook.STANDARD -> SpellUnlock.Always
        Spellbook.ANCIENT -> SpellUnlock.Quest("Desert Treasure")
        Spellbook.LUNAR -> SpellUnlock.Varbit("lunar_quest_main", 190)
    }
}

/** The game as far as casting a spell goes, sampled once. [met] answers the quest and var unlocks. */
internal class MagicState(
    val book: Spellbook?,
    val magicLevel: Int,
    val runes: RuneSupply,
    val onBar: Set<Int>,
    val bookOpen: Boolean,
    val met: (SpellUnlock) -> Boolean,
)

/** How a spell is cast: from an action bar or the magic book, after a Spellbook Swap when [swap] is set. */
internal data class SpellPlan(val spell: SpellInfo, val fromBar: Boolean, val swap: SpellInfo? = null, val swapFromBar: Boolean = false) {
    val needsSwap: Boolean get() = swap != null
}

internal data class SpellCheck(val usable: Boolean, val reason: String, val plan: SpellPlan?) {
    companion object {
        fun no(reason: String) = SpellCheck(false, reason, null)
    }
}

internal object SpellChecks {

    fun check(spell: TeleportSpell, info: SpellInfo?, state: MagicState, swapTo: (Spellbook, Spellbook) -> SpellInfo?): SpellCheck {
        info ?: return SpellCheck.no("$spell is not in the game cache")
        val unlock = SpellUnlocks.of(spell)
        if (!met(unlock, info.abilityId, state)) return SpellCheck.no("${info.name} is locked: needs ${unlock.describe()}")
        if (state.magicLevel < info.level) return SpellCheck.no("${info.name} needs Magic ${info.level}, the player has ${state.magicLevel}")
        val spellOnBar = info.abilityId in state.onBar
        if (!spellOnBar && !state.bookOpen) return SpellCheck.no("${info.name} is not on an action bar and the magic book is closed")

        val target = info.book
        if (target == null || target == state.book) {
            state.runes.missing(info.runes)?.let { return SpellCheck.no("${info.name} needs ${info.runes.getValue(it)} x $it") }
            return SpellCheck(true, "castable", SpellPlan(info, spellOnBar))
        }

        val from = state.book ?: return SpellCheck.no("${info.name} is on the $target book and the current book cannot be read")
        val swap = swapTo(from, target) ?: return SpellCheck.no("${info.name} is on the $target book and the $from book has no Spellbook Swap to it")
        val bookUnlock = SpellUnlocks.book(target)
        if (!met(bookUnlock, info.abilityId, state)) return SpellCheck.no("the $target book is locked: needs ${bookUnlock.describe()}")
        if (state.magicLevel < swap.level) return SpellCheck.no("${swap.name} needs Magic ${swap.level}, the player has ${state.magicLevel}")
        val swapOnBar = swap.abilityId in state.onBar
        if (!swapOnBar) return SpellCheck.no("${info.name} is on the $target book and ${swap.name} is not on an action bar")
        val runes = HashMap(info.runes).apply { swap.runes.forEach { (rune, n) -> merge(rune, n, Int::plus) } }
        state.runes.missing(runes)?.let { return SpellCheck.no("${info.name} with ${swap.name} needs ${runes.getValue(it)} x $it") }
        return SpellCheck(true, "castable after ${swap.name}", SpellPlan(info, spellOnBar, swap, swapOnBar))
    }

    fun met(unlock: SpellUnlock, abilityId: Int, state: MagicState): Boolean = when (unlock) {
        SpellUnlock.Always -> true
        is SpellUnlock.ShownOnBar -> abilityId in state.onBar
        is SpellUnlock.Both -> met(unlock.first, abilityId, state) && met(unlock.second, abilityId, state)
        else -> state.met(unlock)
    }

    /** The cache packs a destination as plane, x and y in one int. */
    fun unpackCoord(packed: Int): Triple<Int, Int, Int> = Triple(packed ushr 28, (packed ushr 14) and 0x3FFF, packed and 0x3FFF)
}
