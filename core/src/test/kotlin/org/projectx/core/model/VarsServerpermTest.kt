package org.projectx.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VarsServerpermTest {
    @Test
    fun `defaults-only serverperm block has the expected framing and size`() {
        val block = Vars().serverpermBlock()
        assertTrue(Vars.DEFAULT_SERVERPERM_VARCS.isNotEmpty())
        assertEquals(1, block[0].toInt() and 0xFF)
        assertEquals(1 + Vars.DEFAULT_SERVERPERM_VARCS.size * 6, block.size)
    }

    @Test
    fun `saved serverperm varc overrides a default in the block`() {
        val vars = Vars()
        val id = Vars.DEFAULT_SERVERPERM_VARCS.keys.first()
        vars.saveVarc(id, 0x0BADF00D)
        val block = vars.serverpermBlock()
        assertEquals(1 + Vars.DEFAULT_SERVERPERM_VARCS.size * 6, block.size)
    }
}
