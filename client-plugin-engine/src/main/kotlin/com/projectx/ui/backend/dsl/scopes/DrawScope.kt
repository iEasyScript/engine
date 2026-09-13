package com.projectx.ui.backend.dsl.scopes

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen.worldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.script.api.getXp
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.*
import com.projectx.ui.backend.dsl.utils.Corner
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.util.getLevelForXp
import com.projectx.util.getXpForLevel

@ImGuiDsl.ImGuiDsl
class BackgroundDrawListScope {
    @PublishedApi
    internal val drawCommands = mutableListOf<ImGuiDrawCommand>()

    @JvmOverloads
    fun line(from: Vector2f, to: Vector2f, color: Int, thickness: Float = 1f) {
        drawCommands.add(DrawLineCommand(from.x, from.y, to.x, to.y, color, thickness))
    }
    
    @JvmOverloads
    fun rect(from: Vector2f, to: Vector2f, color: Int, rounding: Float = 0f, thickness: Float = 1f) {
        drawCommands.add(DrawRectCommand(from.x, from.y, to.x, to.y, color, rounding, 0, thickness))
    }
    
    @JvmOverloads
    fun rectFilled(from: Vector2f, to: Vector2f, color: Int, rounding: Float = 0f) {
        drawCommands.add(DrawRectFilledCommand(from.x, from.y, to.x, to.y, color, rounding, 0))
    }
    
    @JvmOverloads
    fun circle(center: Vector2f, radius: Float, color: Int, segments: Int = 0, thickness: Float = 1f) {
        drawCommands.add(DrawCircleCommand(center.x, center.y, radius, color, segments, thickness))
    }
    
    @JvmOverloads
    fun circleFilled(center: Vector2f, radius: Float, color: Int, segments: Int = 0) {
        drawCommands.add(DrawCircleFilledCommand(center.x, center.y, radius, color, segments))
    }
    
    fun text(pos: Vector2f, color: Int, text: String) {
        drawCommands.add(DrawTextCommand(pos.x, pos.y, color, text))
    }

    @JvmOverloads
    fun image(texturePath: String, pos: Vector2f, size: Vector2f, color: Int = -1) {
        drawCommands.add(DrawImageCommand(texturePath, pos.x, pos.y, pos.x + size.x, pos.y + size.y, color))
    }

    @JvmOverloads
    fun image(texture: ImGuiTexture, pos: Vector2f, size: Vector2f, color: Int = -1) {
        drawCommands.add(DrawTextureCommand(texture, pos.x, pos.y, pos.x + size.x, pos.y + size.y, color))
    }

    @JvmOverloads
    fun imageOnTile(tileFine: Vector3f, texture: ImGuiTexture, size: Vector2f, color: Int = -1) {
        val center = worldToScreen(tileFine) ?: return
        val pos = Vector2f(center.x - size.x * 0.5f, center.y - size.y * 0.5f)
        image(texture, pos, size, color)
    }

    fun imageOnTile(tile: Tile, texture: ImGuiTexture, size: Vector2f, heightFine: Float = 0f, color: Int = -1) {
        val tileFine = Vector3f(tile.x * 512f + 256f, tile.y * 512f + 256f, heightFine)
        imageOnTile(tileFine, texture, size, color)
    }

    fun imageOnTile(tile: Tile, texturePath: String, size: Vector2f, heightFine: Float = 0f, color: Int = -1) {
        val tileFine = Vector3f(tile.x * 512f + 256f, tile.y * 512f + 256f, heightFine)
        val center = worldToScreen(tileFine) ?: return
        val pos = Vector2f(center.x - size.x * 0.5f, center.y - size.y * 0.5f)
        image(texturePath, pos, size, color)
    }

    /** Spans an entity's whole [size]x[size] footprint, anchored at its south-west base [tile]. */
    fun tileArea(tile: Tile, size: Int, color: Int) {
        if (size <= 1) {
            tile(tile, color)
            return
        }
        val x0 = tile.x * 512
        val x1 = (tile.x + size) * 512
        val y0 = tile.y * 512
        val y1 = (tile.y + size) * 512
        val tl = areaCorner(tile.plane, x0, y0) ?: return
        val bl = areaCorner(tile.plane, x0, y1) ?: return
        val br = areaCorner(tile.plane, x1, y1) ?: return
        val tr = areaCorner(tile.plane, x1, y0) ?: return

        val filled = floatArrayOf(tl.x, tl.y, bl.x, bl.y, br.x, br.y, tr.x, tr.y)
        convexPolyFilled(filled, (color and 0x00FFFFFF) or 0x19000000)
        polyLine(floatArrayOf(tl.x, tl.y, bl.x, bl.y, br.x, br.y, tr.x, tr.y, tl.x, tl.y), color, 0, 2f)
    }

