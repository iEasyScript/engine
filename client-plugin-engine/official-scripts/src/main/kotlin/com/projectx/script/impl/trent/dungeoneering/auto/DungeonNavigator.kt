package com.projectx.script.impl.trent.dungeoneering.auto

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.dungeoneering.CriticalityAnalyzer
import com.projectx.script.impl.trent.dungeoneering.DoorKey
import com.projectx.script.impl.trent.dungeoneering.DungeonContext
import com.projectx.script.impl.trent.dungeoneering.DungeonRefusals
import com.projectx.script.impl.trent.dungeoneering.DungeonSession
import com.projectx.script.impl.trent.dungeoneering.DungeonTables
import com.projectx.script.impl.trent.dungeoneering.map.DoorClass
import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapModel
import com.projectx.script.impl.trent.dungeoneering.map.MapCell
import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import com.projectx.script.impl.trent.dungeoneering.puzzle.IceRoom
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile
import kotlin.math.abs

/**
 * Movement for the auto-solver. In Daemonheim you CANNOT walk from room to room - every wall carries a
 * closed door that must be opened (plain "Door"/"Guardian door" -> "Enter"; key door -> "Unlock" when the
 * key is held; skill door -> its force action when the level is met). So navigation is: pick the next cell
 * toward the target, find the door on the wall between here and there, and open it. Ground keys inside the
 * current room are picked up directly.
 */
object DungeonNavigator {

    private const val ROOM_TILES = 16
    private const val HALF_ROOM = ROOM_TILES / 2
    private const val DOOR_AUTO_CROSS_MS = 3500L
    private const val ENTER_RETRIES = 3

    // A server-pathed interact only fires when the target is within the client's interaction range; a door on
    // the far side of a room fails silently (nothing sent), so we'd never walk to it. Walk within this many
    // tiles first - plain WALK reaches any in-scene tile - then the interact lands.
    private const val APPROACH_RANGE = 4

    private var lastForcedFrom: Pair<Int, Int>? = null

    private val NEIGHBOUR_DELTAS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

    // Doors the server has refused, by tile. EVERY path that touches a door records here and consults it, not
    // just the routed one: the stuck-recovery and the stranded-door recovery poke doors of their own accord,
    // and while their refusals went unread they had no exit condition at all - the bot alternated two adjacent
    // locs seventy-two times without moving, because nothing either path did could ever mark them dead.
    private val refusedDoors = HashSet<Pair<Int, Int>>()

    // Doors a blind forced crossing has already failed to open. Deliberately NOT in refusedDoors: a skill/key
    // barrier shares its door's wall tile, so blacklisting that tile for not answering a bare "Enter" hides
    // the barrier the router can legitimately clear and seals every room beyond that wall for the whole floor.
    private val deadForcedDoors = HashSet<Pair<Int, Int>>()

    fun forgetRefusedDoors() {
        refusedDoors.clear()
        deadForcedDoors.clear()
    }

    private fun refused(obj: SceneObject) = (obj.tile.x to obj.tile.y) in refusedDoors

    /** Records a door the server just refused, so no path picks it again this floor. */
    private fun noteRefusal(obj: SceneObject, since: Long): Boolean {
        // "You can't reach that" is the server saying it cannot path us to this loc - a door on the far side of
        // an unsolved puzzle answers that forever. Reading only hard refusals left the navigator walking at the
        // same door every few seconds indefinitely. Cleared with the floor, like every other refusal.
        val why = DungeonRefusals.passageRefusedSince(since)
            ?: DungeonRefusals.unreachableSince(since)
            ?: return false
        if (refusedDoors.add(obj.tile.x to obj.tile.y)) {
            println("DUNG-NAV: door ${obj.id} at (${obj.tile.x},${obj.tile.y}) refused - \"$why\"; never trying it again")
        }
        return true
    }

