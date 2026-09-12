package com.projectx.game.highlight

import com.projectx.game.nxt.OEntity
import com.projectx.game.nxt.ORenderModel
import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.PathingEntity
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT

/**
 * Drives the engine's built-in entity highlight system by writing directly to the render-model slot
 * the shader reads — the same path `jag::game::PathingEntity::UpdateHighlight`'s IMPORTANT
 * (combat-target) path uses. We bypass the CATEGORY path entirely (and its global category table) so
 * our writes never leak to other entities the engine has tagged with a category index.
 *
 * Ordering matters: writes must happen AFTER the main-logic trampoline (so the engine's own
 * per-frame UpdateHighlight is done) — see HighlightTick.
 */
object EntityHighlight {

    // Real Linux x86-64 user-space allocations are at minimum in the megabyte range
    // (typically 0x55_0000_0000+ or 0x7f_0000_0000+). Anything below 1 MB is a small
    // int garbage value living at a stale offset — refuse to deref.
    private const val MIN_VALID_ADDR = 0x100000L

    enum class Mode(val byte: Byte) {
        OFF(0), SOLID(1), OUTLINE(2), GLOW(3);
        companion object {
            fun from(value: Int): Mode = values().firstOrNull { it.byte.toInt() == value } ?: OUTLINE
        }
    }

    /**
     * Light [entity] up with the given color/mode/scale. Color is a packed
     * 0xRRGGBB int (the high byte is ignored). [scale] is the 0..255 intensity
     * byte the engine's renderer reads; default 20 — anything above ~32 is
     * usually overpowering.
     */
    fun apply(entity: Entity, color: Int, mode: Mode = Mode.OUTLINE, scale: Int = 20) {
        val rm = renderModel(entity) ?: return
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_R, r)
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_G, g)
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_B, b)
        rm.set(JAVA_FLOAT, ORenderModel.HL_BASE_ALPHA, 1.0f)
        rm.set(JAVA_FLOAT, ORenderModel.HL_STRENGTH, scale.coerceIn(0, 255).toFloat())
        rm.set(JAVA_FLOAT, ORenderModel.HL_FACTOR, 1.0f)
        rm.set(JAVA_BYTE, ORenderModel.HL_MODE, mode.byte)
    }

    /** Float-channel variant for callers that already work with normalized RGB. */
    fun apply(entity: Entity, r: Float, g: Float, b: Float, mode: Mode = Mode.OUTLINE, scale: Int = 20) {
        val rm = renderModel(entity) ?: return
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_R, r.coerceIn(0f, 1f))
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_G, g.coerceIn(0f, 1f))
        rm.set(JAVA_FLOAT, ORenderModel.HL_COLOR_B, b.coerceIn(0f, 1f))
        rm.set(JAVA_FLOAT, ORenderModel.HL_BASE_ALPHA, 1.0f)
        rm.set(JAVA_FLOAT, ORenderModel.HL_STRENGTH, scale.coerceIn(0, 255).toFloat())
        rm.set(JAVA_FLOAT, ORenderModel.HL_FACTOR, 1.0f)
        rm.set(JAVA_BYTE, ORenderModel.HL_MODE, mode.byte)
    }

    /** Clear any highlight applied to [entity]. */
    fun clear(entity: Entity) {
        val rm = renderModel(entity) ?: return
        rm.set(JAVA_FLOAT, ORenderModel.HL_STRENGTH, 0f)
        rm.set(JAVA_FLOAT, ORenderModel.HL_BASE_ALPHA, 0f)
        rm.set(JAVA_BYTE, ORenderModel.HL_MODE, 0.toByte())
    }

    /** Low-level: clear by raw render-model address (used when the entity
     *  wrapper is gone but we still know the pointer we wrote to last tick). */
    fun clearByRenderModelAddr(rmAddr: Long) {
        if (rmAddr < MIN_VALID_ADDR) return
        val rm = MemorySegment.ofAddress(rmAddr).reinterpret(0x200L)
        rm.set(JAVA_FLOAT, ORenderModel.HL_STRENGTH, 0f)
        rm.set(JAVA_FLOAT, ORenderModel.HL_BASE_ALPHA, 0f)
        rm.set(JAVA_BYTE, ORenderModel.HL_MODE, 0.toByte())
    }

    /**
     * The render-model address for [entity], or 0 if unknown / unallocated. Only PathingEntity
     * (NPC/Player) has a persistent render-model pointer with the highlight slots the renderer reads;
     * other entity kinds fall back to the UI tile-overlay.
     */
    fun renderModelAddr(entity: Entity): Long {
        if (entity !is PathingEntity) return 0L
        val addr = entity.ptr.get(ADDRESS, OEntity.RENDER_MODEL).address()
        return if (addr >= MIN_VALID_ADDR) addr else 0L
    }

    private fun renderModel(entity: Entity): MemorySegment? {
        val addr = renderModelAddr(entity)
        if (addr < MIN_VALID_ADDR) return null
        return MemorySegment.ofAddress(addr).reinterpret(0x200L)
    }

}
