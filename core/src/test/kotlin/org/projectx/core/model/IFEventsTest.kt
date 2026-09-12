package org.projectx.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IF_SETEVENTS settings bitfield builder tests. Bit positions are RE-verified against the
 * rs2client binary (see IFEvents class docs) — these tests pin them so a refactor cannot
 * silently shuffle interaction flags.
 */
class IFEventsTest {

    private fun events(block: IFEvents.() -> Unit = {}): IFEvents =
        IFEvents(906, 5).apply(block)

    @Test
    fun `individual builder bits land at the documented positions`() {
        assertEquals(0x1, events { enableContinueButton() }.settings)
        assertEquals(1 shl 1, events { enableRightClickOption(0) }.settings)
        assertEquals(1 shl 10, events { enableRightClickOption(9) }.settings)
        assertEquals(1 shl 11, events { enableUseOption(UseFlag.GROUND_ITEM) }.settings)
        assertEquals(1 shl 12, events { enableUseOption(UseFlag.NPC) }.settings)
        assertEquals(1 shl 13, events { enableUseOption(UseFlag.WORLD_OBJECT) }.settings)
        assertEquals(1 shl 14, events { enableUseOption(UseFlag.PLAYER) }.settings)
        assertEquals(1 shl 15, events { enableUseOption(UseFlag.SELF) }.settings)
        assertEquals(1 shl 16, events { enableUseOption(UseFlag.ICOMPONENT) }.settings)
        assertEquals(1 shl 17, events { enableUseOption(UseFlag.WORLD_TILE) }.settings)
        assertEquals(1 shl 21, events { enableDrag() }.settings)
        assertEquals(1 shl 22, events { enableUseTargetability() }.settings)
        assertEquals(1 shl 23, events { enableIgnoreDepth() }.settings)
        assertEquals(1 shl 24, events { enableAllowTargetSend() }.settings)
    }

    @Test
    fun `depth occupies bits 18-20 and round-trips`() {
        for (depth in 0..7) {
            val e = events { setDepth(depth) }
            assertEquals(depth shl 18, e.settings, "settings for depth $depth")
            assertEquals(depth, e.getDepth())
        }
        // setDepth overwrites the previous depth instead of OR-ing
        val e = events { setDepth(7); setDepth(2) }
        assertEquals(2, e.getDepth())
        assertEquals(2 shl 18, e.settings)
    }

    @Test
    fun `right-click options combine and use flags aggregate`() {
        val e = events { enableRightClickOptions(0, 1, 9) }
        assertEquals((1 shl 1) or (1 shl 2) or (1 shl 10), e.settings)

        val flags = events { enableUseOptions(UseFlag.NPC, UseFlag.PLAYER) }
        assertEquals(UseFlag.NPC.flag or UseFlag.PLAYER.flag, flags.getUseOptionFlags())
    }

    @Test
    fun `builders compose without clobbering other bits`() {
        val e = events {
            enableContinueButton()
            enableRightClickOption(2)
            enableUseOption(UseFlag.SELF)
            setDepth(3)
            enableDrag()
            enableAllowTargetSend()
        }
        val expected = 0x1 or (1 shl 3) or (1 shl 15) or (3 shl 18) or (1 shl 21) or (1 shl 24)
        assertEquals(expected, e.settings)
    }

    @Test
    fun `toString emits a copy-pasteable builder expression`() {
        assertEquals("IFEvents(906, 5)", events().toString())
        assertEquals(
            "IFEvents(906, 5).enableRightClickOption(0)",
            events { enableRightClickOption(0) }.toString(),
        )
        assertEquals(
            "IFEvents(906, 5, 0, 27).enableRightClickOptions(0, 1).setDepth(1).enableDrag()",
            IFEvents(906, 5, 0, 27) {
                enableRightClickOptions(0, 1)
                setDepth(1)
                enableDrag()
            }.toString(),
        )
    }
}
