package com.projectx.script.impl.trent.dungeoneering.map

import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.api.interfaces
import world.gregs.voidps.type.Tile
import kotlin.math.abs

private const val MAP_INTERFACE = 942
private const val DRAW_LAYER = 8
private const val ICON_CELL_MAX = 20
private const val BASE_CELL_MIN = 28

class DungeonMapReader(
    private val model: DungeonMapModel,
    private val calibration: MapWorldCalibration
) {

    var playerCell: Pair<Int, Int>? = null
        private set

    val mapPresent: Boolean
        get() = interfaces.getComponentRaw(MAP_INTERFACE, DRAW_LAYER) != null

    fun refresh(playerTile: Tile) {
        val layer = interfaces.getComponentRaw(MAP_INTERFACE, DRAW_LAYER) ?: return
        val children = layer.slotChildren
        if (children.isEmpty()) return

        val layerRect = layer.screenRect
        if (layerRect != null) model.cachedRect = layerRect
        model.debugChildren.clear()
        val pips = ArrayList<Pair<Int, Int>>()
        for (child in children) {
            val relX = child.parentRelX
            val relY = child.parentRelY
            if (relX < 0 || relY < 0) continue
            val gx = relX / DungeonMapModel.CELL_PX
            val gy = relY / DungeonMapModel.CELL_PX
            if (gx > 15 || gy > 15) continue

            if (MapIcons.roomOpenings(child.graphicId) == null) {
                model.debugChildren += "$gx,$gy s${child.graphicId} w${child.screenWidth} i${child.itemId}"
            }
            if (child.graphicId in MapIcons.PLAYER_PIPS) {
                pips += gx to gy
                continue
            }
            if (classify(child, gx, gy)) model.dirty = true

            if (layerRect != null && relX >= DungeonMapModel.CELL_PX) {
                val childRect = child.screenRect
                if (childRect != null) {
                    val scale = (childRect.x - layerRect.x).toFloat() / relX
                    if (scale > 0.25f && scale < 8f) model.uiScale = scale
                }
            }
        }

        anchorToPip(pips, playerTile)
    }

    // Feed the local pip to the calibration to ESTABLISH the world↔cell transform, but derive the current
    // cell from the reliable world tile — not the raw pip, which flickers/goes stale (post-death) and would
    // otherwise make current_cell jump. Until the transform locks, cellFor returns null (we simply wait).
    //
    // The transform can DRIFT mid-floor (the minimap re-origins as it reveals rooms; a teleport can move the
    // world tile without the pip). The tell is that the player's computed cell no longer lands on any drawn
    // room even though the player is standing in one (every entered room is drawn) — so when that happens we
    // re-lock onto a pip that DOES land on a real room. A stale post-death pip can't trigger this: there the
    // locked offset still maps the world tile onto a real cell, so `onDrawnRoom` stays true and we hold.
    private fun anchorToPip(pips: List<Pair<Int, Int>>, playerTile: Tile) {
        val pip = localPlayerPip(pips)
        if (pip != null) {
            val room = MapWorldCalibration.roomOf(playerTile)
            if (!calibration.valid) {
                calibration.observe(room, pip)
            } else if (!onDrawnRoom(calibration.cellFor(playerTile)) && onDrawnRoom(pip)) {
                calibration.observe(room, pip, allowRelock = true)
            }
        }
        playerCell = calibration.cellFor(playerTile)
    }

    private fun onDrawnRoom(cell: Pair<Int, Int>?): Boolean {
        val (gx, gy) = cell ?: return false
        return model.get(gx, gy)?.occupied == true
    }

    private fun localPlayerPip(pips: List<Pair<Int, Int>>): Pair<Int, Int>? = when {
        pips.isEmpty() -> null
        pips.size == 1 -> pips.first()
        else -> playerCell?.let { prev -> pips.minByOrNull { abs(it.first - prev.first) + abs(it.second - prev.second) } }
            ?: pips.first()
    }

    private fun classify(child: InterfaceComponent, gx: Int, gy: Int): Boolean {
        val item = child.itemId
        val graphic = child.graphicId
        val width = child.screenWidth
        val cell = model.cell(gx, gy)
        val before = state(cell)

        if (item != -1 && MapIcons.isKeyObj(item)) {
            cell.keyDoorObjId = item
            cell.occupied = true
        } else if (width in 1 until ICON_CELL_MAX) {
            MapIcons.skillForGraphic(graphic)?.let {
                cell.skillDoorSkill = it
                cell.occupied = true
            }
        } else if (width >= BASE_CELL_MIN) {
            when {
                // The exact full-cell START/BOSS icon (id 2831/2833, width >= a full cell) is authoritative and
                // sticky: the boss room renders its icon AND its decoded room shape together, so gating on
                // !openingsKnown would let the shape hide the boss. Set the flag unconditionally and keep any
                // decoded openings — they're useful for routing to the boss.
                graphic == MapIcons.START -> { cell.start = true; cell.occupied = true; cell.unknownRoom = false }
                graphic == MapIcons.BOSS -> { cell.boss = true; cell.occupied = true; cell.unknownRoom = false }
                // A "?" room's graphic encodes only the single door it was revealed through. Keep that as
                // partial evidence (not authoritative) so neighbours can add the sides we haven't seen.
                graphic in MapIcons.UNKNOWN_ROOMS -> {
                    cell.unknownRoom = true
                    cell.occupied = true
                    cell.openingsKnown = false
                    MapIcons.unknownRoomOpenings(graphic)?.let { cell.openings = it }
                }
                graphic != 0 -> {
                    cell.baseGraphicId = graphic
                    cell.occupied = true
                    cell.unknownRoom = false
                    MapIcons.roomOpenings(graphic)?.let {
                        cell.openings = it
                        cell.openingsKnown = true
                    }
                }
            }
        }
        return state(cell) != before
    }

    private fun state(cell: MapCell): Long {
        var bits = 0L
        if (cell.occupied) bits = bits or 1
        if (cell.start) bits = bits or 2
        if (cell.boss) bits = bits or 4
        if (cell.unknownRoom) bits = bits or 16
        bits = bits or ((cell.skillDoorSkill?.ordinal ?: 63).toLong() shl 8)
        bits = bits or ((cell.keyDoorObjId.toLong() and 0xFFFFF) shl 16)
        bits = bits or ((cell.openings.toLong() and 0xF) shl 40)
        if (cell.openingsKnown) bits = bits or (1L shl 44)
        return bits
    }
}
