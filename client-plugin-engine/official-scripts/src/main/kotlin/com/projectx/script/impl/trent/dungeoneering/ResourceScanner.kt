package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill
import com.projectx.game.math.Vector3f
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import world.gregs.voidps.type.Tile

data class TrackedResource(
    val name: String,
    val tile: Tile,
    val fine: Vector3f?,
    val skill: Skill,
    val level: Int,
    val attainable: Boolean,
    val criticalTier: Boolean
)

data class TrackedSkillDoor(
    val key: DoorKey,
    val name: String,
    val tile: Tile,
    val fine: Vector3f?,
    val skill: Skill
)

data class TrackedKeyDoor(val name: String, val tile: Tile, val fine: Vector3f?, val openable: Boolean)

data class TrackedGuardianDoor(val tile: Tile, val fine: Vector3f?, val monstersAlive: Boolean)

data class TrackedGroundKey(val name: String, val tile: Tile)

data class TrackedSlayerNpc(
    val name: String,
    val tile: Tile,
    val level: Int,
    val attainable: Boolean,
    val criticalTier: Boolean
)

class ResourceScanner {
    var resources: List<TrackedResource> = emptyList()
        private set
    var skillDoors: List<TrackedSkillDoor> = emptyList()
        private set
    var keyDoors: List<TrackedKeyDoor> = emptyList()
        private set
    var guardianDoors: List<TrackedGuardianDoor> = emptyList()
        private set
    var slayerNpcs: List<TrackedSlayerNpc> = emptyList()
        private set
    var groundKeys: List<TrackedGroundKey> = emptyList()
        private set

    fun scanKeys() {
        groundKeys = groundItems
            .filter { DungeonTables.KEY_ITEM_NAME.matches(it.name) }
            .map { TrackedGroundKey(it.name, it.tile) }
    }

    fun scan(session: DungeonSession) {
        val foundResources = ArrayList<TrackedResource>()
        val foundSkillDoors = ArrayList<TrackedSkillDoor>()
        val foundKeyDoors = ArrayList<TrackedKeyDoor>()
        val foundGuardianDoors = ArrayList<Pair<Tile, Vector3f?>>()
        val seen = HashSet<Pair<String, Tile>>()
        val heldKeys = MapIcons.heldKeyNames()

        for (obj in getAllObjectsWithinRange(SCAN_RANGE)) {
            val name = obj.name()
            if (name.isEmpty()) continue
            if (!seen.add(name to obj.tile)) continue
            val fine = obj.graphNode?.tileFine

            val resource = DungeonTables.resource(name)
            if (resource != null) {
                val entry = session.entryLevel(resource.skill)
                foundResources += TrackedResource(
                    name, obj.tile, fine, resource.skill, resource.level,
                    resource.level <= entry,
                    DungeonTables.isCriticalTier(resource.skill, resource.level, entry)
                )
                continue
            }

            val door = DungeonTables.skillDoor(name)
            if (door != null && obj.hasOption(door.lockedAction)) {
                foundSkillDoors += TrackedSkillDoor(
                    DoorKey(obj.id, obj.tile.x, obj.tile.y), name, obj.tile, fine, door.skill
                )
                continue
            }

            if (DungeonTables.KEY_DOOR_NAME.matches(name) && obj.hasOption("Unlock")) {
                val openable = DungeonTables.keyNameForDoor(name) in heldKeys
                foundKeyDoors += TrackedKeyDoor(name, obj.tile, fine, openable)
                continue
            }

            if (name == DungeonTables.GUARDIAN_DOOR_NAME) {
                foundGuardianDoors += obj.tile to fine
                continue
            }
        }

        resources = foundResources
        for (resource in foundResources) session.resourceCache[resource.tile] = resource
        skillDoors = foundSkillDoors
        keyDoors = foundKeyDoors
        markOpenedDoors(session, foundSkillDoors)

        // A guardian door is RED iff the 16x16 room its own tile sits in still has a living attackable
        // monster, GREEN once that room is clear. The door tile is placed on the interior side of the room
        // it belongs to, so this is purely the door's room vs the monsters standing in it — no player
        // position, no adjacent room, no radius (a radius bleeds into the next room, the earlier bug).
        val liveMonsterTiles = allNpcsWithinRange(SCAN_RANGE) { it.hasOption("Attack") && it.currentHealth > 0 }
            .map { it.tile }
        guardianDoors = foundGuardianDoors.map { (tile, fine) ->
            val doorRoom = roomOf(tile)
            val locked = liveMonsterTiles.any { roomOf(it) == doorRoom }
            TrackedGuardianDoor(tile, fine, locked)
        }

        val slayerEntry = session.entryLevel(Skill.SLAYER)
        slayerNpcs = allNpcsWithinRange(SCAN_RANGE) { DungeonTables.slayerLevel(it.name()) != null }
            .map {
                val level = DungeonTables.slayerLevel(it.name()) ?: 1
                TrackedSlayerNpc(
                    it.name(), it.tile, level, level <= slayerEntry,
                    DungeonTables.isCriticalTier(Skill.SLAYER, level, slayerEntry)
                )
            }
    }

    // A known door that has vanished near the player was opened (doors above the
    // requirement can still be forced), so it must stop severing the room graph.
    private fun markOpenedDoors(session: DungeonSession, present: List<TrackedSkillDoor>) {
        val presentKeys = present.mapTo(HashSet()) { it.key }
        val playerTile = localPlayer.tile
        for (key in session.doors.entries().keys) {
            if (key in presentKeys || session.doors.isPassable(key)) continue
            if (playerTile.getDistance(Tile.of(key.x, key.y, playerTile.plane)) > OPENED_CHECK_RANGE) continue
            session.doors.markOpened(key)
            session.map.dirty = true
        }
    }

    fun clear() {
        resources = emptyList()
        skillDoors = emptyList()
        keyDoors = emptyList()
        guardianDoors = emptyList()
        slayerNpcs = emptyList()
    }

    private fun roomOf(t: Tile): Pair<Int, Int> = t.x / ROOM_TILES to t.y / ROOM_TILES

    private companion object {
        const val SCAN_RANGE = 60
        const val OPENED_CHECK_RANGE = 25
        const val ROOM_TILES = 16
    }
}
