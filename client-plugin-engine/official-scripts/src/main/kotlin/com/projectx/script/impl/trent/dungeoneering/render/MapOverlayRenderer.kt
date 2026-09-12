package com.projectx.script.impl.trent.dungeoneering.render

import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.script.api.interfaces
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.script.impl.trent.dungeoneering.map.DoorClass
import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapModel
import com.projectx.script.impl.trent.dungeoneering.map.RoomClass
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private const val MAP_INTERFACE = 942
private const val DRAW_LAYER = 8

object MapOverlayRenderer {

    fun draw(scope: BackgroundDrawListScope, session: DungeonSession, debug: Boolean) {
        val layer = interfaces.getComponentRaw(MAP_INTERFACE, DRAW_LAYER)
        val rect = layer?.screenRect?.also { session.map.cachedRect = it } ?: session.map.cachedRect ?: return
        val cellPx = DungeonMapModel.CELL_PX * session.map.uiScale
        val origin = Vector2f(rect.x.toFloat(), rect.y.toFloat())

        for (cell in session.map.all) {
            val topLeft = origin.transform(cell.gx * cellPx, cell.gy * cellPx)
            val bottomRight = topLeft.transform(cellPx, cellPx)

            // Off-path rooms are the only rooms we shade — critical/normal rooms are left as the plain map.
            if (cell.roomClass == RoomClass.LIKELY_BONUS || cell.roomClass == RoomClass.CONFIRMED_BONUS) {
                scope.rectFilled(topLeft, bottomRight, DungeonColors.BONUS_SHADE)
            }

            // Only mark doors that are in the way (red) or need a boost (yellow + level). Doors you can pass
            // now aren't boxed — the GO marker already calls those out, and a green box would just linger.
            when (cell.doorClass) {
                DoorClass.BOOSTABLE -> { scope.rect(topLeft, bottomRight, DungeonColors.DOOR_BOOSTABLE, 0f, 2f); levelTag(scope, topLeft, DungeonColors.DOOR_BOOSTABLE, cell.doorLevel) }
                DoorClass.IMPOSSIBLE -> { scope.rect(topLeft, bottomRight, DungeonColors.DOOR_IMPOSSIBLE, 0f, 2f); levelTag(scope, topLeft, DungeonColors.DOOR_IMPOSSIBLE, cell.doorLevel) }
                DoorClass.NEED_KEY -> scope.rect(topLeft, bottomRight, DungeonColors.DOOR_IMPOSSIBLE, 0f, 2f)
                else -> {}
            }
        }

        session.map.goTarget?.let { go ->
            val topLeft = origin.transform(go.gx * cellPx, go.gy * cellPx)
            val center = topLeft.transform(cellPx / 2f, cellPx / 2f)
            scope.circle(center, cellPx / 2f - 2f, DungeonColors.RECOMMENDED, 0, 3f)
            scope.text(topLeft.transform(2f, -12f), DungeonColors.RECOMMENDED, "GO: ${go.reason}")
        }

        if (debug) drawDebug(scope, session, rect)
        drawLegend(scope, rect)
    }

    private fun levelTag(scope: BackgroundDrawListScope, topLeft: Vector2f, color: Int, level: Int) {
        if (level <= 0) return
        scope.text(topLeft.transform(2f, 1f), color, "$level")
    }

    private fun drawDebug(scope: BackgroundDrawListScope, session: DungeonSession, rect: ScreenRect) {
        val map = session.map
        val pip = session.currentCell?.let { "${it.first},${it.second}" } ?: "none"
        val unknown = map.all.count { it.unknownRoom }
        val bonus = map.all.count { it.roomClass == RoomClass.LIKELY_BONUS || it.roomClass == RoomClass.CONFIRMED_BONUS }
        val go = map.goTarget?.let { "${it.gx},${it.gy}" } ?: "none"
        val lines = ArrayList<String>()
        lines += "pip=$pip valid=${session.calibration.valid} rooms=${map.all.size} unk=$unknown bonus=$bonus go=$go"
        lines += map.debugChildren.take(40)
        var y = rect.y + rect.height + 4f
        for (line in lines) {
            scope.text(Vector2f(rect.x.toFloat(), y), ImGuiColors.WHITE, line)
            y += 11f
        }
    }

    private fun drawLegend(scope: BackgroundDrawListScope, rect: ScreenRect) {
        val rows = buildList {
            add(DungeonColors.RECOMMENDED to "GO: head here next")
            add(DungeonColors.DOOR_BOOSTABLE to "Skill door: boost to pass")
            add(DungeonColors.DOOR_IMPOSSIBLE to "Locked: can't pass yet")
            add(DungeonColors.BONUS_SHADE to "Off-path (skip)")
        }
        val width = 176f
        val height = rows.size * 15f + 8f
        val x = if (rect.x - width - 6f >= 4f) rect.x - width - 6f else rect.x + rect.width + 6f
        val y = rect.y.toFloat()
        scope.rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), ImGuiColors.OVERLAY_DARK, 3f)
        var rowY = y + 5f
        for ((color, label) in rows) {
            scope.rectFilled(Vector2f(x + 6f, rowY + 2f), Vector2f(x + 13f, rowY + 9f), color)
            scope.text(Vector2f(x + 18f, rowY), ImGuiColors.WHITE, label)
            rowY += 15f
        }
    }
}
