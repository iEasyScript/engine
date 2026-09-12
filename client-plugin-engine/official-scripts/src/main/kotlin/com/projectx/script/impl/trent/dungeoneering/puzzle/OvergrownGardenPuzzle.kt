package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Daemonheim "overgrown garden": vine roots seal the room, and the small flowers set into them are the only
 * gates through to the big flower at the centre. Every flower cycles blue -> purple -> red -> yellow -> blue,
 * the big one at twice the rate, and a small flower may only be chopped while it shares the big flower's
 * current colour. Chop a path in, then Uproot the big flower and the room opens.
 *
 * A morphing flower is a separate scenery state that keeps the colour it is leaving and drops its option, so
 * the big flower's name changes exactly when a stable colour begins: swinging only on a freshly seen change
 * starts at the top of the window and never mistakes a morph for a match. Morphing flowers still count as
 * gates to stand at — every flower in the room morphs at once, and treating that second as "no flowers left"
 * sends the bot walking off the gate it was waiting on.
 *
 * Interactions here need real adjacency, not a route: the vines are dynamic scenery the instance's collision
 * does not carry, so the pathfinder happily "reaches" flowers on the far side of a hedge. Waiting at a gate
 * costs at most a few of the big flower's ~4s windows, since it visits every colour. Matched on cache names
 * and options, so every dungeon theme is recognised.
 */
object OvergrownGardenPuzzle {

    private const val RANGE = 24

    private val FLOWER = Regex("Strange (blue|purple|red|yellow) plant")
    private val ORDER = listOf("blue", "purple", "red", "yellow")

    private var bigFlowerColour: String? = null
    private var flowersLeft = -1
    private val unreached = HashSet<Tile>()
    private val choppedThisWindow = HashSet<Tile>()

    private fun colourOf(obj: SceneObject) = FLOWER.matchEntire(obj.name())?.groupValues?.get(1)

    private fun bigFlower(): SceneObject? =
        objectsInRoom(RANGE).firstOrNull { it.hasOption("Uproot") && colourOf(it) != null }

    /**
     * How many colours the big flower still has to advance before it catches this gate.
     *
     * Both cycle the same four colours in the same order and the big one is strictly faster, so this gap only
     * ever shrinks — which makes it a correct ordering of "soonest to match" WITHOUT depending on the rate
     * ratio being exactly 2:1. Only the ranking is claimed here, never a predicted timestamp, so an inexact
     * ratio costs nothing.
     */
    internal fun coloursUntilMatch(gate: String, big: String): Int =
        (ORDER.indexOf(gate) - ORDER.indexOf(big) + ORDER.size) % ORDER.size

    private fun adjacent(obj: SceneObject): Boolean {
        val turned = obj.rotation.toInt() % 2 != 0
        val width = if (turned) obj.defs.sizeY else obj.defs.sizeX
        val height = if (turned) obj.defs.sizeX else obj.defs.sizeY
        val me = localPlayer.tile
        val gapX = maxOf(obj.tile.x - me.x, me.x - (obj.tile.x + width - 1), 0)
        val gapY = maxOf(obj.tile.y - me.y, me.y - (obj.tile.y + height - 1), 0)
        return maxOf(gapX, gapY) <= 1
    }

    fun present(): Boolean = bigFlower() != null

    suspend fun solve(script: Script) {
        val scene = objectsInRoom(RANGE)
        val big = scene.firstOrNull { it.hasOption("Uproot") && colourOf(it) != null } ?: return
        val colour = colourOf(big) ?: return
        if (colour != bigFlowerColour) {
            bigFlowerColour = colour
            choppedThisWindow.clear()
        }

        if (adjacent(big)) {
            if (big.interact("Uproot")) {
                println("DUNG: overgrown garden - uprooting the big flower")
                script.delayUntil(gaussian(6000L, 1400L)) { bigFlower() == null }
            }
            script.delay(680, 180)
            return
        }

        val flowers = scene.filter { colourOf(it) != null && !it.hasOption("Uproot") }
        if (flowers.size != flowersLeft) {
            flowersLeft = flowers.size
            unreached.clear()
            choppedThisWindow.clear()
        }

        // Every adjacent gate already showing the big flower's colour is fair game, not just one per window:
        // the morph guarantee comes from the big flower still offering Uproot and the gate still offering
        // Chop (both drop their option mid-morph), so a second gate in the same window needs no extra proof
        // and would otherwise idle a full cycle.
        val gate = flowers.firstOrNull {
            adjacent(it) && it.hasOption("Chop") && colourOf(it) == colour && it.tile !in choppedThisWindow
        }
        if (gate != null) {
            chop(script, gate, colour)
            return
        }

        // Nothing cuttable this instant. Stand at whichever gate the big flower reaches SOONEST rather than
        // whichever is nearest, and spend the wait walking there — parking at a near gate that is three
        // colours away is where the 30-50s idles came from.
        val target = flowers
            .filter { it.tile !in unreached }
            .minByOrNull {
                val steps = colourOf(it)?.let { c -> coloursUntilMatch(c, colour) } ?: ORDER.size
                steps * 1000 + it.tile.getDistance(localPlayer.tile)
            }
        if (target == null) {
            approach(script, big)
            return
        }
        if (!adjacent(target)) {
            approach(script, target)
            return
        }
        script.delay(420, 120)
    }

    private suspend fun chop(script: Script, gate: SceneObject, colour: String) {
        val tile = gate.tile
        if (gate.interact("Chop")) {
            choppedThisWindow += tile
            println("DUNG: overgrown garden - big flower $colour, chopping (${tile.x},${tile.y})")
            // Poll a handful of tiles around us rather than rescanning the whole room every 100ms — the gate
            // we just swung at is by definition adjacent.
            script.delayUntil(gaussian(4000L, 900L)) {
                getAllObjectsWithinRange(3).none { it.tile == tile && colourOf(it) != null }
            }
            if (getAllObjectsWithinRange(3).any { it.tile == tile && colourOf(it) != null })
                println("DUNG: overgrown garden - chop at (${tile.x},${tile.y}) did not take")
        }
        script.delay(520, 140)
    }

    // A gate we cannot get one step closer to is walled off from the pocket we are standing in; parking it
    // until the hedge changes is what stops the bot pacing at a dead face while other gates stay untried.
    private suspend fun approach(script: Script, target: SceneObject) {
        val from = localPlayer.tile
        walkTo(target.tile, false)
        script.waitUntilNotMoving()
        if (localPlayer.tile != from) {
            script.delay(420, 120)
            return
        }
        unreached += target.tile
        script.delay(2400, 700)
    }
}