    private fun areaCorner(plane: Int, fineX: Int, fineY: Int): Vector2f? {
        val z = HeightMap.fineHeight(plane, fineX, fineY)?.toFloat() ?: return null
        return worldToScreen(Vector3f(fineX.toFloat(), fineY.toFloat(), z))
    }

    fun textOnTile(tile: Tile, color: Int, text: String, size: Int = 1, heightFine: Float = Float.NaN, offsetY: Float = -18f) {
        val z = if (heightFine.isNaN()) (HeightMap.fineHeight(tile)?.toFloat() ?: 0f) else heightFine
        val center = worldToScreen(Vector3f(tile.x * 512f + size * 256f, tile.y * 512f + size * 256f, z)) ?: return
        text(Vector2f(center.x - text.length * HALF_CHAR_WIDTH, center.y + offsetY), color, text)
    }

    @JvmOverloads
    fun watermark(
        texture: ImGuiTexture,
        corner: Corner = Corner.BottomRight,
        paddingX: Float = 8f,
        paddingY: Float = 8f,
        alpha: Float = 1f,
        scale: Float = 1f
    ) {
        drawCommands.add(DrawWatermarkCommand(texture, corner, paddingX, paddingY, alpha, scale))
    }

    fun convexPolyFilled(points: FloatArray, color: Int) {
        drawCommands.add(DrawConvexPolyFilledCommand(points, color))
    }

    @JvmOverloads
    fun polyLine(points: FloatArray, color: Int, flags: Int = 0, thickness: Float = 1f) {
        drawCommands.add(DrawPolylineCommand(points, color, flags, thickness))
    }

    fun tile(tileFine: Vector3f, color: Int) {
        val tl = worldToScreen(tileFine.transform(-250f, -250f, 0f)) ?: return
        val bl = worldToScreen(tileFine.transform(-250f, 250f, 0f)) ?: return
        val br = worldToScreen(tileFine.transform(250f, 250f, 0f)) ?: return
        val tr = worldToScreen(tileFine.transform(250f, -250f, 0f)) ?: return

        val filledPoints = floatArrayOf(tl.x, tl.y, bl.x, bl.y, br.x, br.y, tr.x, tr.y)
        val linePoints = floatArrayOf(tl.x, tl.y, bl.x, bl.y, br.x, br.y, tr.x, tr.y, tl.x, tl.y)

        val filledColor = (color and 0x00FFFFFF) or 0x19000000
        convexPolyFilled(filledPoints, filledColor)
        polyLine(linePoints, color, 0, 2f)
    }

    // Tile-based highlight. With no explicit [heightFine] the Z is sampled from the live height map so
    // the marker sits on the real ground; pass a height only when you already have one (e.g. an entity's
    // graph-node fineZ). NaN = "compute it".
    fun tile(tile: Tile, color: Int, heightFine: Float = Float.NaN) {
        val z = if (heightFine.isNaN()) (HeightMap.fineHeight(tile)?.toFloat() ?: 0f) else heightFine
        tile(Vector3f(tile.x * 512.0f + 256.0f, tile.y * 512.0f + 256.0f, z), color)
    }

    // Coordinate forms of the Tile-taking draws above: Tile is an inline value class, so those compile to mangled JVM
    // names Java cannot call.

    /** Highlights tile ([x], [y]) on [plane]. */
    fun tile(x: Int, y: Int, plane: Int, color: Int) = tile(Tile.of(x, y, plane), color)

    /** Spans a [size]x[size] footprint anchored at its south-west tile ([x], [y]) on [plane]. */
    fun tileArea(x: Int, y: Int, plane: Int, size: Int, color: Int) = tileArea(Tile.of(x, y, plane), size, color)

