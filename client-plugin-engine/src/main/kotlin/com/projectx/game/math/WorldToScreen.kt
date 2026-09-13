package com.projectx.game.math

import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.ui.backend.native.NativeBridge

object WorldToScreen {
    private const val VARC_VIEW_OFF_X = 3005
    private const val VARC_VIEW_OFF_Y = 3006
    private const val VARC_VIEW_W = 3001
    private const val VARC_VIEW_H = 3002

    private data class ViewportInfo(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private fun getViewportInfo(): ViewportInfo {
        val varcs = Bootstrap.client.clientVarDomain
        val viewX = varcs.getVar(VARC_VIEW_OFF_X).coerceAtLeast(0)
        val viewY = varcs.getVar(VARC_VIEW_OFF_Y).coerceAtLeast(0)
        var viewW = varcs.getVar(VARC_VIEW_W)
        var viewH = varcs.getVar(VARC_VIEW_H)

        if (viewW <= 0 || viewH <= 0) {
            val (dw, dh) = NativeBridge.getDisplaySize()
            viewW = dw.toInt()
            viewH = dh.toInt()
        }

        return ViewportInfo(viewX, viewY, viewW, viewH)
    }

    @JvmStatic
    fun worldToScreen(worldFine: Vector3f): Vector2f? {
        val projMatrix = Bootstrap.client.sceneManager.currentWorld?.projectionMatrix ?: return null

        if (projMatrix.size != 16) return null

        val clipW = projMatrix[3] * worldFine.x + projMatrix[7] * worldFine.z + projMatrix[11] * worldFine.y + projMatrix[15]

        if (clipW <= 0.0f) return null

        val clipX = (projMatrix[0] * worldFine.x + projMatrix[4] * worldFine.z + projMatrix[8] * worldFine.y + projMatrix[12]) / clipW
        val clipY = (projMatrix[1] * worldFine.x + projMatrix[5] * worldFine.z + projMatrix[9] * worldFine.y + projMatrix[13]) / clipW

        val viewport = getViewportInfo()

        val cx = viewport.width / 2.0f
        val cy = viewport.height / 2.0f

        val screenX = viewport.x + (clipX * cx) - clipX + cx
        val screenY = viewport.y + -(clipY * cy) + clipY + cy

        return Vector2f(screenX, screenY)
    }

    fun getEstimatedTileCenter(tile: Tile, heightFine: Float = 0f) = getEstimatedTileCenter(Vector3f((tile.x.toFloat() + 0.5f) * 512.0f, (tile.y.toFloat() + 0.5f) * 512.0f, heightFine))

    @JvmStatic
    fun getEstimatedTileCenter(pointFine: Vector3f): Vector2f? {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return null
        return try {
            worldToScreen(pointFine)
        } catch (_: Throwable) { null }
    }
}