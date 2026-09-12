package com.projectx.ui.highlight

import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.spotAnimLabel
import com.projectx.scene.SceneSnapshot
import com.projectx.scene.SceneSnapshot.ItemView
import com.projectx.scene.SceneSnapshot.NpcView
import com.projectx.scene.SceneSnapshot.ObjectView
import com.projectx.scene.SceneSnapshot.Scene
import com.projectx.scene.SceneSnapshot.SimpleView
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.gameval.Gameval

/**
 * Paints scene entities - locs, NPCs, spot anims, projectiles and ground items - onto the 3D scene as a
 * tile marker plus a stack of label lines, bounded by [UIState.entityRange].
 */
object EntityOverlayRenderer {
    private const val LINE_HEIGHT = 13f
    private const val FIRST_LINE = -26f

    val enabled: Boolean
        get() = UIState.showSceneObjects.value || UIState.showNpcs.value || UIState.showSpotAnims.value ||
            UIState.showProjectiles.value || UIState.showGroundItems.value || UIState.showClickboxes.value

    fun draw(scope: BackgroundDrawListScope) = with(scope) {
        val labelColor = ImGuiColors.rgba(
            UIState.entityTextColorR.value,
            UIState.entityTextColorG.value,
            UIState.entityTextColorB.value,
            UIState.entityTextColorA.value
        )
        val range = UIState.entityRange.value
        val scene = SceneSnapshot.collect(range)

        if (UIState.showSceneObjects.value) scene.objects.forEach { drawObject(it, labelColor) }
        if (UIState.showNpcs.value) drawNpcsInRange(scene, range, labelColor)
        if (UIState.showSpotAnims.value) scene.spotAnims.forEach { drawSimple(it, labelColor) }
        if (UIState.showProjectiles.value) scene.projectiles.forEach { drawSimple(it, labelColor) }
        if (UIState.showGroundItems.value) drawGroundItems(scene, labelColor)
        if (UIState.showClickboxes.value) drawClickboxes(scene)
    }

    private fun BackgroundDrawListScope.drawObject(obj: ObjectView, color: Int) {
        val fine = obj.fine.toVector()
        val center = WorldToScreen.getEstimatedTileCenter(fine) ?: return
        tile(fine, color)
        labelStack(center, color, buildList {
            add("${obj.name} (${obj.id})")
            if (obj.typeId != obj.id) add("TypeId: ${obj.typeId}")
            add("Shape: ${obj.shape}")
        })
    }

    private fun BackgroundDrawListScope.drawNpcsInRange(scene: Scene, range: Int, color: Int) {
        scene.npcs.forEach { npc ->
            val dx = npc.tileX - scene.playerTileX
            val dy = npc.tileY - scene.playerTileY
            if (dx * dx + dy * dy <= range * range) drawNpc(npc, color)
        }
    }

    private fun BackgroundDrawListScope.drawNpc(npc: NpcView, color: Int) {
        val fine = npc.fine.toVector()
        val center = WorldToScreen.getEstimatedTileCenter(fine) ?: return
        tile(fine, color)
        labelStack(center, color, buildList {
            add("${npc.name} (${npc.id})")
            if (npc.currentHealth > 0 || npc.maxHealth > 0) add("Health: ${npc.currentHealth}/${npc.maxHealth}")
            if (npc.typeId != npc.id) add("TypeId: ${npc.typeId}")
            if (npc.animationId > 0) add("Anim: ${Gameval.seqLabel(npc.animationId)}")
            if (npc.hiddenMenuOpFlags > 0) add("Menu-flags: ${npc.hiddenMenuOpFlags}")
        })
    }

    private fun BackgroundDrawListScope.drawSimple(view: SimpleView, color: Int) {
        val fine = view.fine.toVector()
        val center = WorldToScreen.getEstimatedTileCenter(fine) ?: return
        tile(fine, color)
        text(center.transform(0f, FIRST_LINE), color, spotAnimLabel(view.id))
    }

    /** Items sharing a tile stack their labels upward so a pile stays readable. */
    private fun BackgroundDrawListScope.drawGroundItems(scene: Scene, color: Int) {
        val linesPerTile = HashMap<Long, Int>()
        scene.items.forEach { item ->
            val fine = item.fine.toVector()
            val center = WorldToScreen.getEstimatedTileCenter(fine) ?: return@forEach
            val key = item.tileX.toLong() shl 20 or item.tileY.toLong()
            val line = linesPerTile.getOrDefault(key, 0)
            if (line == 0) tile(fine, color)
            text(center.transform(0f, FIRST_LINE - line * LINE_HEIGHT), color, item.label())
            linesPerTile[key] = line + 1
        }
    }

    private fun BackgroundDrawListScope.drawClickboxes(scene: Scene) {
        val color = ImGuiColors.rgba(
            UIState.clickboxColorR.value,
            UIState.clickboxColorG.value,
            UIState.clickboxColorB.value,
            1f
        )
        scene.objects.forEach { tile(it.fine.toVector(), color) }
        scene.items.forEach { tile(it.fine.toVector(), color) }
    }

    private fun BackgroundDrawListScope.labelStack(center: Vector2f, color: Int, lines: List<String>) {
        lines.forEachIndexed { index, line ->
            text(center.transform(0f, FIRST_LINE + index * LINE_HEIGHT), color, line)
        }
    }

    private fun ItemView.label() =
        if (amount > 1) "$name ($id) x$amount" else "$name ($id)"

    private fun SceneSnapshot.FineCoord.toVector() = Vector3f(x, y, z)
}
