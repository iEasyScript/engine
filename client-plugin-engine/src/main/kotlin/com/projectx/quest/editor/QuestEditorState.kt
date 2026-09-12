package com.projectx.quest.editor

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.runtime.ActiveQuestState
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.localPlayer
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.script.event.impl.ManualGroundItem
import com.projectx.script.event.impl.ManualItemTarget
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.intState
import com.projectx.ui.backend.dsl.stringState
import com.projectx.ui.backend.native.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI-only state for the quest editor window: which quest is being edited,
 * which step pane is open, tile-pick mode, and the result of the most recent
 * save. Editing a quest doesn't take effect on the live runtime until the user
 * clicks Save (which calls [QuestLibrary.saveQuest] and reloads).
 */
object QuestEditorState {
    val windowOpen = boolState(false)

    @Volatile var draft: MutableQuest? = null
        private set

    val selectedStepIndex = intState(0)

    /** True while any entity/tile pick is armed; drives the pick overlay and prompts. */
    val pickMode = boolState(false)
    val pickPrompt = stringState("", 64)

    /** Kinds the active pick accepts; read by [TilePickOverlay] to decide whether to draw. */
    @Volatile var pendingAccepts: Set<PickKind> = emptySet()
        private set

    val saveStatus = stringState("", 256)

    @Volatile var lastSavedFile: File? = null
        private set

    @Volatile private var pendingSink: PickSink? = null
    @Volatile private var readyResult: PickResult? = null
    @Volatile private var readySink: PickSink? = null
    private val pickObserverRegistered = AtomicBoolean(false)

    fun beginEdit(quest: Quest) {
        draft = MutableQuest.fromQuest(quest)
        selectedStepIndex.value = ActiveQuestState.currentStepIndex.coerceIn(0, (draft?.steps?.size ?: 1) - 1).coerceAtLeast(0)
        windowOpen.value = true
        saveStatus.value = ""
    }

    fun discard() {
        draft = null
        cancelPick()
        readyResult = null
        readySink = null
        windowOpen.value = false
    }

    fun save(): Boolean {
        val d = draft ?: return false
        val q = d.toQuest()
        // Capture the user's current step BEFORE saveQuest's reload tears down
        // ActiveQuestState. select() resets currentStepIndex to 0 (plus a varbit-stage
        // seek) - without preserving here, every save would yank the player back to
        // step 1 of whatever they were editing.
        val keepStepIdx = ActiveQuestState.currentStepIndex
        val f = QuestLibrary.saveQuest(q)
        lastSavedFile = f
        saveStatus.value = if (f != null) "[x] Saved ${f.name}" else "! Save failed"
        if (f != null && ActiveQuestState.selectedQuest?.slug == q.slug) {
            QuestLibrary.bySlug(q.slug)?.let {
                ActiveQuestState.select(it)
                ActiveQuestState.setStep(keepStepIdx)
            }
        }
        return f != null
    }

    /**
     * Arm an entity/tile pick. The next matching thing the user interacts with
     * in-game - captured from the [ManualDoAction] event stream (object / npc /
     * item) or the tile-pick overlay - is resolved to a [PickResult] and handed
     * to [sink]. [accepts] limits which kinds fulfil it. Delivery happens on the
     * render thread via [drainPickDelivery] so the sink mutates editor state safely.
     */
    @Synchronized
    fun beginPick(accepts: Set<PickKind>, prompt: String, sink: PickSink) {
        ensurePickObserver()
        pendingSink = sink
        pendingAccepts = accepts
        pickPrompt.value = prompt
        pickMode.value = true
    }

    /** Convenience: arm a tile-only pick delivered as (x, y, plane, heightFine). */
    fun beginPickTile(sink: TilePickSink) =
        beginPick(setOf(PickKind.TILE), "left-click a tile to capture") { r ->
            sink.onPicked(r.tileX, r.tileY, r.plane, r.heightFine)
        }

    @Synchronized
    fun cancelPick() {
        pendingSink = null
        pendingAccepts = emptySet()
        pickMode.value = false
    }

    /** Offer a captured pick from any source; ignored unless armed and the kind is accepted. */
    @Synchronized
    fun offerPick(result: PickResult) {
        if (pendingSink == null || result.kind !in pendingAccepts) return
        readyResult = result
        readySink = pendingSink
        pendingSink = null
        pendingAccepts = emptySet()
        pickMode.value = false
    }

