package com.projectx.game.math

import com.projectx.game.math.WorldToScreen.worldToScreen
import com.projectx.game.nxt.entity.Entity

/**
 * A tight screen-space rect for an entity, computed by projecting its 8 world-AABB
 * corners (from GraphNode bounds the engine itself maintains for culling and
 * picking) and taking min/max. No mesh chasing, no stale-pointer risk.
 *
 * Used by the overlay layer to draw a customizable outline around any entity.
 */
data class Clickbox(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}

val Entity.clickbox: Clickbox?
    get() {
        val gn = graphNode
        if (!gn.hasBounds) return null
        val mn = gn.boundsMin
        val mx = gn.boundsMax

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false

        for (i in 0 until 8) {
            val cx = if ((i and 1) == 0) mn.x else mx.x
            val cy = if ((i and 2) == 0) mn.y else mx.y
            val cz = if ((i and 4) == 0) mn.z else mx.z
            val sp = worldToScreen(Vector3f(cx, cy, cz)) ?: continue
            any = true
            if (sp.x < minX) minX = sp.x
            if (sp.y < minY) minY = sp.y
            if (sp.x > maxX) maxX = sp.x
            if (sp.y > maxY) maxY = sp.y
        }

        if (!any) return null
        // Reject degenerate rects (entirely off-screen or zero area).
        if (maxX - minX < 1f || maxY - minY < 1f) return null
        return Clickbox(minX, minY, maxX, maxY)
    }
