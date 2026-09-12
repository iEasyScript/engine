package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.Varp
import com.projectx.script.impl.trent.dungeoneering.auto.DungeonNavigator
import com.projectx.script.impl.trent.dungeoneering.map.MapWorldCalibration
import com.projectx.util.gaussian

/**
 * The single shared sensing pipeline for Daemonheim: dungeon session, resource/door/monster scan, map
 * read, and criticality. Both the overlay helper and the auto-solver drive it via [update] and forward
 * events to [onEvent]; a short time-guard means the second caller each window is a no-op, so the world is
 * never scanned twice and the two scripts always see identical state. Nothing here issues game actions
 * except the one-shot door examine (which only READS a requirement).
 */
object DungeonContext {

    var session: DungeonSession? = null
        private set

    val scanner = ResourceScanner()

    private var examineInFlight: DoorKey? = null
    private var examineReply: Chat? = null
    private var tick = 0
    private var lastUpdateMs = 0L

    suspend fun update(script: Script, boostMargin: Int, autoExamine: Boolean, examineSpacingMs: Int) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateMs < MIN_UPDATE_INTERVAL_MS) return
        lastUpdateMs = now

        if (Bootstrap.client.mainState != MainState.LOGGED_IN) {
            scanner.clear()
            return
        }
        scanner.scanKeys()

        val active = session ?: adopt()
        if (active == null || !inInstancedArea) {
            scanner.clear()
            return
        }

        // The wide loc/npc sweep is the only heavy step; the map read and criticality pass are cheap, so run
        // them every update for a responsive map while sampling the world less often.
        if (tick++ % SCAN_EVERY == 0) {
            scanner.scan(active)
            if (autoExamine) examinePump(script, active, examineSpacingMs)
        }
        if (active.reader.mapPresent) active.reader.refresh(localPlayer.tile)
        correctAnchorAtStart(active)
        active.currentCell = active.calibration.cellFor(localPlayer.tile)
        active.currentCell?.let { active.visited.add(it) }
        CriticalityAnalyzer.recompute(active, boostMargin)
    }

    // The Smuggler only ever stands in the START room, so it's a reliable landmark. Whenever it shares our
    // room, trust it over the minimap pip — the pip goes STALE after a death teleport (it stays stuck in the
    // boss room), which otherwise leaves currentCell == boss so the bot thinks it already arrived and freezes
    // instead of walking back to re-fight. Re-anchor our world room to the start cell and correct the cell.
    private fun correctAnchorAtStart(active: DungeonSession) {
        val start = active.map.startCell() ?: return
        val room = MapWorldCalibration.roomOf(localPlayer.tile)
        val smugglerHere = allNpcsWithinRange(20) {
            it.name().equals("Smuggler", ignoreCase = true) && MapWorldCalibration.roomOf(it.tile) == room
        }.isNotEmpty()
        if (!smugglerHere) return
        // Landmark correction: pin the transform to a known-true pair (Smuggler's room ↔ start cell). Fixes a
        // transform that established wrong, and re-locks after a floor's fresh re-establish.
        active.calibration.setAnchor(room, start.gx to start.gy)
    }

    fun onEvent(event: Event) {
        when (event) {
            is Varp -> if (event.id == RAND_SEED_VARP) {
                when {
                    event.newValue == 0 -> { session = null; scanner.clear() }
                    // Idempotent: both consumers forward the same event — only a real seed change re-sessions.
                    session?.seed != event.newValue -> newSession(event.newValue)
                }
            }
            is Chat -> if (examineInFlight != null &&
                DungeonTables.DOOR_REQUIREMENT_MESSAGE.containsMatchIn(event.message)
            ) {
                examineReply = event
            }
        }
    }

    private fun adopt(): DungeonSession? {
        if (!inInstancedArea) return null
        val seed = varps.getVar(RAND_SEED_VARP)
        if (seed == 0) return null
        return newSession(seed)
    }

    // Doors written off as refused are a per-floor judgement; carrying them into the next floor blacklists
    // tiles that belong to a different dungeon entirely.
    private fun newSession(seed: Int): DungeonSession {
        DungeonNavigator.forgetRefusedDoors()
        return DungeonSession(seed).also { session = it }
    }

    private suspend fun examinePump(script: Script, active: DungeonSession, examineSpacingMs: Int) {
        val target = scanner.skillDoors.firstOrNull { active.doors.shouldExamine(it.key) } ?: return
        val door = getAllObjectsWithinRange(40).firstOrNull {
            it.id == target.key.locId && it.tile.x == target.key.x && it.tile.y == target.key.y
        } ?: return

        active.doors.markAttempt(target.key)
        examineReply = null
        examineInFlight = target.key
        door.interact(EXAMINE_OP)
        script.waitForEvent(gaussian(3500L, 700L)) {
            it is Chat && DungeonTables.DOOR_REQUIREMENT_MESSAGE.containsMatchIn(it.message)
        }
        val reply = examineReply
        examineInFlight = null
        if (reply != null) {
            parseRequirement(reply.message, target.skill)?.let { active.doors.record(target.key, it) }
            active.map.dirty = true
        }
        script.delay(examineSpacingMs, 400)
    }

    private fun parseRequirement(message: String, knownSkill: Skill?): DoorRequirement? {
        val match = DungeonTables.DOOR_REQUIREMENT_MESSAGE.find(message) ?: return null
        val level = match.groupValues[1].toIntOrNull() ?: return null
        val skill = DungeonTables.skillByName(match.groupValues[2]) ?: knownSkill
        return DoorRequirement(skill, level, message)
    }

    private const val RAND_SEED_VARP = 1088
    private const val EXAMINE_OP = 5
    private const val SCAN_EVERY = 3
    private const val MIN_UPDATE_INTERVAL_MS = 120L
}
