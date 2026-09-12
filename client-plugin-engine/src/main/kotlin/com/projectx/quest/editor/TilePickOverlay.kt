package com.projectx.quest.editor

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.MainState
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender

/**
 * Background-drawlist overlay rendered while the quest editor's tile-pick mode
 * is active. Draws a magenta cross-hair on the tile under the cursor, prints
 * (x, y, plane, fineZ), and a "Pick / Esc" hint. Consumes the next left-click
 * to capture the tile, offering it to [QuestEditorState.offerPick] as a
 * [PickKind.TILE] result.
 */
object TilePickOverlay {
    private const val PICK_COLOR = 0xFFFF00FF.toInt() // magenta
    private const val PICK_FILL = 0x44FF00FF
    @Volatile private var lastClickMs: Long = 0L
    private const val CLICK_DEBOUNCE_MS = 250L

    @JvmStatic
    @ImGUIRender(priority = Priority.HIGH)
    fun render() {
        try {
            if (!QuestEditorState.pickMode.value) return
            if (PickKind.TILE !in QuestEditorState.pendingAccepts) return
            if (!isLoggedIn()) return

            val (mx, my) = NativeBridge.getMousePos()
            val hit = TilePickResolver.resolve(mx, my) ?: return

            backgroundDrawList {
                drawTileMarker(hit)
                val label = "Pick tile  (${hit.tileX}, ${hit.tileY})  plane=${hit.plane}  fineZ=${"%.0f".format(hit.heightFine)}"
                text(Vector2f(hit.screenX + 12f, hit.screenY - 24f), PICK_COLOR, label)
                text(Vector2f(hit.screenX + 12f, hit.screenY - 8f), 0xFFAAAAAA.toInt(), "left-click to select, Esc to cancel")
            }

            val now = System.currentTimeMillis()
            if (now - lastClickMs > CLICK_DEBOUNCE_MS && NativeBridge.isMouseClicked(0, false)) {
                lastClickMs = now
                QuestEditorState.offerPick(
                    PickResult(PickKind.TILE, tileX = hit.tileX, tileY = hit.tileY, plane = hit.plane, heightFine = hit.heightFine)
                )
            }
        } catch (t: Throwable) {
            println("[TilePickOverlay] render error: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun BackgroundDrawListScope.drawTileMarker(hit: TilePickResolver.Hit) {
        tile(Vector3f(hit.tileX * 512f + 256f, hit.tileY * 512f + 256f, hit.heightFine), PICK_COLOR)
        circle(Vector2f(hit.screenX, hit.screenY), 6f, PICK_COLOR, segments = 0, thickness = 2f)
        circleFilled(Vector2f(hit.screenX, hit.screenY), 2.5f, PICK_COLOR)
    }

    private fun isLoggedIn(): Boolean = try {
        Bootstrap.client.mainState == MainState.LOGGED_IN
    } catch (_: Throwable) { false }
}
