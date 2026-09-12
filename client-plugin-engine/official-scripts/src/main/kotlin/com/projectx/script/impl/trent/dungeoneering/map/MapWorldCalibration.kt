package com.projectx.script.impl.trent.dungeoneering.map

import world.gregs.voidps.type.Tile

private const val ROOM_TILES = 16

// Consecutive agreeing pip observations required before we trust (lock) the world↔cell transform.
private const val ESTABLISH_CONSISTENT = 3

/**
 * Maps world rooms ↔ dungeon-map cells for one floor. World X grows east / map X grows right (same sign);
 * world Y grows north / map Y grows down (opposite sign) — fixed for Daemonheim's north-up map. So the only
 * unknown is an offset (cx, cy): `gx = worldRoomX + cx`, `gy = -worldRoomY + cy`.
 *
 * The offset is stable for long stretches but NOT guaranteed constant for the whole floor: the minimap
 * widget re-origins as it reveals rooms, and a teleport can move the world tile without the pip following,
 * either of which shifts the world↔cell offset. So we [observe] pips to ESTABLISH the offset, LOCK it, and
 * derive the current cell from the reliable world tile via [cellFor] (stable — no flicker) — but the reader
 * RE-locks (via `allowRelock`) whenever the locked offset has drifted so far that the player's computed cell
 * no longer lands on any drawn room, feeding a pip that DOES land on a real room. That guard is what makes it
 * safe against a stale post-death pip (then the locked offset still maps the world tile onto a real cell, so
 * no re-lock happens) while still recovering from a genuine drift (the old door ping-pong). A new floor makes
 * a fresh session + calibration, re-establishing from scratch.
 */
class MapWorldCalibration {
    private val xSign = 1
    private val ySign = -1

    private var cx = 0
    private var cy = 0

    var valid: Boolean = false
        private set

    private var pendCx = 0
    private var pendCy = 0
    private var pendCount = 0

    // Force a specific world-room ↔ cell correspondence (a known landmark, e.g. the Smuggler at the start
    // room). Overrides and re-locks the transform.
    fun setAnchor(worldRoom: Pair<Int, Int>, cell: Pair<Int, Int>) {
        cx = cell.first - xSign * worldRoom.first
        cy = cell.second - ySign * worldRoom.second
        valid = true
        pendCount = 0
    }

    // Feed a (worldRoom, pip-cell) observation. Normally, once the transform is locked we IGNORE it — a
    // differing pip is then pure noise (a stale post-death pip, a flicker). But when `allowRelock` is set
    // (the reader passes it only once the locked offset has drifted off the drawn map — see the class KDoc),
    // we accept a fresh offset the same way we established the first one: ESTABLISH_CONSISTENT agreeing
    // observations before committing, so a one- or two-frame flicker can never re-lock us wrong.
    fun observe(worldRoom: Pair<Int, Int>, pipCell: Pair<Int, Int>, allowRelock: Boolean = false) {
        if (valid && !allowRelock) return
        val ocx = pipCell.first - xSign * worldRoom.first
        val ocy = pipCell.second - ySign * worldRoom.second
        if (pendCount > 0 && ocx == pendCx && ocy == pendCy) {
            pendCount++
        } else {
            pendCx = ocx
            pendCy = ocy
            pendCount = 1
        }
        if (pendCount >= ESTABLISH_CONSISTENT) {
            cx = ocx
            cy = ocy
            valid = true
            pendCount = 0
        }
    }

    fun cellFor(tile: Tile): Pair<Int, Int>? {
        if (!valid) return null
        val gx = xSign * (tile.x / ROOM_TILES) + cx
        val gy = ySign * (tile.y / ROOM_TILES) + cy
        if (gx < 0 || gy < 0 || gx > 15 || gy > 15) return null
        return gx to gy
    }

    // Inverse of cellFor (1/sign == sign for ±1). Turns a target cell into the world room a mover walks to.
    fun worldRoomFor(cell: Pair<Int, Int>): Pair<Int, Int>? {
        if (!valid) return null
        return xSign * (cell.first - cx) to ySign * (cell.second - cy)
    }

    fun roomCenterTile(cell: Pair<Int, Int>, plane: Int): Tile? {
        val (roomX, roomY) = worldRoomFor(cell) ?: return null
        return Tile.of(roomX * ROOM_TILES + ROOM_TILES / 2, roomY * ROOM_TILES + ROOM_TILES / 2, plane)
    }

    companion object {
        fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
    }
}
