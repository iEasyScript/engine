package com.projectx.script.impl.trent.dungeoneering.map

import org.projectx.core.game.skill.Skill
import com.projectx.game.nxt.interfaces.ScreenRect

enum class RoomClass { CONFIRMED_CRITICAL, LIKELY_CRITICAL, UNKNOWN, LIKELY_BONUS, CONFIRMED_BONUS }

enum class DoorClass { NONE, OPEN_NOW, REACHABLE, BOOSTABLE, NEED_KEY, IMPOSSIBLE, UNKNOWN }

// Where to head next. gx/gy may be an UNDRAWN cell (the empty space just past an unopened door) when the
// player is already standing in the frontier room — so the marker points onward, never at your own room.
class GoTarget(val gx: Int, val gy: Int, val reason: String)

class MapCell(val gx: Int, val gy: Int) {
    var baseGraphicId = -1
    var openings = 0
    // True only for a fully-drawn room whose door layout is exactly known from its graphic. Start/boss and
    // not-yet-entered "?" rooms are partial: a missing door on their side is NOT proof of no passage, so a
    // neighbour's decoded door is allowed to establish the connection.
    var openingsKnown = false
    var occupied = false
    var start = false
    var boss = false
    var unknownRoom = false
    var skillDoorSkill: Skill? = null
    var keyDoorObjId = -1
    var doorLevel = -1
    var roomClass = RoomClass.UNKNOWN
    var onCriticalPath = false
    var doorClass = DoorClass.NONE
}

class DungeonMapModel {
    private val cells = HashMap<Int, MapCell>()
    var dirty = false
    var uiScale = 1f
    var bossFound = false
    var goTarget: GoTarget? = null
    // Debug-only: raw summary of every non-plain-room child seen last scan (pips, icons, "?" rooms).
    val debugChildren = ArrayList<String>()
    // The dungeon map is a static HUD widget whose per-frame screen rect only refreshes when it
    // redraws; cache the last live rect so an unfreshened frame doesn't blank the overlay.
    var cachedRect: ScreenRect? = null

    val all: Collection<MapCell> get() = cells.values

    fun cell(gx: Int, gy: Int): MapCell = cells.getOrPut(key(gx, gy)) { MapCell(gx, gy) }

    fun get(gx: Int, gy: Int): MapCell? = cells[key(gx, gy)]

    fun startCell(): MapCell? = cells.values.firstOrNull { it.start }

    fun bossCell(): MapCell? = cells.values.firstOrNull { it.boss }

    fun resetCriticality() {
        goTarget = null
        for (c in cells.values) {
            c.roomClass = RoomClass.UNKNOWN
            c.onCriticalPath = false
            c.doorLevel = -1
            c.doorClass = DoorClass.NONE
        }
    }

    private fun key(gx: Int, gy: Int) = (gy shl 8) or (gx and 0xFF)

    companion object {
        const val CELL_PX = 32
    }
}
