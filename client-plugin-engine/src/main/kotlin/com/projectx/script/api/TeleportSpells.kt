package com.projectx.script.api

import com.projectx.game.magic.CastResult
import com.projectx.game.magic.SpellCatalogue
import com.projectx.game.magic.SpellTeleports
import com.projectx.script.Script
import world.gregs.voidps.type.Tile

/** The three spellbooks a player can be on. */
enum class Spellbook { STANDARD, ANCIENT, LUNAR }

/**
 * The teleport spells the web walker can route with, keyed by their ability id: the key of the spell in the game's
 * magic ability list, which is also its slot in the magic book. Name, Magic level, spellbook, runes and destination
 * are read from the game cache.
 */
enum class TeleportSpell(val abilityId: Int) {
    SOUTH_FELDIP_HILLS(19),
    TAVERLEY(207),
    VARROCK(28),
    WENDLEWICK(260),
    LUMBRIDGE(31),
    FALADOR(34),
    CAMELOT(39),
    ARDOUGNE(45),
    WATCHTOWER(50),
    TROLLHEIM(57),
    GOD_WARS_DUNGEON(170),
    MAZCAB(191),
    ANACHRONIA(203),
    NORTH_WESTERN_ANACHRONIA(253),
    EASTERN_ANACHRONIA(254),
    NORTHERN_LOST_GROVE(255),
    PADDEWWA(83),
    SENNTISTEN(86),
    KHARYRLL(89),
    LASSAR(92),
    DAREEYAK(95),
    CARRALLANGAR(98),
    ANNAKARL(101),
    GHORROCK(104),
    MOONCLAN(111),
    OURANIA(113),
    SOUTH_FALADOR(116),
    WATERBIRTH(117),
    BARBARIAN(121),
    NORTH_ARDOUGNE(123),
    KHAZARD(127),
    WESTERN_KHARAZI_JUNGLE(251),
    MOUNTAIN_CAMP(252),
    FISHING_GUILD(137),
    CATHERBY(140),
    ICE_PLATEAU(143),
    TROLLHEIM_FARM(148),
    WARS_RETREAT(205),
    SKELETAL_HORROR(200),
    KANDARIN_MONASTERY(201),
    MANOR_FARM(202),
    ;

    /** The name the game shows, e.g. "Varrock Teleport". */
    val spellName: String get() = SpellCatalogue.info(abilityId)?.name ?: name

    val magicLevel: Int get() = SpellCatalogue.info(abilityId)?.level ?: 0

    /** The book the spell is cast from; null when it is on every book. */
    val spellbook: Spellbook? get() = SpellCatalogue.info(abilityId)?.book

    /** Where the game's own data says the spell lands, or null when it does not say. */
    val destination: Tile? get() = SpellCatalogue.destination(abilityId)

    companion object {
        @JvmStatic
        fun byAbilityId(abilityId: Int): TeleportSpell? = entries.firstOrNull { it.abilityId == abilityId }

        /** The spell named [name], with or without its " Teleport", ignoring case. */
        @JvmStatic
        fun named(name: String): TeleportSpell? {
            val wanted = name.trim().removeSuffix(" Teleport").removeSuffix(" teleport")
            return entries.firstOrNull { it.spellName.removeSuffix(" Teleport").equals(wanted, ignoreCase = true) }
        }
    }
}

/** The spellbook the player is on, or null on Dungeoneering's or when it cannot be read. */
val currentSpellbook: Spellbook? get() = SpellTeleports.currentBook()

/**
 * Whether [spell] can be cast now: unlocked, the Magic level (boosts count), the runes from the backpack, rune pouches
 * and worn staffs, and the spell on an action bar or the magic book open. A spell on another book also needs that
 * book's Spellbook Swap on an action bar, with its runes on top.
 */
fun canCastTeleportSpell(spell: TeleportSpell): Boolean = SpellTeleports.check(spell).usable

/** Why [spell] cannot be cast now, or null when it can. */
fun teleportSpellProblem(spell: TeleportSpell): String? = SpellTeleports.check(spell).takeUnless { it.usable }?.reason

/**
 * Casts [spell], swapping spellbook first when it is on another book, and waits for the landing and its animation.
 * False when it cannot be cast, the cast never started, or the player did not land.
 */
suspend fun Script.castTeleportSpell(spell: TeleportSpell): Boolean {
    val from = localPlayer.tile
    if (SpellTeleports.cast(this, spell) { stopped } != CastResult.STARTED) return false
    return SpellTeleports.awaitLanding(this, from) { stopped }
}
