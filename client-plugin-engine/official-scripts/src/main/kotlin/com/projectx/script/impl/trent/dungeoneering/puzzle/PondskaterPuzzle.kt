package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import world.gregs.voidps.type.Tile

/**
 * The Daemonheim "fishing keys" puzzle: four Pondskaters skate a pool and one holds the door key, surfacing
 * it (mouth-open) now and then. Catching it — with dungeoneering feathers in the pack — fishes the key out.
 * Lives HERE, same isolation as the ice rooms / bosses.
 *
 * Quick gameval-keyed solve: the key holder reads as NPC id 12089 (`rand_fishing_keys_pondskater_1`) for the
 * whole puzzle and flips to 12090 (`..._nokey`) only once the key is retrieved; the decoys (12091/2/3) never
 * hold it. So we shadow the 12089 skater and Catch it, and treat its disappearance (→12090) as solved. (If a
 * floor ever puts the key on a different skater this would key off the mouth-open render seq 14405 instead —
 * noted, not needed here.)
 */
object PondskaterPuzzle {
    // The key holder reads as id 12089 for the whole puzzle; the no-key variant 12090 ONLY appears once the
    // key has been retrieved (the seals then drop). So "a 12089 skater is in the room" IS the unsolved test —
    // when it flips to 12090 the puzzle is done and we fall through to normal navigation. The skaters never
    // despawn, so we must not key off their mere presence.
    private const val KEY_HOLDER = 12089        // rand_fishing_keys_pondskater_1 — key present (unsolved)
    private const val FEATHER = 17796           // rand_feather (dungeoneering)
    private const val ROOM_TILES = 16
    private const val RANGE = 24

    fun present(session: DungeonSession): Boolean {
        val room = roomOf(localPlayer.tile)
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        return allNpcsWithinRange(RANGE) { it.id == KEY_HOLDER && roomOf(it.tile) == room }.isNotEmpty()
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        // No feathers and no way to fish the key here — mark the room bonus so the picker routes around its
        // locked door rather than freezing on it. (Buying feathers from the Smuggler is the proper unlock.)
        if (!inventory.hasItem(FEATHER)) {
            val cell = session.currentCell
            session.ban(cell, "pondskater: no dungeoneering feathers (buy them from the Smuggler)")
            script.delay(400, 110)
            return
        }
        val room = roomOf(localPlayer.tile)
        val holder = allNpcsWithinRange(RANGE) { it.id == KEY_HOLDER && roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }
        if (holder == null) {
            script.delay(150, 40)
            return
        }
        if (holder.interact("Catch")) script.waitUntilNotMoving()
        script.delay(220, 60)
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
