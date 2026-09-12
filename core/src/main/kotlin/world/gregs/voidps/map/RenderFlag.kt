package world.gregs.voidps.map

import world.gregs.voidps.cache.type.data.MapSquareTiles

/**
 * Interprets the raw per-tile flag byte from
 * [MapSquareTiles.flags].
 */
enum class RenderFlag(val flag: Int) {
    CLIPPED(0x1),
    LOWER_OBJECTS_TO_OVERRIDE_CLIPPING(0x2),
    UNDER_ROOF(0x4),
    FORCE_TO_BOTTOM(0x8),
    ROOF(0x10);

    companion object {
        fun getFlags(value: Int): List<RenderFlag> {
            return entries.filter { value and it.flag != 0 }
        }

        fun flagged(value: Int, vararg flags: RenderFlag): Boolean {
            val combinedFlag = flags.fold(0) { acc, f -> acc or f.flag }
            return value and combinedFlag != 0
        }
    }
}
