package com.projectx.markers

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.nxt.MainState
import com.projectx.quest.editor.TilePickResolver
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowPos
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowSize
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.button
import com.projectx.ui.backend.dsl.scopes.styleColor
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender

/**
 * The tile tool's input layer. While [TileMarkerState.toolActive] is on it draws a
 * fullscreen, invisible host window whose body is one transparent button — so ImGui owns
 * the mouse (the native SDL hook drops those clicks before the game sees them: no walking)
 * and routes clicks correctly (the button only fires over the world, not the tab). A
 * left-click toggles a marker on the hovered tile in the active group.
 */
object TileMarkerInputSurface {
    private val HOST_FLAGS =
        WindowFlags.NoTitleBar + WindowFlags.NoResize + WindowFlags.NoMove +
            WindowFlags.NoScrollbar + WindowFlags.NoCollapse + WindowFlags.NoBackground +
            WindowFlags.NoSavedSettings + WindowFlags.NoBringToFrontOnFocus + WindowFlags.NoFocusOnAppearing

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            if (!isLoggedIn() || !TileMarkerState.toolActive.value) return

            val (dw, dh) = NativeBridge.getDisplaySize()
            setNextWindowPos(0f, 0f, ImGuiCond.Always)
            setNextWindowSize(dw, dh, ImGuiCond.Always)
            window("##tilemarker-capture", HOST_FLAGS) {
                styleColor(ImGuiCol.Button, ImGuiColors.TRANSPARENT) {
                    styleColor(ImGuiCol.ButtonHovered, ImGuiColors.TRANSPARENT) {
                        styleColor(ImGuiCol.ButtonActive, ImGuiColors.TRANSPARENT) {
                            button("##tilemarker-hit", dw, dh) { toggleAtCursor() }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            println("[TileMarkerInputSurface] render error: ${t.message}")
        }
    }

    private fun toggleAtCursor() {
        val (mx, my) = NativeBridge.getMousePos()
        val hit = TilePickResolver.resolve(mx, my) ?: return
        TileMarkerStore.toggleTile(activeGroup(), hit.tileX, hit.tileY, hit.plane, TileMarkerState.activeColor())
    }

    private fun activeGroup(): String {
        val current = TileMarkerState.activeGroup.value
        if (current.isNotBlank() && TileMarkerStore.groups().any { it.name == current }) return current
        TileMarkerStore.addGroup("Default", TileMarkerState.activeColor())
        TileMarkerState.activeGroup.value = "Default"
        return "Default"
    }

    private fun isLoggedIn(): Boolean = try {
        Bootstrap.client.mainState == MainState.LOGGED_IN
    } catch (_: Throwable) { false }
}
