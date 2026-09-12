package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill
import com.projectx.script.api.getRealLevel
import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapModel
import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapReader
import com.projectx.script.impl.trent.dungeoneering.map.MapWorldCalibration
import world.gregs.voidps.type.Tile

class DungeonSession(val seed: Int) {
    val entryLevels: Map<Skill, Int> = Skill.entries.associateWith { getRealLevel(it) }
    val doors = DoorKnowledge()
    val map = DungeonMapModel()
    val calibration = MapWorldCalibration()
    val reader = DungeonMapReader(map, calibration)

    // Resources are static locs; remember every one seen this dungeon so map markers don't blink
    // out as they leave scan range. Cleared on the next seed change with the whole session.
    val resourceCache = LinkedHashMap<Tile, TrackedResource>()

    // The player's current map cell (from the map pip), used to place resources and rank frontier doors.
    var currentCell: Pair<Int, Int>? = null

    // Every map cell the player has actually stood in this dungeon. A revealed room the player has NOT
    // visited is still worth entering (it may hold a key/resource or reveal further doors), so exploration
    // targets unvisited reachable rooms - not just undrawn frontiers.
    val visited = HashSet<Pair<Int, Int>>()

    // Directional cell->cell passages proven impassable this floor (the door is unreachable by real collision
    // / won't cross), packed via CriticalityAnalyzer.edgeKey. The router avoids them so it reroutes instead of
    // ping-ponging back through a dead door. Cleared with the session on the next seed.
    val blockedEdges = HashSet<Long>()

    // Map cells we've decided we can't clear this floor (an unsolvable puzzle room - e.g. a ferret room whose
    // trap needs Fletching/Hunter we lack, or a magic-gated convergence room). The analyzer paints them
    // LIKELY_BONUS so the frontier picker routes around them instead of stranding the bot at a locked door.
    val bannedCells = HashSet<Pair<Int, Int>>()

    // Deaths this floor, per map cell. A room that keeps killing us is as unclearable as a puzzle we can't
    // solve - and unlike a stalled door it costs XP every cycle - so past a limit its cell is banned too.
    val deathsByCell = HashMap<Pair<Int, Int>, Int>()

    // Which neighbours we have failed to enter a cell FROM. One failed crossing proves that edge did not work
    // on that attempt, not that the room is unreachable - so a cell is only written off once several distinct
    // approaches have failed. Repeats are kept: the same door failing again after a re-route is a real strike.
    val failedApproaches = HashMap<Pair<Int, Int>, MutableList<Pair<Int, Int>>>()

    /**
     * The ONLY way a cell is written off. Every ban permanently shrinks the floor, so all of them report
     * through here under one prefix - a ban that has to be inferred from a solver's own wording is a ban
     * nobody can audit.
     */
    fun ban(cell: Pair<Int, Int>?, reason: String) {
        if (cell == null) return
        if (bannedCells.add(cell)) println("DUNG-BAN: room $cell - $reason")
    }

    // One start-of-floor supply run (buy dungeoneering feathers from the Smuggler) per floor. Reset with the
    // session on the next seed, so every new floor re-stocks before exploring.
    var suppliesBought = false

    fun entryLevel(skill: Skill): Int = entryLevels[skill] ?: 0

    fun attainable(skill: Skill, level: Int): Boolean = level <= entryLevel(skill)
}
