package com.projectx.quest.runtime

import world.gregs.voidps.type.Tile
import com.projectx.game.highlight.EntityHighlight
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.data.QuestAction
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.ui.UIState

/**
 * Per-tick driver that pushes the engine's highlight onto the quest helper's
 * currently-targeted entities. Runs after the main-logic trampoline so writes
 * survive into the render frame.
 *
 * Writes directly to each targeted entity's render-model — no global category
 * table involved, so quest-helper writes never leak to other entities the
 * engine has assigned a category index to.
 *
 * Also publishes [labels] — a frozen per-frame snapshot of `(fineCoord, name)`
 * pairs for each [QuestAction.ModelHighlight] in the current step. The render
 * thread reads this instead of calling [QuestTargetMatcher] directly: the
 * matcher walks live `npcManager` + SQLite-backed cache types, which crashes
 * if the render thread races a scene mutation on the main thread.
 */
object QuestHighlightTick {
    private const val COLOR = 0x00FFFF   // cyan
    private const val SCALE = 8

    data class Label(
        val fineX: Float, val fineY: Float, val fineZ: Float,
        val name: String,
        /** World tiles the target's footprint covers — populated for scene objects, empty for NPCs
         *  (which already get the engine render-model outline). The render thread draws these. */
        val tiles: List<Tile> = emptyList(),
    )

    @Volatile var labels: Map<QuestAction.ModelHighlight, List<Label>> = emptyMap()
        private set

    /**
     * Per-tick snapshot of the active step's solver overlay actions. Evaluated here
     * on the main-logic thread (post-trampoline, under the main lock) so the render
     * thread can render highlights without ever reading game state itself.
     */
    @Volatile var solverOverlayActions: List<QuestAction> = emptyList()
        private set

    private val touched = HashSet<Long>()
    private var lastInstanceOrigin: QuestInstanceTracker.Origin? = null
    private var lastQuestSlug: String? = null
    private var lastStepIdx: Int = -1

