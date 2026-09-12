package org.projectx.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vars varbit packing relies on [Vars.BIT_MASKS]: `BIT_MASKS[bitLength]` must be a mask of
 * `bitLength + 1` low bits, because varbit bit lengths are computed as `endBit - startBit`
 * (inclusive ranges).
 *
 * NOTE: setVarBit/getVarBit themselves read varbit definitions through the loaded cache
 * (`Cache.varbits` is a lazy global), so the full pack/unpack path cannot run as a pure unit
 * test without a cache fixture; the mask table and shift arithmetic pinned here are the pure
 * parts of that logic.
 */
class VarsBitMasksTest {

    @Test
    fun `mask table holds 2^(i+1) - 1`() {
        for (i in 0..30) {
            assertEquals(((1L shl (i + 1)) - 1).toInt(), Vars.BIT_MASKS[i], "BIT_MASKS[$i]")
        }
        assertEquals(-1, Vars.BIT_MASKS[31])
    }

    @Test
    fun `mask and shift arithmetic packs and unpacks varbit-style ranges`() {
        // Mirror of the setVarBit/getVarBit arithmetic with explicit start/end bits,
        // independent of cache definitions.
        fun pack(varp: Int, startBit: Int, endBit: Int, value: Int): Int {
            val mask = Vars.BIT_MASKS[endBit - startBit]
            val capped = value.coerceIn(0, mask)
            val shifted = mask shl startBit
            return (varp and shifted.inv()) or ((capped shl startBit) and shifted)
        }
        fun unpack(varp: Int, startBit: Int, endBit: Int): Int =
            (varp shr startBit) and Vars.BIT_MASKS[endBit - startBit]

        // Disjoint (startBit, endBit, value) ranges packed into one varp
        val cases = listOf(
            Triple(0, 0, 1),
            Triple(1, 8, 200),
            Triple(9, 14, 33),
            Triple(28, 31, 9),
        )
        var varp = 0
        for ((start, end, value) in cases) {
            varp = pack(varp, start, end, value)
            assertEquals(value, unpack(varp, start, end), "unpack bits $start..$end")
        }
        // Every range must survive the later writes to other ranges
        for ((start, end, value) in cases) {
            assertEquals(value, unpack(varp, start, end), "bits $start..$end after later writes")
        }

        // Out-of-range values are capped at the mask
        assertEquals(Vars.BIT_MASKS[2], unpack(pack(0, 5, 7, 9999), 5, 7))
    }
}
