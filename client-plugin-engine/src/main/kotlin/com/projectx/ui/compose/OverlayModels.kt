package com.projectx.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectx.markers.TileMarkerState
import com.projectx.quest.editor.QuestEditorState
import com.projectx.ui.UIState
import com.projectx.ui.compose.library.LibraryModel
import com.projectx.ui.compose.screens.Cs2Model
import com.projectx.ui.compose.screens.EffectsModel
import com.projectx.ui.compose.screens.EntitiesModel
import com.projectx.ui.compose.screens.FarmingModel
import com.projectx.ui.compose.screens.InputRecordingModel
import com.projectx.ui.compose.screens.InterfacesModel
import com.projectx.ui.compose.screens.InventionModel
import com.projectx.ui.compose.screens.InventoryModel
import com.projectx.ui.compose.screens.LogsModel
import com.projectx.ui.compose.screens.PacketLogModel
import com.projectx.ui.compose.screens.QuestEditorModel
import com.projectx.ui.compose.screens.QuestsModel
import com.projectx.ui.compose.screens.SettingsModel
import com.projectx.ui.compose.screens.StoreModel
import com.projectx.ui.compose.screens.TileMarkersModel
import com.projectx.ui.compose.screens.VariablesModel
import com.projectx.ui.compose.screens.XpModel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Work the panel asks for that touches the game.
 *
 * The panel runs on the render thread, which must never read game memory: the main-logic thread mutates it and
 * the two would race. Anything a click needs from the game - the player's tile, a varp, an inventory - is posted
 * here and run at the start of the next main-logic frame instead.
 */
object GameThread {
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    fun post(action: () -> Unit) {
        queue += action
    }

    fun drain() {
        while (true) {
            val action = queue.poll() ?: return
            runCatching(action).onFailure { it.printStackTrace() }
        }
    }
}

/**
 * One screen's data, produced on the main-logic thread and handed to the panel as a finished value.
 *
 * Produced at most every [intervalMs], and only while the screen is showing - reading live game state costs a frame
 * its time, and nobody is looking at a hidden screen. Publishing only a changed value keeps an idle screen still.
 */
class Feed<T>(private val intervalMs: Long, private val produce: () -> T) {
    var value: T? by mutableStateOf(null)
        private set

    private var lastRun = 0L

    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRun < intervalMs) return
        lastRun = now
        val next = try {
            produce()
        } catch (t: Throwable) {
            println("[Overlay] ${t::class.simpleName} producing a screen: ${t.message}")
            return
        }
        if (next != value) value = next
    }

    /** Runs the next [refresh] regardless of the interval, for a change the user just asked for. */
    fun invalidate() {
        lastRun = 0L
    }
}

/** Runs on the main-logic thread each frame: queued panel work, settings upkeep, and the showing screen's data. */
object OverlayModels {
    /** Whether the render thread has created the screens' state yet; see [prepare]. */
    @Volatile private var prepared = false

    /**
     * Creates every screen's state on the render thread, before anything composes. Render thread only.
     *
     * A Compose state is only readable from snapshots taken after it was created. Were a screen's state created on the
     * game thread - its model first touched by [refresh] - while the render thread was mid-recomposition, that
     * recomposition would read a state newer than its snapshot and throw, and the panel would stop responding. So the
     * models are created here, and the game thread leaves them alone until they have been.
     */
    fun prepare() {
        if (prepared) return
        listOf(
            UIState, TileMarkerState, QuestEditorState, OverlayNavigation, OverlayKeyboard, OverlayClock, LibraryModel,
            StoreModel, QuestsModel, XpModel, InventoryModel, EffectsModel, InventionModel, FarmingModel, EntitiesModel,
            TileMarkersModel, LogsModel, PacketLogModel, InputRecordingModel, VariablesModel, InterfacesModel, Cs2Model,
            SettingsModel, QuestEditorModel,
        )
        prepared = true
    }

    fun refresh() {
        // Settings upkeep (MCP, the packet recorder) has to run whether or not the overlay is drawn. The Settings page
        // is the only reader of SettingsModel, and nothing can open it before the render thread's first frame.
        SettingsModel.reconcile()
        if (!prepared) return
        GameThread.drain()
        QuestEditorModel.refresh()
        if (!ComposeOverlay.visible) return
        LibraryModel.refresh()
        refreshPage(OverlayNavigation.page)
    }

    internal fun refreshPage(page: Page) {
        feedFor(page)?.refresh()
    }

    private fun feedFor(page: Page): Feed<*>? = when (page) {
        Page.Library -> null
        Page.Store -> StoreModel.feed
        Page.Quests -> QuestsModel.feed
        Page.Xp -> XpModel.feed
        Page.Inventory -> InventoryModel.feed
        Page.Effects -> EffectsModel.feed
        Page.Invention -> InventionModel.feed
        Page.Farming -> FarmingModel.feed
        Page.Entities -> EntitiesModel.feed
        Page.Collision -> null
        Page.TileMarkers -> TileMarkersModel.groups
        Page.Logs -> LogsModel.feed
        Page.PacketLog -> PacketLogModel.feed
        Page.InputRecording -> InputRecordingModel.feed
        Page.Variables -> VariablesModel.feed
        Page.Interfaces -> InterfacesModel.feed
        Page.Cs2Trace -> Cs2Model.feed
        Page.Settings -> SettingsModel.feed
    }
}