    fun tick() {
        // Drop stale render-model addresses on any instance enter / exit / hop.
        // The game reuses heap slots immediately after freeing the old scene's
        // entities, so clearing or writing to addresses from before the boundary
        // corrupts refcount fields of the brand-new objects sitting at those
        // addresses — the next DecRef the game does on one of them then crashes
        // (witnessed entering Land of Snow in violet-is-blue, IP at
        // jag::ref_counter_base::DecRef in rs2client).
        val currentOrigin = QuestInstanceTracker.origin
        if (currentOrigin != lastInstanceOrigin) {
            touched.clear()
            if (labels.isNotEmpty()) labels = emptyMap()
            lastInstanceOrigin = currentOrigin
        }

        val quest = ActiveQuestState.selectedQuest
        val stepIdx = ActiveQuestState.currentStepIndex
        val step = quest?.steps?.getOrNull(stepIdx)

        // Quest completion / deselection / step change all imply the previous step's
        // matched entities may have been freed (cutscene NPCs despawned, quest-spawned
        // objects removed, etc.) — same hazard as the instance-origin branch above.
        // Drop touched without writing; the engine's per-frame UpdateHighlight handles
        // any still-live entities. Only call clearAllTouched (which writes) when the
        // entities are guaranteed still alive: user toggling the overlay off mid-step.
        val questChanged = quest?.slug != lastQuestSlug
        val stepChanged = stepIdx != lastStepIdx
        if (questChanged || stepChanged) {
            touched.clear()
            if (labels.isNotEmpty()) labels = emptyMap()
            lastQuestSlug = quest?.slug
            lastStepIdx = stepIdx
        }

        if (!UIState.questHelperEnabled.value || !UIState.questHelperShowOverlay.value) {
            // Drop without writing — same rationale as the track-and-drop block
            // below. The entity might already be torn down by the time the user
            // toggles the overlay off; the stale highlight is preferable to a
            // delayed-fire SIGSEGV.
            if (touched.isNotEmpty()) touched.clear()
            if (labels.isNotEmpty()) labels = emptyMap()
            if (solverOverlayActions.isNotEmpty()) solverOverlayActions = emptyList()
            return
        }

        if (quest == null || step == null) {
            // Touched already dropped above by the quest/step change branch; nothing
            // safe to write. Labels also dropped.
            if (solverOverlayActions.isNotEmpty()) solverOverlayActions = emptyList()
            return
        }

        val nextTouched = HashSet<Long>(4)
        val nextLabels = mutableMapOf<QuestAction.ModelHighlight, List<Label>>()
        val solverActions = step.solverId?.let {
            QuestSolverRegistry.overlayActionsFor(quest, step, ActiveQuestState.currentStepIndex, System.currentTimeMillis() / 250)
        } ?: emptyList()
        solverOverlayActions = solverActions
        val combinedActions = if (solverActions.isEmpty()) step.actions else step.actions + solverActions
        for (action in combinedActions) {
            if (action !is QuestAction.ModelHighlight) continue
            if (action.kind == "item") continue
            if (action.kind == "grounditem") {
                // Ground items have no engine render-model glow — highlight the tile they sit on
                // (like scene objects). Tiles + ground height resolved here on the main-logic thread.
                val items = QuestTargetMatcher.findGroundItems(action)
                val chosen = if (action.priority == "all") items else items.take(1)
                if (chosen.isNotEmpty()) nextLabels[action] = chosen.map(::groundItemLabel)
                continue
            }
            // `highlightPriority = "all"` outlines every match; default is closest.
            val targets: List<Entity> = if (action.priority == "all") {
                QuestTargetMatcher.findAll(action)
            } else {
                listOfNotNull(QuestTargetMatcher.findFirst(action))
            }
            if (targets.isEmpty()) continue
            val actionLabels = mutableListOf<Label>()
            for (target in targets) {
                EntityHighlight.apply(target, color = COLOR, mode = EntityHighlight.Mode.OUTLINE, scale = SCALE)
                val rmAddr = EntityHighlight.renderModelAddr(target)
                if (rmAddr >= 0x100000L) nextTouched.add(rmAddr)
                snapshotEntity(target)?.let { actionLabels += it }
            }
            if (actionLabels.isNotEmpty()) nextLabels[action] = actionLabels
        }

        // Track-and-drop instead of track-and-clear. An rmAddr we no longer touch
        // this tick could be a still-alive entity that moved out of match scope, or
        // a freed render_model whose heap slot has been reused by a fresh entity.
        // We can't distinguish cheaply, and writing into the reused-slot case
        // corrupts the new entity → SIGSEGV minutes/hours later. The same fix
        // HighlightTick uses; the state-transition branch above handles the bulk
        // clear cases. Only addrs still in the live iteration get written this
        // tick; everything else is dropped untouched.
        touched.clear()
        touched.addAll(nextTouched)
        labels = nextLabels
    }

    // No entity.name() call here — that goes through NPCType.get/ObjectType.get,
    // which hits the SQLite-backed cache. Combining the live tick-side iteration
    // with that cache read corrupted scene memory on instance entry. The renderer
    // already has the action's displayName so the label has a non-empty source.
    private fun groundItemLabel(m: QuestTargetMatcher.GroundItemMatch): Label {
        val fineX = m.tile.x * 512f + 256f
        val fineY = m.tile.y * 512f + 256f
        val fineZ = HeightMap.fineHeight(m.tile)?.toFloat() ?: 0f
        return Label(fineX, fineY, fineZ, m.name, listOf(m.tile))
    }

    private fun snapshotEntity(entity: Entity): Label? = runCatching {
        val coord = entity.graphNode.tileFine
        // Footprint computed here on the main-logic thread (live LocType memory read, no SQLite) so
        // the render thread never touches game state. NPCs aren't SceneObjects → no footprint.
        val tiles = if (entity is SceneObject) entity.occupiedTiles() else emptyList()
        Label(coord.x, coord.y, coord.z, "", tiles)
    }.getOrNull()
}
