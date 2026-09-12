package com.projectx.ui.highlight

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.map.SceneRenderFlags
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen.worldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.pathfinder.WorldCollision
import com.projectx.script.api.localPlayer
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.type.Tile

/**
 * Paints the live global [WorldCollision] (the grid the pathfinder routes on, unified across overworld and
 * dynamic instances) plus the map [RenderFlag] / water data onto the 3D scene, one selectable group per
 * colour. Wall-family flags draw as edges on the exact tile side they sit on; whole-tile groups draw as a
 * translucent fill, nested inward when several apply so overlaps stay legible. Read-only and radius-bounded.
 */
object CollisionDebugRenderer {
    private const val HALF = 250f
    private const val INSET_STEP = 26f
    private const val EDGE_THICKNESS = 3f
    private const val FILL_ALPHA = 0x30

    private val WALL = ImGuiColors.rgba(0, 210, 255, 235)
    private val WALL_CORNER = ImGuiColors.rgba(120, 235, 255, 235)
    private val PROJECTILE = ImGuiColors.rgba(255, 150, 0, 235)
    private val ROUTE = ImGuiColors.rgba(200, 90, 255, 235)

    private val BLOCK_WALK = ImGuiColors.rgba(255, 45, 45, 235)
    private val FLOOR_DECORATION = ImGuiColors.rgba(255, 220, 40, 235)
    private val BLOCK_NPC = ImGuiColors.rgba(60, 220, 90, 235)
    private val BLOCK_PLAYER = ImGuiColors.rgba(255, 60, 200, 235)
    private val COLLISION_ROOF = ImGuiColors.rgba(170, 170, 170, 235)

    private val RENDER_CLIPPED = ImGuiColors.rgba(220, 100, 100, 235)
    private val RENDER_ROOF = ImGuiColors.rgba(210, 130, 40, 235)
    private val RENDER_UNDER_ROOF = ImGuiColors.rgba(190, 170, 90, 235)
    private val RENDER_FORCE_BOTTOM = ImGuiColors.rgba(90, 130, 200, 235)
    private val RENDER_LOWER_OBJECTS = ImGuiColors.rgba(40, 210, 190, 235)
    private val RENDER_FLAG20 = ImGuiColors.rgba(230, 90, 230, 235)
    private val RENDER_FLAG40 = ImGuiColors.rgba(150, 230, 90, 235)
    private val RENDER_FLAG80 = ImGuiColors.rgba(230, 230, 230, 235)
    private val WATER = ImGuiColors.rgba(40, 130, 255, 235)

    private val WALLS = CollisionFlag.WALL_NORTH or CollisionFlag.WALL_EAST or CollisionFlag.WALL_SOUTH or
        CollisionFlag.WALL_WEST or CollisionFlag.WALL_NORTH_EAST or CollisionFlag.WALL_NORTH_WEST or
        CollisionFlag.WALL_SOUTH_EAST or CollisionFlag.WALL_SOUTH_WEST

