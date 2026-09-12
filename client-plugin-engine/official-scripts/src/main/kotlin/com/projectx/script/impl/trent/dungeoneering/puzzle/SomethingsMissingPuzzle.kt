package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian

/**
 * Daemonheim "Something's missing" room: three combat-triangle statues, two armed and one empty. Mine the
 * crumbling wall for a stone block, carve it into the weapon matching the EMPTY statue's own style (melee→sword,
 * ranged→bow, magic→staff), and Arm the statue. Carving via the explicit right-click option makes the exact
 * weapon directly (no make-menu, no combat interruption). Ids span every dungeon theme.
 */
object SomethingsMissingPuzzle {
    private const val BLOCK = 17415

    private val WALL_IDS = setOf(39900, 49647, 49648, 49649, 53991)
    private val MELEE_EMPTY = setOf(11036, 11037, 11038, 12094, 13057)
    private val RANGED_EMPTY = setOf(11039, 11040, 11041, 12095, 13058)
    private val MAGIC_EMPTY = setOf(11042, 11043, 11044, 12096, 13059)
    private val EMPTY = MELEE_EMPTY + RANGED_EMPTY + MAGIC_EMPTY

    private const val SWORD = 17416
    private const val BOW = 17418
    private const val STAFF = 17420

    private fun emptyStatue(): NPC? = npcsInRoom(RANGE) { it.id in EMPTY }.firstOrNull()

    private fun weaponFor(id: Int): Pair<String, Int>? = when (id) {
        in MELEE_EMPTY -> "Carve sword" to SWORD
        in RANGED_EMPTY -> "Carve bow" to BOW
        in MAGIC_EMPTY -> "Carve staff" to STAFF
        else -> null
    }

    private const val RANGE = 16

    fun present(): Boolean =
        emptyStatue() != null && objectsInRoom(RANGE).any { it.id in WALL_IDS }

    suspend fun solve(script: Script) {
        val statue = emptyStatue() ?: return
        val (carveOption, weapon) = weaponFor(statue.id) ?: return

        if (inventory.hasItem(weapon)) {
            if (statue.interact("Arm")) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(2000L, 500L)) { !inventory.hasItem(weapon) }
            }
            script.delay(620, 160)
            return
        }

        inventory.firstOrNull { it.id == BLOCK }?.let { block ->
            if (block.click(carveOption))
                script.delayUntil(gaussian(3000L, 700L)) { inventory.hasItem(weapon) }
            return
        }

        val wall = objectsInRoom(RANGE).firstOrNull { it.id in WALL_IDS && it.hasOption("Mine") } ?: return
        if (wall.tile.getDistance(localPlayer.tile) > 2) {
            walkTo(wall.tile, false)
            script.waitUntilNotMoving()
        }
        if (wall.interact("Mine"))
            script.delayUntil(gaussian(4000L, 900L)) { inventory.hasItem(BLOCK) }
    }
}
