package com.projectx.game.magic

import com.projectx.quest.runtime.CacheQuests
import com.projectx.script.api.Spellbook
import com.projectx.script.api.TeleportSpell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellChecksTest {

    private val varrock = SpellInfo(28, 14753, "Varrock Teleport", Spellbook.STANDARD, 25, mapOf(Rune.AIR to 3, Rune.FIRE to 1, Rune.LAW to 1))
    private val trollheim = SpellInfo(57, 14778, "Trollheim Teleport", Spellbook.STANDARD, 61, mapOf(Rune.FIRE to 2, Rune.LAW to 2))
    private val southFalador = SpellInfo(116, 14836, "South Falador Teleport", Spellbook.LUNAR, 72, mapOf(Rune.AIR to 2, Rune.ASTRAL to 2, Rune.LAW to 1))
    private val paddewwa = SpellInfo(83, 14803, "Paddewwa Teleport", Spellbook.ANCIENT, 54, mapOf(Rune.AIR to 1, Rune.FIRE to 1, Rune.LAW to 2))
    private val warsRetreat = SpellInfo(205, 11659, "War's Retreat Teleport", null, 0, emptyMap())
    private val swapToStandard = SpellInfo(197, 6845, "Spellbook Swap (Standard)", Spellbook.LUNAR, 96, mapOf(Rune.ASTRAL to 3, Rune.COSMIC to 2, Rune.LAW to 1))
    private val swapToAncient = SpellInfo(198, 6846, "Spellbook Swap (Ancient)", Spellbook.LUNAR, 96, mapOf(Rune.ASTRAL to 3, Rune.COSMIC to 2, Rune.LAW to 1))

    private fun swaps(from: Spellbook, to: Spellbook): SpellInfo? = when (from to to) {
        Spellbook.LUNAR to Spellbook.STANDARD -> swapToStandard
        Spellbook.LUNAR to Spellbook.ANCIENT -> swapToAncient
        else -> null
    }

    private fun runes(vararg counts: Pair<Rune, Long>, unlimited: Set<Rune> = emptySet()) =
        RuneSupply.of(counts.map { setOf(it.first) to it.second }, unlimited)

    private fun state(
        book: Spellbook? = Spellbook.STANDARD,
        level: Int = 99,
        runes: RuneSupply = runes(Rune.AIR to 100L, Rune.FIRE to 100L, Rune.LAW to 100L, Rune.ASTRAL to 100L, Rune.COSMIC to 100L),
        onBar: Set<Int> = emptySet(),
        bookOpen: Boolean = true,
        met: (SpellUnlock) -> Boolean = { true },
    ) = MagicState(book, level, runes, onBar, bookOpen, met)

    private fun check(spell: TeleportSpell, info: SpellInfo, state: MagicState) = SpellChecks.check(spell, info, state, ::swaps)

    @Test
    fun `a spell on the current book with its runes and level is cast from the book or the bar`() {
        val fromBook = check(TeleportSpell.VARROCK, varrock, state())
        assertTrue(fromBook.usable, fromBook.reason)
        assertEquals(SpellPlan(varrock, fromBar = false), fromBook.plan)
        val fromBar = check(TeleportSpell.VARROCK, varrock, state(onBar = setOf(28), bookOpen = false))
        assertTrue(assertNotNull(fromBar.plan).fromBar)
        assertFalse(fromBar.plan!!.needsSwap)
    }

    @Test
    fun `a spell neither on a bar nor in an open book cannot be clicked`() {
        val check = check(TeleportSpell.VARROCK, varrock, state(bookOpen = false))
        assertFalse(check.usable)
        assertNull(check.plan)
    }

    @Test
    fun `the Magic level, boosts included, has to reach the spell's`() {
        assertFalse(check(TeleportSpell.TROLLHEIM, trollheim, state(level = 60)).usable)
        assertTrue(check(TeleportSpell.TROLLHEIM, trollheim, state(level = 61)).usable)
    }

    @Test
    fun `runes are counted from stacks, combination runes and worn staffs`() {
        val short = check(TeleportSpell.VARROCK, varrock, state(runes = runes(Rune.AIR to 2L, Rune.FIRE to 1L, Rune.LAW to 1L)))
        assertFalse(short.usable)
        assertTrue("Air rune" in short.reason, short.reason)

        val staff = runes(Rune.LAW to 1L, Rune.FIRE to 1L, unlimited = setOf(Rune.AIR))
        assertTrue(check(TeleportSpell.VARROCK, varrock, state(runes = staff)).usable)

        val combination = RuneSupply.of(listOf(setOf(Rune.AIR, Rune.FIRE) to 3L, setOf(Rune.LAW) to 1L), emptySet())
        assertEquals(3L, combination.available(Rune.AIR))
        assertEquals(3L, combination.available(Rune.FIRE))
        assertTrue(check(TeleportSpell.VARROCK, varrock, state(runes = combination)).usable)
        assertNull(combination.missing(mapOf(Rune.AIR to 3, Rune.FIRE to 3)))
        assertEquals(Rune.LAW, combination.missing(mapOf(Rune.LAW to 2)))
    }

    @Test
    fun `a quest or var unlock that is not met keeps the spell locked`() {
        val locked = check(TeleportSpell.TROLLHEIM, trollheim, state(met = { it != SpellUnlock.Quest("Eadgar's Ruse") }))
        assertFalse(locked.usable)
        assertTrue("Eadgar's Ruse" in locked.reason, locked.reason)
        assertTrue(check(TeleportSpell.TROLLHEIM, trollheim, state()).usable)
    }

    @Test
    fun `a spell with no unlock var counts as unlocked only while it sits on an action bar`() {
        val lunar = state(book = Spellbook.LUNAR)
        assertFalse(check(TeleportSpell.SOUTH_FALADOR, southFalador, lunar).usable)
        assertTrue(check(TeleportSpell.SOUTH_FALADOR, southFalador, state(book = Spellbook.LUNAR, onBar = setOf(116))).usable)
        assertTrue(SpellUnlocks.of(TeleportSpell.SOUTH_FALADOR) is SpellUnlock.ShownOnBar)
        assertEquals(SpellUnlock.Always, SpellUnlocks.of(TeleportSpell.VARROCK))
    }

    @Test
    fun `a spell on every book needs no swap`() {
        val check = check(TeleportSpell.WARS_RETREAT, warsRetreat, state(book = Spellbook.LUNAR))
        assertTrue(check.usable, check.reason)
        assertFalse(check.plan!!.needsSwap)
    }

    @Test
    fun `a spell on another book is cast after the Spellbook Swap on a bar, with both sets of runes`() {
        val noSwap = check(TeleportSpell.VARROCK, varrock, state(book = Spellbook.LUNAR))
        assertFalse(noSwap.usable)

        val swapped = check(TeleportSpell.VARROCK, varrock, state(book = Spellbook.LUNAR, onBar = setOf(197)))
        assertTrue(swapped.usable, swapped.reason)
        assertEquals(swapToStandard, swapped.plan!!.swap)
        assertTrue(swapped.plan!!.swapFromBar)

        val runesForSpellOnly = runes(Rune.AIR to 3L, Rune.FIRE to 1L, Rune.LAW to 1L, Rune.ASTRAL to 3L, Rune.COSMIC to 2L)
        assertFalse(check(TeleportSpell.VARROCK, varrock, state(book = Spellbook.LUNAR, onBar = setOf(197), runes = runesForSpellOnly)).usable)

        assertFalse(check(TeleportSpell.VARROCK, varrock, state(book = Spellbook.LUNAR, onBar = setOf(197), level = 95)).usable)
        assertFalse(check(TeleportSpell.VARROCK, varrock, state(book = null, onBar = setOf(197))).usable)
    }

    @Test
    fun `swapping onto the Ancient book needs Desert Treasure`() {
        val withoutQuest = state(book = Spellbook.LUNAR, onBar = setOf(198), met = { it != SpellUnlock.Quest("Desert Treasure") })
        assertFalse(check(TeleportSpell.PADDEWWA, paddewwa, withoutQuest).usable)
        assertTrue(check(TeleportSpell.PADDEWWA, paddewwa, state(book = Spellbook.LUNAR, onBar = setOf(198))).usable)
        assertNull(swaps(Spellbook.STANDARD, Spellbook.ANCIENT))
        assertFalse(check(TeleportSpell.PADDEWWA, paddewwa, state(book = Spellbook.STANDARD, onBar = setOf(198))).usable)
    }

    @Test
    fun `a spell missing from the cache is never castable`() {
        assertFalse(SpellChecks.check(TeleportSpell.VARROCK, null, state(), ::swaps).usable)
    }

    @Test
    fun `the cache's packed destination unpacks to plane, x and y`() {
        assertEquals(Triple(0, 2882, 3668), SpellChecks.unpackCoord(47222356))
        assertEquals(Triple(2, 2933, 4712), SpellChecks.unpackCoord(584929896))
    }

    @Test
    fun `quest names match without case, punctuation or a leading The`() {
        assertTrue(CacheQuests.sameName("The Watchtower", "Watchtower"))
        assertTrue(CacheQuests.sameName("Legends' Quest", "legends quest"))
        assertFalse(CacheQuests.sameName("Plague City", "Plague's End"))
    }
}
