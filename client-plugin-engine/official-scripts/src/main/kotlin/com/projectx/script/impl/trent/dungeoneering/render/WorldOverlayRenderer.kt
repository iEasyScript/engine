package com.projectx.script.impl.trent.dungeoneering.render

import org.projectx.core.game.skill.Skill
import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.script.impl.trent.dungeoneering.DungeonTables
import com.projectx.script.impl.trent.dungeoneering.ResourceScanner
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import world.gregs.voidps.type.Tile
import kotlin.math.sin

data class WorldOverlayOptions(
    val resources: Boolean,
    val labels: Boolean,
    val glowSlayer: Boolean
)

object WorldOverlayRenderer {

    fun draw(
        scope: BackgroundDrawListScope,
        session: DungeonSession?,
        scanner: ResourceScanner,
        options: WorldOverlayOptions
    ) {
        drawGroundKeys(scope, scanner)
        if (session == null) return

        if (options.resources) {
            drawResources(scope, session, scanner, options.labels)
            drawSkillDoors(scope, session, scanner, options.labels)
            drawKeyDoors(scope, scanner, options.labels)
            drawGuardianDoors(scope, scanner, options.labels)
        }
        if (options.glowSlayer) drawSlayerNpcs(scope, session, options.labels)
    }

    private fun drawResources(
        scope: BackgroundDrawListScope,
        session: DungeonSession,
        scanner: ResourceScanner,
        labels: Boolean
    ) {
        for (resource in scanner.resources) {
            val color = tierColor(resource.criticalTier, resource.attainable, DungeonColors.forSkill(resource.skill))
            drawTile(scope, resource.tile, resource.fine, color)
            if (labels) {
                label(scope, resource.tile, resource.fine, "${resource.name} ${resource.level}", color)
            }
        }
    }

    private fun tierColor(criticalTier: Boolean, attainable: Boolean, skillColor: Int): Int = when {
        criticalTier -> skillColor
        !attainable -> DungeonColors.UNATTAINABLE
        else -> DungeonColors.OFF_TIER
    }

    private fun drawSkillDoors(
        scope: BackgroundDrawListScope,
        session: DungeonSession,
        scanner: ResourceScanner,
        labels: Boolean
    ) {
        for (door in scanner.skillDoors) {
            val requirement = session.doors.requirement(door.key)
            val level = requirement?.level
            val color = when {
                level == null -> DungeonColors.UNKNOWN
                session.attainable(requirement.skill ?: door.skill, level) -> DungeonColors.ATTAINABLE
                else -> DungeonColors.UNATTAINABLE
            }
            drawTile(scope, door.tile, door.fine, color)
            if (labels) {
                val skillName = (requirement?.skill ?: door.skill).name.lowercase().replaceFirstChar { it.uppercase() }
                label(scope, door.tile, door.fine, "$skillName ${level?.toString() ?: "?"}", color)
            }
        }
    }

    private fun drawKeyDoors(scope: BackgroundDrawListScope, scanner: ResourceScanner, labels: Boolean) {
        for (door in scanner.keyDoors) {
            val color = if (door.openable) DungeonColors.ATTAINABLE else DungeonColors.UNATTAINABLE
            drawTile(scope, door.tile, door.fine, color)
            if (labels) {
                val tag = if (door.openable) "open now" else "need key"
                label(scope, door.tile, door.fine, "${door.name} · $tag", color)
            }
        }
    }

    private fun drawGuardianDoors(scope: BackgroundDrawListScope, scanner: ResourceScanner, labels: Boolean) {
        for (door in scanner.guardianDoors) {
            val color = if (door.monstersAlive) DungeonColors.UNATTAINABLE else DungeonColors.ATTAINABLE
            drawTile(scope, door.tile, door.fine, color)
            if (labels) {
                val tag = if (door.monstersAlive) "kill monsters" else "clear - enter"
                label(scope, door.tile, door.fine, "Guardian door · $tag", color)
            }
        }
    }

    private fun drawGroundKeys(scope: BackgroundDrawListScope, scanner: ResourceScanner) {
        if (scanner.groundKeys.isEmpty()) return
        val pulse = ((sin(System.currentTimeMillis() / 180.0) + 1.0) / 2.0).toFloat()
        for (key in scanner.groundKeys) {
            scope.tile(key.tile, DungeonColors.KEY)
            val center = tileCenterScreen(key.tile) ?: continue
            scope.circle(center, 10f + 8f * pulse, DungeonColors.KEY_RING, 0, 3f)
            scope.circle(center, 18f + 10f * pulse, DungeonColors.KEY, 0, 2f)
            scope.text(center.transform(-30f, -30f - 6f * pulse), DungeonColors.KEY_RING, key.name)
        }
    }

    private fun drawSlayerNpcs(scope: BackgroundDrawListScope, session: DungeonSession, labels: Boolean) {
        val entryLevel = session.entryLevel(Skill.SLAYER)
        val monsters = allNpcsWithinRange(40) { DungeonTables.slayerLevel(it.name()) != null }
        for (npc in monsters) {
            val level = DungeonTables.slayerLevel(npc.name()) ?: continue
            val criticalTier = DungeonTables.isCriticalTier(Skill.SLAYER, level, entryLevel)
            val color = tierColor(criticalTier, level <= entryLevel, DungeonColors.forSkill(Skill.SLAYER))
            EntityHighlight.apply(npc, color and 0xFFFFFF, EntityHighlight.Mode.OUTLINE, 24)
            if (labels) {
                label(scope, npc.tile, null, "${npc.name()} · Slayer $level", color)
            }
        }
    }

    private fun drawTile(scope: BackgroundDrawListScope, tile: Tile, fine: Vector3f?, color: Int) {
        if (fine != null) scope.tile(fine, color) else scope.tile(tile, color)
    }

    private fun label(scope: BackgroundDrawListScope, tile: Tile, fine: Vector3f?, text: String, color: Int) {
        val anchor = fine ?: fineCenter(tile) ?: return
        val screen = WorldToScreen.getEstimatedTileCenter(anchor) ?: return
        scope.text(screen.transform(0f, -16f), color, text)
    }

    private fun tileCenterScreen(tile: Tile): Vector2f? {
        val fine = fineCenter(tile) ?: return null
        return WorldToScreen.getEstimatedTileCenter(fine)
    }

    private fun fineCenter(tile: Tile): Vector3f? {
        val height = HeightMap.fineHeight(tile)?.toFloat() ?: 0f
        return Vector3f(tile.x * 512f + 256f, tile.y * 512f + 256f, height)
    }
}
