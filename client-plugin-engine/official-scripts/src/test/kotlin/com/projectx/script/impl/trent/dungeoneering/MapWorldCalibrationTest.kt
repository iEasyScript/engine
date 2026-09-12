package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.impl.trent.dungeoneering.map.MapWorldCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import world.gregs.voidps.type.Tile

class MapWorldCalibrationTest {

    @Test
    fun `worldRoomFor is the exact inverse of cellFor`() {
        val cal = MapWorldCalibration()
        // Anchor: the player stands in world room (500, 143) and their pip is at map cell (8, 7).
        cal.setAnchor(500 to 143, 8 to 7)

        // Every in-range cell round-trips: cellFor(center of worldRoomFor(cell)) == cell.
        for (gy in 0..15) for (gx in 0..15) {
            val cell = gx to gy
            val (roomX, roomY) = cal.worldRoomFor(cell)!!
            val anyTileInRoom = Tile.of(roomX * 16 + 3, roomY * 16 + 11, 0)
            assertEquals(cell, cal.cellFor(anyTileInRoom), "cell $cell should round-trip through room ($roomX,$roomY)")
        }
    }

    @Test
    fun `roomCenterTile lands inside the target cell's room and carries the plane`() {
        val cal = MapWorldCalibration()
        cal.setAnchor(500 to 143, 8 to 7)
        val center = cal.roomCenterTile(9 to 6, plane = 2)!!
        assertEquals(2, center.plane)
        // The centre tile must map back to the same cell.
        assertEquals(9 to 6, cal.cellFor(Tile.of(center.x, center.y, 0)))
        // North on the map (gy 6 < anchor 7) is +1 room in world Y (ySign = -1).
        assertEquals(144, center.y / 16)
        // East on the map (gx 9 > anchor 8) is +1 room in world X.
        assertEquals(501, center.x / 16)
    }

    @Test
    fun `reverse mapping returns null until calibrated`() {
        val cal = MapWorldCalibration()
        assertNull(cal.worldRoomFor(3 to 3))
        assertNull(cal.roomCenterTile(3 to 3, 0))
    }
}