    // True when this wall HAS door-like locs but every one of them is a door the server has refused to let us
    // reach. The passage is shut for now, but that is our delivery failing - never evidence the room beyond is
    // unreachable, so it must not feed the cell ban.
    fun wallDoorsAllRefused(session: DungeonSession, nextCell: Pair<Int, Int>): Boolean {
        val wall = wallTileToward(session, nextCell) ?: return false
        val room = roomOf(localPlayer.tile)
        val onWall = getAllObjectsWithinRange(20)
            .filter { isDoorLike(it.name()) && it.tile.getDistance(wall) <= 5 && roomOf(it.tile) == room }
        return onWall.isNotEmpty() && onWall.all { refused(it) }
    }

    fun currentCell(session: DungeonSession): MapCell? =
        session.currentCell?.let { session.map.get(it.first, it.second) }

    // Where we ultimately want to reach: the GO marker if set, else the boss cell. The boss fallback skips
    // the analyzer's candidate list entirely, so it has to re-check the ban itself - otherwise a boss room
    // written off after repeated deaths is walked straight back into the moment the frontier runs dry, which
    // is exactly what happened after the night-gazer banned its cell.
    fun targetCell(session: DungeonSession): Pair<Int, Int>? =
        session.map.goTarget?.let { it.gx to it.gy }
            ?: session.map.bossCell()
                ?.let { it.gx to it.gy }
                ?.takeIf { it !in session.bannedCells }

    // The next cell to step to from the player's cell toward `target` via the connectivity graph, or
    // `target` itself when it isn't a drawn/connected cell.
    fun nextStepCell(session: DungeonSession, target: Pair<Int, Int>): Pair<Int, Int> {
        val from = currentCell(session) ?: return target
        val to = session.map.get(target.first, target.second) ?: return target
        val step = CriticalityAnalyzer.nextStep(session.map, from, to, session.blockedEdges) ?: return target
        return step.gx to step.gy
    }

    // The end-of-floor trapdoor only unlocks (exposing "End dungeon") once the boss is dead, so its mere
    // presence in the player's room is the floor-complete signal. The interact walks the avatar to it. Scoped
    // to the current room so we never poke a ladder seen through a wall from an adjacent room.
    suspend fun endDungeon(script: Script): Boolean {
        val room = roomOf(localPlayer.tile)
        val ladder = getAllObjectsWithinRange(20)
            .firstOrNull { it.hasOption("End dungeon") && roomOf(it.tile) == room } ?: return false
        // On an ice room the ladder is unreachable by a normal click (you slide past it) - slide up to it first.
        if (IceRoom.onIce()) IceRoom.slideTo(script, ladder.tile) else approach(script, ladder)
        if (!ladder.interact("End dungeon")) return false
        script.waitUntilNotMoving()
        return true
    }

    // A room where killing is mandatory to progress: the boss room, or a guardian room (a guardian door
    // belongs to it and stays sealed until its monsters are dead). Elsewhere, monsters are skippable.
    fun currentRoomIsCombatRequired(session: DungeonSession): Boolean {
        if (currentCell(session)?.boss == true) return true
        return currentRoomHasGuardianDoor()
    }

    // A guardian door bordering this room - its tile in this room OR one step across a wall (an exit door's
    // tile sits on the far side of the boundary) - gates it: the door stays sealed until this room's monsters
    // are dead. Read the live scene, not the scanner cache, so it's never stale mid-fight.
    private fun currentRoomHasGuardianDoor(): Boolean {
        val room = roomOf(localPlayer.tile)
        return getAllObjectsWithinRange(ROOM_TILES).any { it.name() == "Guardian door" && bordersRoom(room, it.tile) }
    }

    private fun bordersRoom(room: Pair<Int, Int>, tile: Tile): Boolean {
        val dr = roomOf(tile)
        return abs(dr.first - room.first) + abs(dr.second - room.second) <= 1
    }

