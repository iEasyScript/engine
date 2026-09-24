package com.projectx.ui.compose

import androidx.compose.runtime.snapshots.Snapshot
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.compose.hud.HudHost

/**
 * Draws one frame of the overlay, render thread only: the world layer and script cards the frame's commands describe,
 * then the main panel over them.
 */
object OverlayHost {
    private var wasDown = false

    fun drawFrame(commands: List<ImGuiCommand>) {
        OverlayModels.prepare()
        HudHost.update(commands)
        Snapshot.sendApplyNotifications()
        OverlayClock.tick()

        val down = NativeBridge.isMouseDown(0)
        // A click on the game ends typing into any of the overlay's fields.
        if (down && !wasDown && !ComposeOverlay.hovered && !HudHost.hovered) OverlayKeyboard.blur()
        wasDown = down

        val wheel = OverlayInput.mouseWheel()
        HudHost.drawFrame(wheel)
        ComposeOverlay.drawFrame(wheel)
    }

    fun dispose() {
        HudHost.dispose()
        ComposeOverlay.dispose()
    }
}
