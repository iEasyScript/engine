package com.projectx.ui

import com.projectx.game.hooks.Priority
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.ui.compose.OverlayModels
import com.projectx.ui.highlight.CollisionDebugRenderer
import com.projectx.ui.highlight.EntityOverlayRenderer
import com.projectx.util.EngineLog

/**
 * The overlay's main-logic half: world overlays drawn on the scene, and the data the panel shows, gathered where
 * the game state can be read safely. The panel itself is drawn on the render thread by the Compose host.
 */
object UI {
    init {
        EngineLog.install()
    }

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            if (EntityOverlayRenderer.enabled) backgroundDrawList { EntityOverlayRenderer.draw(this) }
            if (UIState.collisionOverlayEnabled.value) backgroundDrawList { CollisionDebugRenderer.draw(this) }
            OverlayModels.refresh()
        } catch (t: Throwable) {
            println("Error in overlay render: ${t.message}")
            t.printStackTrace()
        }
    }
}
