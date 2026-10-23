package com.projectx.game.nxt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each mouse ring is its samples followed directly by its write and read cursors. A table that moves the cursors
 * but carries the old base reads and writes past the ring's end, which on 951-1 landed synthetic clicks on the
 * cursors themselves. The layout is checked from the table alone, so a stale base cannot ship.
 */
class MouseRingLayoutTest {

    @Test
    fun `every ring ends exactly where its cursors begin`() {
        val broken = OffsetTable.bundledTableNames().flatMap { table ->
            val values = OffsetTable.bundled(table).values
            listOf("CLICK" to "CLICK", "MOVE" to "MOVE").mapNotNull { (ring, entry) ->
                val base = values["OMainLogicManager.${ring}_BUFFER_BASE"] ?: return@mapNotNull null
                val write = values["OMainLogicManager.${ring}_BUFFER_WRITE"] ?: return@mapNotNull null
                val read = values["OMainLogicManager.${ring}_BUFFER_READ"] ?: return@mapNotNull null
                val capacity = values["OMainLogicManager.${ring}_BUFFER_CAPACITY"] ?: return@mapNotNull null
                val stride = values["OMainLogicManager.${entry}_ENTRY_SIZE"] ?: return@mapNotNull null
                val end = base + capacity * stride
                when {
                    end != write -> "$table $ring ring ends at 0x${end.toString(16)}, cursors at 0x${write.toString(16)}"
                    read != write + Int.SIZE_BYTES -> "$table $ring read cursor is not after the write cursor"
                    else -> null
                }
            }
        }
        assertEquals(emptyList(), broken)
    }
}
