package com.projectx.ui.tabs

import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*

object CollisionDebugTab {
    fun ChildScope.render() {
        textWrapped(
            "Paints the live pathfinder collision and map render flags on the 3D scene. Works the same in " +
                "the overworld and inside instances."
        )

        section("Overlay")
        properties("collision-overlay") {
            row("Enabled") { checkbox("##collisionEnabled", UIState.collisionOverlayEnabled) }
            row("Radius") { sliderInt("##collisionRadius", UIState.collisionOverlayRadius, 4, 48) }
        }

        section("Collision flags")
        checkboxGrid(
            "collision-flags",
            listOf(
                "Walls" to UIState.collisionShowWalls,
                "Block walk" to UIState.collisionShowBlockWalk,
                "No-floor / blocked ground" to UIState.collisionShowWater,
                "Floor decoration" to UIState.collisionShowFloorDecoration,
                "Projectile blockers" to UIState.collisionShowProjectile,
                "Route blockers" to UIState.collisionShowRouteBlocker,
                "Block NPCs" to UIState.collisionShowBlockNpc,
                "Block players" to UIState.collisionShowBlockPlayer,
                "Roof" to UIState.collisionShowRoof,
            )
        )

        section("Render flags (tile settings byte)")
        checkboxGrid(
            "render-flags",
            listOf(
                "Clipped / blocked (0x1)" to UIState.renderShowClipped,
                "Bridge / lower-objects (0x2)" to UIState.renderShowLowerObjects,
                "Under roof (0x4)" to UIState.renderShowUnderRoof,
                "Force lowest level (0x8)" to UIState.renderShowForceBottom,
                "Roof (0x10)" to UIState.renderShowRoof,
                "Flag 0x20" to UIState.renderShowFlag20,
                "Flag 0x40" to UIState.renderShowFlag40,
                "Flag 0x80" to UIState.renderShowFlag80,
            )
        )
    }
}
