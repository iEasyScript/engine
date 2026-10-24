package com.projectx.script.api

import com.projectx.game.interfaces.Bank.Companion.doBankInventoryAction
import com.projectx.game.nxt.entity.SpotAnim
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

private const val DEFAULT_RANGE = 24
private const val ROCK_SLACK = 2
private const val MINE = "Mine"
private const val FILL = "Fill"
private const val HEADBAR_MINING_STAMINA = 5

/** The bank's backpack offers an ore box's first bank-side option, "Empty - ore", in this slot. */
private const val BANK_EMPTY_ORE = 8

private val ROCKERTUNITY_IDS = intArrayOf(7164, 7165)
private val ORE_BOX = Regex(".* ore box")

/** The ore box's count of each ore, by ore item id; every tier of box shares the one store. */
private val ORE_BOX_VARBITS = mapOf(
    436 to 43188, 438 to 43190, 440 to 43192, 453 to 43194, 442 to 43196, 447 to 43198, 449 to 43200,
    44820 to 43202, 444 to 43204, 451 to 43206, 44822 to 43208, 44824 to 43210, 44826 to 43212, 44828 to 43214,
    21778 to 43216, 44830 to 43218, 44832 to 43220,
    57175 to 55880, 57177 to 55883, 57179 to 55886, 57181 to 55889, 57183 to 55892, 57185 to 55895,
    57187 to 55898, 57189 to 55901, 57191 to 55904, 57193 to 55907,
)

/** The nearest rockertunity within [range] tiles, or null when none is up. */
@JvmOverloads
fun findRockertunity(range: Int = DEFAULT_RANGE): SpotAnim? = findClosestSpotAnim(range) { ROCKERTUNITY_IDS.contains(it.id) }

/** The tile of the nearest rockertunity within [range], or null when none is up. */
@JvmOverloads
fun rockertunityTile(range: Int = DEFAULT_RANGE): Tile? = findRockertunity(range)?.tile

/** The minable rock the nearest rockertunity sits on that [isRock] accepts, or null when none is up. */
@JvmOverloads
fun findRockertunityRock(range: Int = DEFAULT_RANGE, isRock: (SceneObject) -> Boolean): SceneObject? {
    val tile = rockertunityTile(range) ?: return null
    return findClosestObjectToTile(tile, ROCK_SLACK) { it.hasOption(MINE) && isRock(it) }
}

/**
 * Stamina left on the headbar's 0-255 scale, or -1 while no rock is being worked. Unlike [miningStamina], whose
 * ceiling grows with level, this is comparable at any level. Java: `getMiningStaminaBar`.
 */
val miningStaminaBar: Int get() = localPlayer.headbarFill(HEADBAR_MINING_STAMINA)

/** True when an ore box of any tier is in the backpack. */
fun hasOreBox(): Boolean = inventory.hasItem(ORE_BOX)

/** How many of [oreId] the ore box holds; 0 for anything it never stores. */
fun oreBoxCount(oreId: Int): Int = ORE_BOX_VARBITS[oreId]?.let { varps.getVarBit(it) } ?: 0

/**
 * Fills the ore box from the backpack and waits for the ore to leave it. False when there is no box, or nothing
 * moved because the box is full or doesn't take that ore. Java: `Wait.fillOreBox`.
 */
suspend fun Script.fillOreBox(): Boolean {
    if (!hasOreBox()) return false
    val free = inventory.freeSlots
    if (!inventory.clickItem(ORE_BOX, FILL)) return false
    waitThenDelayUntil(gaussian(300L, 2_500L), gaussian(2400L, 90_000L)) { inventory.freeSlots > free }
    return inventory.freeSlots > free
}

/** Empties the ore box into the open bank. False when the bank isn't open or there is no box. */
fun emptyOreBoxIntoBank(): Boolean = bankOpen && doBankInventoryAction(ORE_BOX, BANK_EMPTY_ORE)