    fun draw(scope: BackgroundDrawListScope) {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        val radius = UIState.collisionOverlayRadius.value
        val here = localPlayer.tile
        val plane = here.plane

        val walls = UIState.collisionShowWalls.value
        val projectile = UIState.collisionShowProjectile.value
        val route = UIState.collisionShowRouteBlocker.value
        val blockWalk = UIState.collisionShowBlockWalk.value
        val floorDecoration = UIState.collisionShowFloorDecoration.value
        val water = UIState.collisionShowWater.value
        val blockNpc = UIState.collisionShowBlockNpc.value
        val blockPlayer = UIState.collisionShowBlockPlayer.value
        val collisionRoof = UIState.collisionShowRoof.value
        val anyCollision = walls || projectile || route || blockWalk || water || floorDecoration || blockNpc ||
            blockPlayer || collisionRoof

        val clipped = UIState.renderShowClipped.value
        val lowerObjects = UIState.renderShowLowerObjects.value
        val underRoof = UIState.renderShowUnderRoof.value
        val forceBottom = UIState.renderShowForceBottom.value
        val roof = UIState.renderShowRoof.value
        val flag20 = UIState.renderShowFlag20.value
        val flag40 = UIState.renderShowFlag40.value
        val flag80 = UIState.renderShowFlag80.value
        val anyRender = clipped || lowerObjects || underRoof || forceBottom || roof || flag20 || flag40 || flag80

        if (!anyCollision && !anyRender) return
        heightCache.clear()

        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val x = here.x + dx
                val y = here.y + dy
                val tile = Tile.of(x, y, plane)
                var inset = 0f

                if (anyCollision) {
                    val flags = WorldCollision.getFlags(x, y, plane)
                    if (flags != -1 && flags != 0) {
                        if (blockWalk && flags and (CollisionFlag.OBJECT or CollisionFlag.FLOOR) != 0) inset = fill(scope, tile, BLOCK_WALK, inset)
                        if (water && flags and CollisionFlag.FLOOR != 0) inset = fill(scope, tile, WATER, inset)
                        if (floorDecoration && flags and CollisionFlag.FLOOR_DECORATION != 0) inset = fill(scope, tile, FLOOR_DECORATION, inset)
                        if (blockNpc && flags and CollisionFlag.BLOCK_NPCS != 0) inset = fill(scope, tile, BLOCK_NPC, inset)
                        if (blockPlayer && flags and CollisionFlag.BLOCK_PLAYERS != 0) inset = fill(scope, tile, BLOCK_PLAYER, inset)
                        if (collisionRoof && flags and CollisionFlag.ROOF != 0) inset = fill(scope, tile, COLLISION_ROOF, inset)
                        if (projectile) sideEdges(scope, tile, flags, PROJECTILE_SIDES, PROJECTILE)
                        if (route) sideEdges(scope, tile, flags, ROUTE_SIDES, ROUTE)
                        if (walls && flags and WALLS != 0) drawWalls(scope, tile, flags)
                    }
                }

                if (anyRender) {
                    val flags = SceneRenderFlags.renderFlags(x, y, plane)
                    if (clipped && flags and 0x1 != 0) inset = fill(scope, tile, RENDER_CLIPPED, inset)
                    if (lowerObjects && flags and 0x2 != 0) inset = fill(scope, tile, RENDER_LOWER_OBJECTS, inset)
                    if (underRoof && flags and 0x4 != 0) inset = fill(scope, tile, RENDER_UNDER_ROOF, inset)
                    if (forceBottom && flags and 0x8 != 0) inset = fill(scope, tile, RENDER_FORCE_BOTTOM, inset)
                    if (roof && flags and 0x10 != 0) inset = fill(scope, tile, RENDER_ROOF, inset)
                    if (flag20 && flags and 0x20 != 0) inset = fill(scope, tile, RENDER_FLAG20, inset)
                    if (flag40 && flags and 0x40 != 0) inset = fill(scope, tile, RENDER_FLAG40, inset)
                    if (flag80 && flags and 0x80 != 0) inset = fill(scope, tile, RENDER_FLAG80, inset)
                }
            }
        }
    }

    private fun drawWalls(scope: BackgroundDrawListScope, tile: Tile, flags: Int) {
        val cx = tile.x * 512f + 256f
        val cy = tile.y * 512f + 256f
        val plane = tile.plane
        val sw = projected(cx - HALF, cy - HALF, plane)
        val nw = projected(cx - HALF, cy + HALF, plane)
        val ne = projected(cx + HALF, cy + HALF, plane)
        val se = projected(cx + HALF, cy - HALF, plane)

        if (flags and CollisionFlag.WALL_NORTH != 0) edge(scope, nw, ne, WALL)
        if (flags and CollisionFlag.WALL_EAST != 0) edge(scope, ne, se, WALL)
        if (flags and CollisionFlag.WALL_SOUTH != 0) edge(scope, sw, se, WALL)
        if (flags and CollisionFlag.WALL_WEST != 0) edge(scope, sw, nw, WALL)

        if (flags and CollisionFlag.WALL_NORTH_EAST != 0) corner(scope, cx, cy, plane, HALF, HALF)
        if (flags and CollisionFlag.WALL_NORTH_WEST != 0) corner(scope, cx, cy, plane, -HALF, HALF)
        if (flags and CollisionFlag.WALL_SOUTH_EAST != 0) corner(scope, cx, cy, plane, HALF, -HALF)
        if (flags and CollisionFlag.WALL_SOUTH_WEST != 0) corner(scope, cx, cy, plane, -HALF, -HALF)
    }

    private fun sideEdges(scope: BackgroundDrawListScope, tile: Tile, flags: Int, sides: Sides, color: Int) {
        if (flags and sides.any == 0) return
        val cx = tile.x * 512f + 256f
        val cy = tile.y * 512f + 256f
        val plane = tile.plane
        val sw = projected(cx - HALF, cy - HALF, plane)
        val nw = projected(cx - HALF, cy + HALF, plane)
        val ne = projected(cx + HALF, cy + HALF, plane)
        val se = projected(cx + HALF, cy - HALF, plane)
        if (flags and sides.north != 0) edge(scope, nw, ne, color)
        if (flags and sides.east != 0) edge(scope, ne, se, color)
        if (flags and sides.south != 0) edge(scope, sw, se, color)
        if (flags and sides.west != 0) edge(scope, sw, nw, color)
    }

    private fun corner(scope: BackgroundDrawListScope, cx: Float, cy: Float, plane: Int, sx: Float, sy: Float) {
        edge(scope, projected(cx + sx, cy + sy * 0.4f, plane), projected(cx + sx * 0.4f, cy + sy, plane), WALL_CORNER)
    }

    private fun edge(scope: BackgroundDrawListScope, a: Vector2f?, b: Vector2f?, color: Int) {
        if (a == null || b == null) return
        scope.line(a, b, color, EDGE_THICKNESS)
    }

    private fun fill(scope: BackgroundDrawListScope, tile: Tile, color: Int, inset: Float): Float {
        val half = HALF - inset
        if (half <= 30f) return inset
        val cx = tile.x * 512f + 256f
        val cy = tile.y * 512f + 256f
        val plane = tile.plane
        val sw = projected(cx - half, cy - half, plane) ?: return inset
        val nw = projected(cx - half, cy + half, plane) ?: return inset
        val ne = projected(cx + half, cy + half, plane) ?: return inset
        val se = projected(cx + half, cy - half, plane) ?: return inset
        scope.convexPolyFilled(floatArrayOf(sw.x, sw.y, nw.x, nw.y, ne.x, ne.y, se.x, se.y), (color and 0x00FFFFFF) or (FILL_ALPHA shl 24))
        scope.polyLine(floatArrayOf(sw.x, sw.y, nw.x, nw.y, ne.x, ne.y, se.x, se.y, sw.x, sw.y), color, 0, 2f)
        return inset + INSET_STEP
    }

    private fun projected(fineX: Float, fineY: Float, plane: Int): Vector2f? {
        val xi = fineX.toInt()
        val yi = fineY.toInt()
        val key = (plane.toLong() shl 60) or ((xi.toLong() and 0x3FFFFFFF) shl 30) or (yi.toLong() and 0x3FFFFFFF)
        val height = heightCache.getOrPut(key) { HeightMap.fineHeight(plane, xi, yi)?.toFloat() ?: 0f }
        return worldToScreen(Vector3f(fineX, fineY, height))
    }

    private val heightCache = HashMap<Long, Float>()

    private class Sides(val north: Int, val east: Int, val south: Int, val west: Int) {
        val any = north or east or south or west
    }

    private val PROJECTILE_SIDES = Sides(
        CollisionFlag.WALL_NORTH_PROJECTILE_BLOCKER, CollisionFlag.WALL_EAST_PROJECTILE_BLOCKER,
        CollisionFlag.WALL_SOUTH_PROJECTILE_BLOCKER, CollisionFlag.WALL_WEST_PROJECTILE_BLOCKER
    )
    private val ROUTE_SIDES = Sides(
        CollisionFlag.WALL_NORTH_ROUTE_BLOCKER, CollisionFlag.WALL_EAST_ROUTE_BLOCKER,
        CollisionFlag.WALL_SOUTH_ROUTE_BLOCKER, CollisionFlag.WALL_WEST_ROUTE_BLOCKER
    )
}
