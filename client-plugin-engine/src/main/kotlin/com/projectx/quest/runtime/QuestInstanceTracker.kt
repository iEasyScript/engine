package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.script.api.localPlayer
import kotlin.math.abs

/**
 * Tracks the player's "instance origin" so the quest helper can translate
 * instance-relative direction/path coordinates to absolute world coordinates.
 *
 * In RS3, when the player enters an instanced area, their tile X jumps into
 * the 6400+ range; the actual scene is composed of chunks copied from
 * non-instance template locations. Bolt's Lua quest helper captures the
 * player's tile the first frame they appear in instance space and treats that
 * as the instance origin — instance-flagged actions store deltas from it.
 *
 * Behavior (mirrors `core/instance.lua` from bolt-questhelper):
 *  - Capture the player's tile the first frame X >= 6400.
 *  - Reset on leaving the instance area.
 *  - Detect instance hops (large displacement while staying in instance space)
 *    and re-capture.
 *  - Tolerate small Y-only adjustments shortly after entering (the engine
 *    sometimes nudges the height a few frames in).
 *
 * Updated every main-logic tick via [tick]; consumers read via [origin].
 */
object QuestInstanceTracker {
    private const val INSTANCE_X_THRESHOLD = 6400
    private const val HOP_CHEBYSHEV_DISTANCE = 16        // tiles — > 16 means we jumped to a new instance
    private const val Y_ADJUSTMENT_GRACE_FRAMES = 15

    /**
     * Instance anchor. [x]/[y] are the tile-space anchor used to translate
     * lua-side delta coordinates; [fineZ] is the local player's `graphNode.tileFine.z`
     * captured on entry so renderers can lock instance-relative `heightFine` to
     * the actual ground height of the instance (e.g. the snowy hill in
     * violet-is-blue is several thousand fine units above world Z=0).
     */
    data class Origin(val x: Int, val y: Int, val plane: Int, val fineZ: Float = 0f)

    @Volatile var origin: Origin? = null
        private set

    private var framesSinceChange = 0

    val isInInstance: Boolean
        get() = origin != null

    fun tick() {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) {
            reset()
            return
        }
        val tile = runCatching { localPlayer.tile }.getOrNull() ?: return
        val px = tile.x
        val py = tile.y
        val pz = tile.plane
        val fineZ = runCatching { localPlayer.graphNode.tileFine.z }.getOrDefault(0f)

        if (px < INSTANCE_X_THRESHOLD) {
            if (origin != null) reset()
            return
        }

        val current = origin
        if (current == null) {
            origin = Origin(px, py, pz, fineZ)
            framesSinceChange = 0
            return
        }

        framesSinceChange++

        val dx = abs(px - current.x)
        val dy = abs(py - current.y)
        if (maxOf(dx, dy) > HOP_CHEBYSHEV_DISTANCE * 8) {
            // Jumped to a different instance entirely.
            origin = Origin(px, py, pz, fineZ)
            framesSinceChange = 0
            return
        }

        // Early-tick Y-only height adjustment quirk: re-anchor if only the
        // plane / height shifted in the first ~15 ticks.
        if (framesSinceChange < Y_ADJUSTMENT_GRACE_FRAMES &&
            current.x == px && current.y == py && current.plane != pz
        ) {
            origin = Origin(px, py, pz, fineZ)
        }
    }

    fun reset() {
        origin = null
        framesSinceChange = 0
    }
}
