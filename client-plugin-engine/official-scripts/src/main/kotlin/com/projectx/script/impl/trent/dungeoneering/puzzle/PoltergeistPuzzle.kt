package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.Script
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Daemonheim "ghost with key" (Poltergeist) puzzle: open the sarcophagus to unlock the room's door while the
 * poltergeist (Leif) patrols as a harmless distraction. Mechanic (RE'd live):
 *
 *  1. **Read the sarcophagus** — a message dialogue (1186) reads e.g. "…honoured with the discovery of
 *     *slaughtercress*." → that names the herb this room wants.
 *  2. **Harvest the herb patch** — opens the "Select a herb." list (interface 720) of the six dungeon herbs;
 *     click the row whose name matches the inscription. That yields the grimy herb.
 *  3. **Consecrate** it (Prayer), **add a herb to each of the four censers** and **light** them (tinderbox).
 *  4. **Read/Open the coffin** — it opens and the door unlocks.
 *
 * Driven off live loc OPTIONS (theme-independent): censer "Add herb" (empty) → "Light" (loaded) → lit; coffin
 * "Read"/"Open" until opened. The correct herb is cached per floor (re-read if the seed changes).
 */
object PoltergeistPuzzle {
    private const val CENSER = "Censer"
    private const val COFFIN = "Sarcophagus"
    private const val PATCH = "Herb patch"
    private const val ADD_HERB = "Add herb"
    private const val LIGHT = "Light"
    private const val HARVEST = "Harvest"
    private const val READ = "Read"
    private const val OPEN = "Open"
    private const val MSG_DIALOG = 1186
    private const val MSG_TEXT = 3
    private const val MSG_CONTINUE = 8
    private const val HERB_SELECT = 720
    private const val RANGE = 16
    private val RAW_HERBS = (19654..19658).toSet()   // rand_ghostkey_herb1..5 — harvested, need consecrating
    private const val BLESSED_HERB = 19659           // rand_ghostkey_herb_blessed — consecrated, goes in a censer
    private val INSCRIPTION = Regex("discovery of (\\w+)", RegexOption.IGNORE_CASE)

    private var wantHerb: String? = null
    private var wantSeed = -1

    private fun censers() = objectsInRoom(RANGE).filter { it.name() == CENSER }
    private fun coffin() = objectsInRoom(RANGE).firstOrNull { it.name() == COFFIN }
    private fun rawHerb() = inventory.firstOrNull { it.id in RAW_HERBS }

    fun present(session: DungeonSession): Boolean {
        if (session.currentCell != null && session.currentCell in session.bannedCells) return false
        val c = censers()
        if (c.isEmpty()) return false
        return c.any { it.hasOption(ADD_HERB) || it.hasOption(LIGHT) } ||
            (coffin()?.let { it.hasOption(OPEN) || it.hasOption(READ) } == true)
    }

    suspend fun solve(session: DungeonSession, script: Script) {
        if (session.seed != wantSeed) {
            wantSeed = session.seed
            wantHerb = null
        }

        // A. Herb-select list is up → pick the row whose name matches the inscription, via the engine's
        // dialogue-option map (it resolves each row's IFSlot; a hand-picked component won't select it). If we
        // don't know the herb yet (a stale list from before we read the coffin), read the coffin — that cancels
        // the list and tells us the herb.
        if (interfaces.isOpen(HERB_SELECT)) {
            val herb = wantHerb
            if (herb != null) continueDialogueContaining(herb) else readSarcophagus(script)
            script.delayUntil(gaussian(2000L, 500L)) { rawHerb() != null || !interfaces.isOpen(HERB_SELECT) }
            script.delay(420, 110)
            return
        }

        // B. Sarcophagus message dialogue is up → learn the herb from it, then dismiss.
        if (interfaces.isOpen(MSG_DIALOG)) {
            interfaces.getComponent(MSG_DIALOG, MSG_TEXT)?.text?.let { txt ->
                INSCRIPTION.find(txt)?.let { m -> wantHerb = m.groupValues[1].replaceFirstChar { it.uppercase() } }
            }
            IFSlot(MSG_DIALOG, MSG_CONTINUE, -1).dialogueContinue()
            script.delayUntil(gaussian(1600L, 400L)) { !interfaces.isOpen(MSG_DIALOG) }
            script.delay(420, 110)
            return
        }

        val censers = censers()

        // C. Light any censer that already holds a herb.
        censers.filter { it.hasOption(LIGHT) }.minByOrNull { it.tile.getDistance(localPlayer.tile) }?.let { censer ->
            approach(script, censer.tile)
            if (censer.interact(LIGHT)) {
                script.waitUntilNotMoving()
                script.delayUntil(gaussian(2000L, 500L)) { censers().none { it.tile.x == censer.tile.x && it.tile.y == censer.tile.y && it.hasOption(LIGHT) } }
            }
            script.delay(620, 160)
            return
        }

        // D. Fill an empty censer. Chain: read sarcophagus → harvest+select the named herb → consecrate it →
        // add the blessed herb to a censer. One step per loop; the interface branches (A/B) handle the dialogs.
        val empty = censers.filter { it.hasOption(ADD_HERB) }
        if (empty.isNotEmpty()) {
            if (wantHerb == null) {
                readSarcophagus(script)
                return
            }
            rawHerb()?.let { herb ->
                if (herb.click("Consecrate"))
                    script.delayUntil(gaussian(1600L, 400L)) { inventory.hasItem(BLESSED_HERB) }
                return
            }
            if (inventory.hasItem(BLESSED_HERB)) {
                val censer = empty.minByOrNull { it.tile.getDistance(localPlayer.tile) }!!
                approach(script, censer.tile)
                if (censer.interact(ADD_HERB)) {
                    script.waitUntilNotMoving()
                    script.delayUntil(gaussian(2000L, 500L)) { !inventory.hasItem(BLESSED_HERB) }
                }
                script.delay(620, 160)
                return
            }
            harvest(script)
            return
        }

        // E. All censers lit → open the coffin; the door unlocks.
        coffin()?.let { cof ->
            approach(script, cof.tile)
            if (cof.interact(if (cof.hasOption(OPEN)) OPEN else READ)) script.waitUntilNotMoving()
            script.delay(820, 220)
        }
    }

    private suspend fun readSarcophagus(script: Script) {
        val cof = coffin() ?: return
        approach(script, cof.tile)
        if (cof.interact(READ)) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2200L, 500L)) { interfaces.isOpen(MSG_DIALOG) }
        }
        script.delay(560, 150)
    }

    private suspend fun harvest(script: Script) {
        val patch = objectsInRoom(RANGE).firstOrNull { it.name() == PATCH && it.hasOption(HARVEST) } ?: return
        approach(script, patch.tile)
        if (patch.interact(HARVEST)) {
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(2200L, 500L)) { interfaces.isOpen(HERB_SELECT) || rawHerb() != null }
        }
        script.delay(560, 150)
    }

    private suspend fun approach(script: Script, tile: Tile) {
        if (tile.getDistance(localPlayer.tile) <= 1) return
        walkTo(tile, false)
        script.waitUntilNotMoving()
    }
}
