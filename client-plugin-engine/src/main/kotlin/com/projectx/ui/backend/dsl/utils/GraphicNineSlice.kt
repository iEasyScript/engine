package com.projectx.ui.backend.dsl.utils

import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.getTexture
import world.gregs.voidps.cache.Cache

/**
 * Minimal nine-slice descriptor backed by cache graphics.
 * Width/height come from the decoded graphic (not the texture), which we then draw via ImGui.
 */
data class GraphicPiece(val id: Int, val texture: ImGuiTexture, val width: Int, val height: Int)

data class GraphicNineSlice(
    val topLeft: GraphicPiece,
    val top: GraphicPiece,
    val topRight: GraphicPiece,
    val left: GraphicPiece,
    val center: GraphicPiece,
    val right: GraphicPiece,
    val bottomLeft: GraphicPiece,
    val bottom: GraphicPiece,
    val bottomRight: GraphicPiece
) {
    companion object {
        @JvmStatic
        fun fromGraphicIds(
            topLeft: Int,
            top: Int,
            topRight: Int,
            left: Int,
            center: Int,
            right: Int,
            bottomLeft: Int,
            bottom: Int,
            bottomRight: Int
        ): GraphicNineSlice {
            fun load(id: Int): GraphicPiece {
                val s = Cache.graphic(id) ?: error("Graphic $id doesn't exist.")
                val tex = s.getTexture()
                return GraphicPiece(id, tex, s.maxWidth, s.maxHeight)
            }

            return GraphicNineSlice(
                topLeft = load(topLeft),
                top = load(top),
                topRight = load(topRight),
                left = load(left),
                center = load(center),
                right = load(right),
                bottomLeft = load(bottomLeft),
                bottom = load(bottom),
                bottomRight = load(bottomRight)
            )
        }
    }
}
