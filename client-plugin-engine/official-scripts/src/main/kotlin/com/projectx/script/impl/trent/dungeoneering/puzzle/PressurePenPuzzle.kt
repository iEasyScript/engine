package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Pressure-pen (pressure-pad) puzzle: five coloured ferrets must each stand on their matching-colour
 * "<Colour> pressure plate" to unlock the room's door. A ferret exposes a "Scare" option and flees directly
 * AWAY from the player when scared; standing on the far side of the ferret (opposite its plate) and scaring it
 * drives it across — and locks it onto — the plate. Once every plate holds its ferret the door opens on its
 * own and the navigator crosses. The door itself only reads as "Enter", so the scanner would otherwise just
 * force it forever; this owns the room and places the ferrets instead.
 */
object PressurePenPuzzle {
    private const val RANGE = 24

    private fun plates() = objectsInRoom(RANGE).filter { it.name().endsWith("pressure plate") }
    private fun ferrets() = npcsInRoom(RANGE) { it.name().endsWith("ferret") && it.hasOption("Scare") }
    private fun colour(name: String) = name.substringBefore(' ')
    private fun ferretFor(colour: String): NPC? = ferrets().firstOrNull { colour(it.name()) == colour }
    private fun onTile(npc: NPC, t: Tile) = npc.tile.x == t.x && npc.tile.y == t.y

    fun present(): Boolean = plates().any { plate ->
        val ferret = ferretFor(colour(plate.name()))
        ferret != null && !onTile(ferret, plate.tile)
    }

    suspend fun solve(script: Script) {
        for (plate in plates()) {
            val ferret = ferretFor(colour(plate.name())) ?: continue
            if (onTile(ferret, plate.tile)) continue
            herd(script, ferret, plate.tile)
            return
        }
    }

    private suspend fun herd(script: Script, ferret: NPC, plate: Tile) {
        val ft = ferret.tile
        val stepX = (plate.x - ft.x).coerceIn(-1, 1)
        val stepY = (plate.y - ft.y).coerceIn(-1, 1)
        val behind = Tile.of(ft.x - stepX, ft.y - stepY, ft.plane)
        if (localPlayer.tile.x != behind.x || localPlayer.tile.y != behind.y) {
            walkTo(behind, false)
            script.waitUntilNotMoving()
        }
        val from = ferret.tile
        if (ferret.interact("Scare")) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2200L, 600L)) { ferret.tile.x != from.x || ferret.tile.y != from.y }
        }
        script.delay(560, 150)
    }
}
