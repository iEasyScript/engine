package com.projectx.game.hooks.impl

import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OLocChangeRecord
import com.projectx.pathfinder.DynamicMapSquareCollision
import com.projectx.pathfinder.WorldCollision
import java.lang.foreign.MemorySegment

/**
 * Every single-loc scene mutation (LOC_ADD_CHANGE / LOC_DEL / customise) funnels through
 * jag::game::ApplyLocChange. The client keeps no walk-collision grid, so a dynamically spawned loc — e.g.
 * Stomp's falling debris — never entered our rebuilt instance clip, and mining it never cleared it. Flag the
 * affected zone dirty (add/del only) so [DynamicMapSquareCollision] re-clips just that zone next tick.
 *
 * The loc record is arg1[1]; all coords are absolute-world. We only need the tile and whether it is an
 * add/del vs an animate, which never changes collision.
 */
object ApplyLocChangeHook {
    private const val RECORD_SIZE = 0x80L
    private const val KIND_ADD = 1
    private const val KIND_DEL = 3

    @JvmStatic
    @Hook("APPLY_LOC_CHANGE")
    fun applyLocChangeHook(collector: MemorySegment, descPair: MemorySegment) {
        HookManager.trampoline(::applyLocChangeHook.name)
            .invoke(collector, descPair)

        if (!WorldCollision.inDynamic) return
        try {
            val record = descPair.pointerAtOffset(OLocChangeRecord.RECORD, RECORD_SIZE)
            val kind = record.readInt(OLocChangeRecord.KIND)
            if (kind != KIND_ADD && kind != KIND_DEL) return
            DynamicMapSquareCollision.markZoneDirty(record.readInt(OLocChangeRecord.TILE_X), record.readInt(OLocChangeRecord.TILE_Y), record.readInt(OLocChangeRecord.PLANE))
        } catch (_: Throwable) {
        }
    }
}
