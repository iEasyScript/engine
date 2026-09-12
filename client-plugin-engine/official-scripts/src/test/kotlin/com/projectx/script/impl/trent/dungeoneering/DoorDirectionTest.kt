package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.impl.trent.dungeoneering.auto.DungeonNavigator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import world.gregs.voidps.type.Tile

/**
 * Tiles taken from the live floor the bot bounced on for four and a half minutes: the two halves of one
 * doorway between rooms (968,7) and (968,6), plus the east door that was the way out the whole time.
 */
class DoorDirectionTest {

    @Test
    fun `the two halves of a doorway point back at each other`() {
        assertEquals(968 to 6, DungeonNavigator.doorLeadsTo(968 to 7, Tile.of(15495, 112, 0)))
        assertEquals(968 to 7, DungeonNavigator.doorLeadsTo(968 to 6, Tile.of(15495, 111, 0)))
    }

    @Test
    fun `the unused east door leads somewhere new`() {
        assertEquals(969 to 6, DungeonNavigator.doorLeadsTo(968 to 6, Tile.of(15503, 103, 0)))
    }

    @Test
    fun `a tile off the room wall has no far side`() {
        assertNull(DungeonNavigator.doorLeadsTo(968 to 6, Tile.of(15495, 103, 0)))
    }
}