    // Last-resort anti-stuck: cross ANY open door in our ACTUAL world room (scene-based, IGNORING the cell
    // math - the calibration itself may be what's wrong, e.g. a stale post-death minimap pip that maps our
    // real room onto the boss cell so we think we already arrived). Moving through a door refreshes the pip,
    // so the calibration re-anchors and normal navigation resumes. Never shove through a guardian door with
    // monsters still up.
    //
    // The nearest door is the one we just came through, so picking purely by distance makes two adjacent
    // rooms bounce the avatar between them forever - each room's recovery pointing straight back at the
    // other. The exclusion is therefore absolute: with no candidate left this recovery declines rather than
    // re-taking the door it just came through, because a fallback that ignores the exclusion IS the bounce.
    suspend fun forceAnyDoor(script: Script): Boolean {
        val room = roomOf(localPlayer.tile)
        val door = getAllObjectsWithinRange(20)
            .filter {
                roomOf(it.tile) == room && (it.name() == "Door" || it.name() == "Guardian door") &&
                    it.hasOption("Enter") && !refused(it) &&
                    (it.tile.x to it.tile.y) !in deadForcedDoors &&
                    doorLeadsTo(room, it.tile) != lastForcedFrom
            }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) } ?: return false
        if (door.name() == "Guardian door" && roomHasLiveMonster()) return false
        approach(script, door)
        val attemptedAt = System.currentTimeMillis()
        if (!door.interact("Enter")) return false
        script.waitUntilNotMoving()
        script.delayUntil(gaussian(DOOR_AUTO_CROSS_MS, 700L)) { crossed(room) }
        if (crossed(room)) {
            lastForcedFrom = room
            return true
        }
        noteRefusal(door, attemptedAt)
        deadForcedDoors += door.tile.x to door.tile.y
        return false
    }

    // A cell whose gate is a skill door we are allowed to force. Failing to force one is the door's own
    // mechanic - an attempt may fail and be repeated - so a failed crossing here is never evidence that the
    // room behind it is unreachable. BOOSTABLE is included because it is exactly the boostMargin config's
    // reach; IMPOSSIBLE (beyond that reach) is not, and keeps the ordinary dead-passage treatment.
    fun gatedByForceableSkillDoor(map: DungeonMapModel, cell: Pair<Int, Int>): Boolean {
        val gate = map.get(cell.first, cell.second) ?: return false
        if (gate.skillDoorSkill == null) return false
        return gate.doorClass == DoorClass.REACHABLE || gate.doorClass == DoorClass.BOOSTABLE ||
            gate.doorClass == DoorClass.UNKNOWN
    }

    // Normal routing is working again, so the anti-bounce memory has done its job and must not bias the next
    // genuine recovery.
    fun clearForcedMemory() {
        lastForcedFrom = null
    }

    // The room on the far side of a door, from which room wall its tile sits on.
    internal fun doorLeadsTo(room: Pair<Int, Int>, tile: Tile): Pair<Int, Int>? {
        val baseX = room.first * ROOM_TILES
        val baseY = room.second * ROOM_TILES
        return when {
            tile.x == baseX -> room.first - 1 to room.second
            tile.x == baseX + ROOM_TILES - 1 -> room.first + 1 to room.second
            tile.y == baseY -> room.first to room.second - 1
            tile.y == baseY + ROOM_TILES - 1 -> room.first to room.second + 1
            else -> null
        }
    }

    fun roomHasLiveMonster(): Boolean = roomMonsterHealth() > 0

    // Total health of everything in this room still worth waiting on. A monster combat has written off is not
    // one of them - blocking on a target combat has explicitly stopped fighting is a deadlock, not patience.
    // Zero means the room is clear; a value that stops changing means the fight has stopped progressing.
    fun roomMonsterHealth(): Int {
        val room = roomOf(localPlayer.tile)
        return allNpcsWithinRange(ROOM_TILES) {
            it.hasOption("Attack") && it.currentHealth > 0 && roomOf(it.tile) == room &&
                !AbandonedMonsters.isAbandoned(it.serverIndex)
        }.sumOf { it.currentHealth }
    }

    // Pick up a ground key (in the current room). Dungeon keys go on the keyring and holding one is what
    // unlocks its key door. Fire the ground-item opcode BY INDEX (Take = op 0) so it routes through
    // DoActionOpcode.withMenuOpen (sets jag::MiniMenu.menuOpen=1) for an immediate Take, never area-loot.
    // The interact walks the avatar to the item, so there is no walkTo here.
    suspend fun pickUpGroundItemAt(script: Script, x: Int, y: Int): Boolean {
        val item = groundItems.firstOrNull { it.tile.x == x && it.tile.y == y } ?: return false
        val takeOp = item.groundOps.indexOfFirst { it?.equals("Take", ignoreCase = true) == true }
            .let { if (it >= 0) it else 0 }
        if (!item.interact(takeOp)) return false
        script.waitUntilNotMoving()
        return true
    }

    // Pass into the room toward `nextCell` by clearing the wall between here and there - the ONLY way between
    // rooms in Daemonheim (you never walk between rooms). A key/skill door is TWO separate locs on the wall:
    // a lock BARRIER (has "Unlock" / a skill-force, but NO "Enter") rendered in front of the actual DOOR (has
    // "Enter"). Unlocking/forcing the barrier removes it; then the door is Entered to cross. Plain/guardian
    // doors are a single "Enter" loc with no barrier. The interact walks the avatar to the loc within the
    // current room, so there is no walkTo here.
    suspend fun openDoorToward(script: Script, session: DungeonSession, nextCell: Pair<Int, Int>, boostMargin: Int): Boolean {
        val wall = wallTileToward(session, nextCell) ?: return false
        // On an ice room the door is unreachable by a normal click (you slide past it) - hand off to IceRoom to
        // slide up beside it first; nothing sliding-specific lives in this navigator.
        if (IceRoom.onIce()) {
            val door = doorsOnWall(wall).minByOrNull { it.tile.getDistance(localPlayer.tile) }
            IceRoom.slideTo(script, door?.tile ?: wall)
        }
        return passWall(script, session, wall, boostMargin)
    }

    private suspend fun passWall(script: Script, session: DungeonSession, wall: Tile, boostMargin: Int): Boolean {
        val roomBefore = roomOf(localPlayer.tile)
        val doors = doorsOnWall(wall)

        // A guardian door stays sealed until THIS room's monsters are dead - never poke it while they live;
        // the combat loop clears them first, then the door opens.
        if (doors.any { it.name() == "Guardian door" } && roomHasLiveMonster()) return false

        // A lock barrier (Unlock / skill-force, no "Enter") blocks the door behind it - clear it first. Only a
        // real LOCK counts: a plain door that merely lacks "Enter" (a decorative / already-passed door on the
        // same wall) is NOT a barrier and must not abort the passable door beside it.
        val barrier = doors.firstOrNull { !it.hasOption("Enter") && isLockBarrier(it) }
        if (barrier != null) {
            val action = barrierAction(session, barrier, boostMargin) ?: return false
            val bId = barrier.id
            val bx = barrier.tile.x
            val by = barrier.tile.y
            approach(script, barrier)
            val barrierAt = System.currentTimeMillis()
            if (!barrier.interact(action)) return false
            script.waitUntilNotMoving()
            // A barrier the server has refused outright will never answer an Unlock, and retrying it just
            // alternates with the door behind it. Mark it and leave.
            if (noteRefusal(barrier, barrierAt)) return false
            // The client normally auto-"Enter"s the door once the barrier clears; wait for that cross.
            script.delayUntil(gaussian(DOOR_AUTO_CROSS_MS, 700L)) { crossed(roomBefore) }
            if (crossed(roomBefore)) return true
            // Barrier still present => the unlock/force didn't take (e.g. no key) - nothing more to do here.
            if (objectPresent(bId, bx, by)) return false
            // Barrier gone but not crossed (auto-Enter eaten by an interruption) -> drive the Enter ourselves.
        }

        val door = doorsOnWall(wall).firstOrNull { it.hasOption("Enter") } ?: return crossed(roomBefore)
        repeat(ENTER_RETRIES) {
            approach(script, door)
            val enterAt = System.currentTimeMillis()
            if (!door.interact("Enter")) return crossed(roomBefore)
            script.waitUntilNotMoving()
            script.delayUntil(gaussian(DOOR_AUTO_CROSS_MS, 700L)) { crossed(roomBefore) }
            if (crossed(roomBefore)) return true
            if (noteRefusal(door, enterAt)) return false
        }
        return crossed(roomBefore)
    }

    // Walk up to a door/loc before interacting it - a far interact fails silently (out of the client's
    // interaction range). No-op when already within reach. On ice the caller has already slid us adjacent.
    private suspend fun approach(script: Script, obj: SceneObject) {
        if (obj.tile.getDistance(localPlayer.tile) <= APPROACH_RANGE) return
        walkTo(obj.tile, false)
        script.waitUntilNotMoving()
    }

    // Recovery for a key door that was unlocked (its lock Loc consumed + removed) but whose automatic
    // "Enter" got eaten by an interruption (e.g. an auto-retaliate): the next room never rendered, the
    // minimap still reads the door locked, and the analyzer gives no objective - so the door sits open but
    // uncrossed. Find such a door (a map key-door cell whose lock Loc is gone in the world but still has a
    // passable door), navigate back to the rendered room beside it, and drive the "Enter" to finally cross.
    suspend fun recoverStrandedDoor(script: Script, session: DungeonSession, boostMargin: Int): Boolean {
        val map = session.map
        val here = currentCell(session) ?: return false
        for (cell in map.all) {
            if (cell.keyDoorObjId <= 0) continue
            val neighbour = NEIGHBOUR_DELTAS.firstNotNullOfOrNull { (dx, dy) ->
                map.get(cell.gx + dx, cell.gy + dy)?.takeIf { !it.unknownRoom && it.keyDoorObjId <= 0 }
            } ?: continue
            val wall = wallBetween(session, neighbour.gx to neighbour.gy, cell.gx to cell.gy) ?: continue
            val onWall = getAllObjectsWithinRange(60).filter { isDoorLike(it.name()) && it.tile.getDistance(wall) <= 5 }
            // A barrier still present (a door-like loc WITHOUT "Enter" = the Unlock/force lock) => still locked.
            if (onWall.any { !it.hasOption("Enter") }) continue
            if (onWall.none { it.hasOption("Enter") }) continue // no passable door here yet (room not loaded)

            if (here.gx == neighbour.gx && here.gy == neighbour.gy) {
                return passWall(script, session, wall, boostMargin)
            }
            return openDoorToward(script, session, nextStepCell(session, neighbour.gx to neighbour.gy), boostMargin)
        }
        return false
    }

    private fun crossed(roomBefore: Pair<Int, Int>): Boolean = roomOf(localPlayer.tile) != roomBefore

    // The wall-midpoint tile of `fromCell`'s room on the side facing `toCell` (via the reverse calibration),
    // for any pair of cells - not just the player's current room (that is `wallTileToward`).
    private fun wallBetween(session: DungeonSession, fromCell: Pair<Int, Int>, toCell: Pair<Int, Int>): Tile? {
        val fromRoom = session.calibration.worldRoomFor(fromCell) ?: return null
        val toRoom = session.calibration.worldRoomFor(toCell) ?: return null
        val baseX = fromRoom.first * ROOM_TILES
        val baseY = fromRoom.second * ROOM_TILES
        val plane = localPlayer.tile.plane
        return when {
            toRoom.first > fromRoom.first -> Tile.of(baseX + ROOM_TILES - 1, baseY + HALF_ROOM, plane)
            toRoom.first < fromRoom.first -> Tile.of(baseX, baseY + HALF_ROOM, plane)
            toRoom.second > fromRoom.second -> Tile.of(baseX + HALF_ROOM, baseY + ROOM_TILES - 1, plane)
            toRoom.second < fromRoom.second -> Tile.of(baseX + HALF_ROOM, baseY, plane)
            else -> null
        }
    }

    private fun objectPresent(id: Int, x: Int, y: Int): Boolean =
        getAllObjectsWithinRange(20).any { it.id == id && it.tile.x == x && it.tile.y == y }

    // Every door-like loc on `wall` on OUR side of it (barrier + the door behind it share the wall). Doors
    // are kept only if they sit in the player's own room - the copy across the wall belongs to the next room
    // and is out of reach, and clicking it just wastes ticks. Closest to the wall midpoint first, so the door
    // aligned with this passage wins over an offset one from an adjacent doorway.
    private fun doorsOnWall(wall: Tile): List<SceneObject> {
        val room = roomOf(localPlayer.tile)
        return getAllObjectsWithinRange(20)
            .filter { isDoorLike(it.name()) && it.tile.getDistance(wall) <= 5 && roomOf(it.tile) == room && !refused(it) }
            .sortedBy { it.tile.getDistance(wall) }
    }

    // The wall-midpoint tile of the player's current room, on the side facing `nextCell` (via the world
    // room delta from the reverse calibration). The door on that wall sits at/near this tile.
    internal fun wallTileToward(session: DungeonSession, nextCell: Pair<Int, Int>): Tile? {
        val playerRoom = roomOf(localPlayer.tile)
        val nextRoom = session.calibration.worldRoomFor(nextCell) ?: return null
        val baseX = playerRoom.first * ROOM_TILES
        val baseY = playerRoom.second * ROOM_TILES
        val plane = localPlayer.tile.plane
        return when {
            nextRoom.first > playerRoom.first -> Tile.of(baseX + ROOM_TILES - 1, baseY + HALF_ROOM, plane) // east
            nextRoom.first < playerRoom.first -> Tile.of(baseX, baseY + HALF_ROOM, plane)                  // west
            nextRoom.second > playerRoom.second -> Tile.of(baseX + HALF_ROOM, baseY + ROOM_TILES - 1, plane) // north (higher Y)
            nextRoom.second < playerRoom.second -> Tile.of(baseX + HALF_ROOM, baseY, plane)                 // south
            else -> null
        }
    }

    private fun isDoorLike(name: String): Boolean =
        name == "Door" || name == "Guardian door" ||
            DungeonTables.KEY_DOOR_NAME.matches(name) || DungeonTables.skillDoor(name) != null

    // A real lock in front of a door: a key door (has "Unlock") or a skill door (its force action). A plain
    // door without "Enter" is not a lock - it's just not the passable loc on this wall.
    private fun isLockBarrier(loc: SceneObject): Boolean =
        loc.hasOption("Unlock") || DungeonTables.skillDoor(loc.name()) != null

    // The action that clears a lock BARRIER now, or null if we can't. A key barrier "Unlock"s only when its
    // key is held; a skill barrier forces only when the (examined) requirement is within reach + boost.
    private fun barrierAction(session: DungeonSession, barrier: SceneObject, boostMargin: Int): String? {
        val name = barrier.name()
        if (barrier.hasOption("Unlock")) {
            return if (DungeonTables.keyNameForDoor(name) in MapIcons.heldKeyNames()) "Unlock" else null
        }
        val info = DungeonTables.skillDoor(name) ?: return null
        if (!barrier.hasOption(info.lockedAction)) return null
        val req = session.doors.requirement(DoorKey(barrier.id, barrier.tile.x, barrier.tile.y))
        val level = req?.level ?: return null
        val skill = req.skill ?: info.skill
        return if (session.entryLevel(skill) + boostMargin >= level) info.lockedAction else null
    }

    private fun roomOf(tile: Tile) = (tile.x / ROOM_TILES) to (tile.y / ROOM_TILES)
}