    /** Render thread: apply a captured pick to the draft. Call each frame while the editor is open. */
    fun drainPickDelivery() {
        val r = readyResult ?: return
        val sink = readySink
        readyResult = null
        readySink = null
        try {
            sink?.onPicked(r)
        } catch (t: Throwable) {
            println("[QuestEditor] pick sink threw: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun ensurePickObserver() {
        if (pickObserverRegistered.compareAndSet(false, true)) {
            ScriptExecutor.addEventObserver(::onGameEvent)
        }
    }

    // Main-logic thread: resolve a manual interaction into a pick. Reads entity
    // memory here (safe under the main-logic lock); never mutates editor state.
    private fun onGameEvent(event: Event) {
        if (event !is ManualDoAction || pendingSink == null) return
        val result = when (val t = event.target) {
            is NPC -> runCatching { PickResult(PickKind.NPC, npcTypeId(t.typeId, t.id), t.name) }.getOrNull()
            is SceneObject -> runCatching { PickResult(PickKind.OBJECT, t.visibleTypeId, t.name()) }.getOrNull()
            is ManualItemTarget -> PickResult(PickKind.ITEM, t.itemId, t.name)
            is ManualGroundItem -> runCatching {
                val z = HeightMap.fineHeight(t.tile)?.toFloat() ?: playerFineZ()
                PickResult(PickKind.GROUND_ITEM, t.itemId, t.name,
                    tileX = t.tile.x, tileY = t.tile.y, plane = t.tile.plane, heightFine = z)
            }.getOrNull()
            is IFSlot -> PickResult(PickKind.COMPONENT, interfaceId = t.interfaceId, componentId = t.componentId, slotId = t.slotId)
            is Tile -> runCatching {
                val z = HeightMap.fineHeight(t)?.toFloat() ?: playerFineZ()
                PickResult(PickKind.TILE, tileX = t.x, tileY = t.y, plane = t.plane, heightFine = z)
            }.getOrNull()
            else -> null
        } ?: return
        offerPick(result)
    }

    private fun npcTypeId(typeId: Int, id: Int): Int = if (typeId != -1) typeId else id
    private fun playerFineZ(): Float = runCatching { localPlayer.graphNode.tileFine.z }.getOrDefault(0f)
}

enum class PickKind { TILE, OBJECT, NPC, ITEM, GROUND_ITEM, COMPONENT }

data class PickResult(
    val kind: PickKind,
    val typeId: Int = -1,
    val name: String = "",
    val tileX: Int = 0,
    val tileY: Int = 0,
    val plane: Int = 0,
    val heightFine: Float = 0f,
    val interfaceId: Int = -1,
    val componentId: Int = -1,
    val slotId: Int = -1,
)

fun interface PickSink {
    fun onPicked(result: PickResult)
}

fun interface TilePickSink {
    fun onPicked(tileX: Int, tileY: Int, plane: Int, heightFine: Float)
}

/**
 * Mouse->world tile resolver. Loops over tiles within range of the player,
 * projects each tile centre to screen, and returns the tile whose screen
 * centre is closest to the mouse (within MAX_SCREEN_PX). Cheap to call each
 * frame (~21×21 tile fan-out at default range).
 */
object TilePickResolver {
    private const val MAX_SCREEN_PX = 64.0

    data class Hit(
        val tileX: Int,
        val tileY: Int,
        val plane: Int,
        val heightFine: Float,
        val screenX: Float,
        val screenY: Float,
        /** True when [tileX]/[tileY] are instance-local (delta from the instance origin). */
        val instance: Boolean = false,
    )

    fun resolve(mouseX: Float, mouseY: Float, range: Int = 30): Hit? {
        if (!isLoggedIn()) return null
        val p = runCatching { localPlayer.tile }.getOrNull() ?: return null
        val plane = p.plane
        val playerFineZ = runCatching { localPlayer.graphNode.tileFine.z }.getOrDefault(0f)
        val px = p.x
        val py = p.y
        var best: Hit? = null
        var bestDistSq = MAX_SCREEN_PX * MAX_SCREEN_PX
        for (dy in -range..range) {
            for (dx in -range..range) {
                val tx = px + dx
                val ty = py + dy
                // Real ground height per tile so picking lands correctly on slopes (and the captured
                // heightFine is the tile's own, not the player's).
                val z = HeightMap.fineHeight(plane, tx * 512 + 256, ty * 512 + 256)?.toFloat() ?: playerFineZ
                val fine = Vector3f(tx * 512f + 256f, ty * 512f + 256f, z)
                val xy: Vector2f = WorldToScreen.getEstimatedTileCenter(fine) ?: continue
                val ddx = (xy.x - mouseX).toDouble()
                val ddy = (xy.y - mouseY).toDouble()
                val d2 = ddx * ddx + ddy * ddy
                if (d2 < bestDistSq) {
                    bestDistSq = d2
                    best = Hit(tx, ty, plane, z, xy.x, xy.y)
                }
            }
        }
        return best
    }

    private fun isLoggedIn(): Boolean = try {
        Bootstrap.client.mainState == MainState.LOGGED_IN
    } catch (_: Throwable) { false }
}

/** Current player tile + fine z, captured at call time. */
fun currentPlayerTilePick(): TilePickResolver.Hit? {
    return try {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return null
        val t = localPlayer.tile
        val gnFine = localPlayer.graphNode.tileFine
        TilePickResolver.Hit(t.x, t.y, t.plane, gnFine.z, 0f, 0f)
    } catch (_: Throwable) {
        null
    }
}

/** Reads the current cursor position from the native ImGui IO. */
fun currentMousePos(): Pair<Float, Float> {
    return try {
        val (x, y) = NativeBridge.getMousePos()
        x to y
    } catch (_: Throwable) {
        0f to 0f
    }
}