    /** Draws [text] above tile ([x], [y]) on [plane]. */
    fun textOnTile(x: Int, y: Int, plane: Int, color: Int, text: String) = textOnTile(Tile.of(x, y, plane), color, text)

    /** Draws [texture] at [size], centred on tile ([x], [y]) on [plane]. */
    fun imageOnTile(x: Int, y: Int, plane: Int, texture: ImGuiTexture, size: Vector2f) = imageOnTile(Tile.of(x, y, plane), texture, size)

    @JvmOverloads
    fun drawProgressBar(
        x: Float, 
        y: Float, 
        width: Float = 100f, 
        height: Float = 6f,
        progress: Float,
        backgroundColor: Int = 0xFF333333.toInt(),
        progressColor: Int = 0xFF00FF00.toInt(),
        rounding: Float = 2f
    ) {
        rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), backgroundColor, rounding)
        
        if (progress > 0f) {
            val fillWidth = (progress.coerceIn(0f, 1f)) * width
            rectFilled(Vector2f(x, y), Vector2f(x + fillWidth, y + height), progressColor, rounding)
        }
    }

    @JvmOverloads
    fun drawProgressBarWithGradient(
        x: Float, 
        y: Float, 
        width: Float = 100f, 
        height: Float = 6f,
        progress: Float,
        backgroundColor: Int = 0xFF333333.toInt(),
        lowColor: Int = 0xFF0000FF.toInt(),
        medColor: Int = 0xFF03A5FC.toInt(),
        highColor: Int = 0xFF00FF00.toInt(),
        rounding: Float = 2f
    ) {
        val progressColor = when {
            progress >= 0.8f -> highColor
            progress >= 0.5f -> medColor
            else -> lowColor
        }
        drawProgressBar(x, y, width, height, progress, backgroundColor, progressColor, rounding)
    }

    @JvmOverloads
    fun drawXpProgressBar(
        x: Float,
        y: Float, 
        skill: Skill,
        width: Float = 100f,
        height: Float = 6f,
        showPercentage: Boolean = true,
        textColor: Int = 0xFF00FF80.toInt()
    ): Float {
        val currentXp = getXp(skill)
        val currentLevel = getLevelForXp(currentXp)
        val xpForCurrentLevel = getXpForLevel(currentLevel)
        val xpForNextLevel = getXpForLevel(currentLevel + 1)
        val progressXp = currentXp - xpForCurrentLevel
        val totalXpNeeded = xpForNextLevel - xpForCurrentLevel
        val progressPercent = if (totalXpNeeded > 0) progressXp.toFloat() / totalXpNeeded else 0f

        val levelText = if (currentLevel >= 120) "Maxed Level" else "Level $currentLevel${if (showPercentage) " (${(progressPercent * 100).toInt()}%)" else ""}"
        
        text(Vector2f(x, y), textColor, levelText)

        if (currentLevel < 120) {
            val barY = y + 20f
            drawProgressBarWithGradient(x, barY, width, height, progressPercent)
        }
        
        return if (currentLevel < 120) y + 22f else y + 16f
    }

    @JvmOverloads
    fun drawScriptPanel(
        x: Float, 
        y: Float, 
        width: Float, 
        height: Float,
        backgroundColor: Int = 0xAA000000.toInt(),
        borderColor: Int = 0xFF333333.toInt(),
        rounding: Float = 4f,
        borderThickness: Float = 1.5f
    ) {
        rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), backgroundColor, rounding)
        rect(Vector2f(x, y), Vector2f(x + width, y + height), borderColor, rounding, borderThickness)
    }

    @JvmOverloads
    fun drawStatusIndicator(
        x: Float, 
        y: Float, 
        status: String = "RUNNING",
        isActive: Boolean = true,
        activeColor: Int = 0xFF00FF00.toInt(),
        inactiveColor: Int = 0xFF0000FF.toInt(),
        textColor: Int = 0xFF00FF80.toInt()
    ) {
        val statusColor = if (isActive) activeColor else inactiveColor
        circleFilled(Vector2f(x - 8f, y + 7f), 3f, statusColor)
        text(Vector2f(x, y), textColor, "Status: $status")
    }

    private companion object {
        const val HALF_CHAR_WIDTH = 3.5f
    }
}
