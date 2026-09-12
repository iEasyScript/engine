package com.projectx.game.highlight

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.localPlayer
import com.projectx.ui.UIState

/**
 * Per-tick driver for the "show clickboxes" UI toggle. Runs AFTER the main-logic trampoline so
 * writes survive into the render frame. NPC/Player (PathingEntity) only — other entity kinds are
 * handled by the UI tile-overlay.
 */
object HighlightTick {

    private val touched = HashSet<Long>()

    fun tick(range: Int) {
        if (!UIState.showClickboxes.value) {
            // Drop without writing. The clear-by-addr write path was crashing long
            // grinds: in dense entity churn (wisp scripts, etc.) the allocator
            // reuses a freed render_model heap slot for a fresh entity, and our
            // write to render_model+0x100..0x134 then corrupts the new entity's
            // fields. The lingering highlight on stale entities is acceptable —
            // they'll be cleaned up by the engine's own refcount path when the
            // entity is finally torn down. Same hazard QuestHighlightTick already
            // mitigates for instance transitions.
            if (touched.isNotEmpty()) touched.clear()
            return
        }

        val r = UIState.clickboxColorR.value.coerceIn(0f, 1f)
        val g = UIState.clickboxColorG.value.coerceIn(0f, 1f)
        val b = UIState.clickboxColorB.value.coerceIn(0f, 1f)
        val mode = EntityHighlight.Mode.from(UIState.clickboxOutlineMode.value)
        val scale = UIState.clickboxIntensity.value

        val playerTile = runCatching { localPlayer.tile }.getOrNull() ?: return
        val nextTouched = HashSet<Long>(touched.size + 8)

        runCatching {
            val npcManager = Bootstrap.client.npcManager
            for (hashCode in npcManager.indices) {
                if (hashCode <= 0) continue
                val npcPtr = npcManager[hashCode] ?: continue
                if (npcPtr.address() == 0L) continue
                val npc = NPC(npcPtr)
                if (!npc.exists()) continue
                val t = runCatching { npc.tile }.getOrNull() ?: continue
                if (playerTile.getDistance(t) > range) continue
                lightUp(npc, r, g, b, mode, scale, nextTouched)
            }
        }

        // Track-and-drop instead of track-and-clear. An rmAddr we no longer touch
        // this tick could be: (a) still-alive NPC that moved out of range, or
        // (b) freed render_model whose heap slot has been reused by a fresh
        // entity. We can't distinguish cheaply, and writing into case (b)
        // corrupts the new entity → SIGSEGV minutes/hours later. Both quest
        // helper and this tick now follow the same rule: only write to addrs
        // currently in the live iteration; drop everything else without
        // touching it.
        touched.clear()
        touched.addAll(nextTouched)
    }

    private fun lightUp(
        entity: Entity,
        r: Float, g: Float, b: Float,
        mode: EntityHighlight.Mode,
        scale: Int,
        sink: HashSet<Long>,
    ) {
        val rmAddr = EntityHighlight.renderModelAddr(entity)
        if (rmAddr < 0x100000L) return
        EntityHighlight.apply(entity, r, g, b, mode, scale)
        sink.add(rmAddr)
    }
}
