package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill
import com.projectx.script.impl.trent.dungeoneering.map.DoorClass
import com.projectx.script.impl.trent.dungeoneering.map.DungeonMapModel
import com.projectx.script.impl.trent.dungeoneering.map.GoTarget
import com.projectx.script.impl.trent.dungeoneering.map.MapCell
import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import com.projectx.script.impl.trent.dungeoneering.map.RoomClass
import world.gregs.voidps.type.Tile

private data class Dir(val bit: Int, val dx: Int, val dy: Int, val opposite: Int)

// `cell` is the reachable room used for scoring/distance; `targetGx/Gy` is where the GO marker is drawn —
// the room itself for a concrete lead, or the UNDRAWN cell past an unopened door for exploration (so the
// marker points onward instead of at the room the player is already standing in).
private class Candidate(val cell: MapCell, val score: Int, val reason: String, val targetGx: Int, val targetGy: Int)

object CriticalityAnalyzer {

    // Points subtracted per room of travel when ranking GO candidates. Turns the pick from "highest category
    // anywhere on the floor" (which beelined to one openable key door 500 tiles away every time, ignoring work
    // underfoot) into "best thing near us". Big enough that a far high-value lead yields to nearby exploration,
    // small enough that a genuinely close key/skill door still wins.
    private const val DIST_WEIGHT = 5

    private val DIRS = listOf(
        Dir(MapIcons.NORTH, 0, -1, MapIcons.SOUTH),
        Dir(MapIcons.EAST, 1, 0, MapIcons.WEST),
        Dir(MapIcons.SOUTH, 0, 1, MapIcons.NORTH),
        Dir(MapIcons.WEST, -1, 0, MapIcons.EAST)
    )

    fun recompute(session: DungeonSession, boostMargin: Int) {
        val model = session.map
        model.resetCriticality()
        model.dirty = false

        classifyKeyDoors(model)
        classifySkillDoors(session, model, boostMargin)
        deactivateResolvedDoors(model)

        val start = model.startCell()
        start?.roomClass = RoomClass.CONFIRMED_CRITICAL
        markConfirmedBossPath(model, start)
        // Rooms a puzzle solver gave up on (unsolvable for us this floor) are off-path — paint them bonus so
        // the frontier picker routes around their locked door instead of stranding us at it. Applied after the
        // boss path so an unsolvable room never masquerades as critical, and before the bonus cascade below.
        for (b in session.bannedCells) model.get(b.first, b.second)?.roomClass = RoomClass.LIKELY_BONUS
        // Off-path shading floods from a fixed root. The start room is the natural one, but it's frequently
        // never decoded as a start ICON (it gets read as an ordinary room shape first), which used to disable
        // ALL shading for the whole floor. Fall back to the player's own cell: "off-path" then means
        // "reachable only by a detour from where I am", which is what we want to avoid exploring.
        // Bonus is derived ONLY from doors we've PROVEN impassable (examined skill-door level vs ours) — never
        // from skilling nodes: their tier/placement isn't a reliable off-path signal (esp. F2P — a high-level
        // tree routinely sits ON the critical path), and using it blacklisted whole wings and the frontier.
        val root = start ?: session.currentCell?.let { model.get(it.first, it.second) }
        if (session.calibration.valid && root != null) {
            val goals = goalCells(model)
            shadeImpossibleDoorBranches(session, model, root, boostMargin, goals)
            propagateBehindBonus(session, model, root)
        }
        recommendFrontier(session, model, root)
    }

    // The things still worth reaching: the boss, open "?" frontiers to explore, and doors we can pass now.
    // An impossible-door branch is only sealed off when doing so leaves every one of these still reachable,
    // so a forced corridor to the boss or to unexplored ground is never mistakenly blacklisted.
    private fun goalCells(model: DungeonMapModel): Set<MapCell> {
        val goals = LinkedHashSet<MapCell>()
        model.bossCell()?.let { goals += it }
        for (cell in model.all) {
            if (isOpenFrontier(cell)) goals += cell
            if (cell.doorClass == DoorClass.OPEN_NOW || cell.doorClass == DoorClass.REACHABLE || cell.doorClass == DoorClass.BOOSTABLE) {
                goals += cell
            }
        }
        return goals
    }

    // Impossible-door rooms are off-path, so anything reachable from start ONLY by passing
    // through one is off-path too. Mark every drawn room that's connected to start but unreachable once
    // bonus rooms are treated as walls — so GO (which skips bonus cells) never routes behind a bonus room.
    private fun propagateBehindBonus(session: DungeonSession, model: DungeonMapModel, start: MapCell) {
        val connected = reachable(model, start, avoidBonus = false)
        val clean = reachable(model, start, avoidBonus = true)
        for (cell in connected) {
            if (cell in clean) continue
            if (eligibleForBonus(session, cell)) cell.roomClass = RoomClass.LIKELY_BONUS
        }
    }

    private fun isBonus(cell: MapCell) =
        cell.roomClass == RoomClass.LIKELY_BONUS || cell.roomClass == RoomClass.CONFIRMED_BONUS

    private fun reachable(model: DungeonMapModel, start: MapCell, avoidBonus: Boolean): Set<MapCell> {
        val reached = LinkedHashSet<MapCell>()
        reached += start
        val queue = ArrayDeque<MapCell>()
        queue += start
        while (queue.isNotEmpty()) {
            for (next in neighbours(model, queue.removeFirst())) {
                if (avoidBonus && isBonus(next)) continue
                if (reached.add(next)) queue += next
            }
        }
        return reached
    }

    private fun classifyKeyDoors(model: DungeonMapModel) {
        val held = MapIcons.heldKeyObjs()
        for (cell in model.all) {
            if (cell.keyDoorObjId <= 0) continue
            cell.doorClass = if (cell.keyDoorObjId in held) DoorClass.OPEN_NOW else DoorClass.NEED_KEY
        }
    }

    private fun classifySkillDoors(session: DungeonSession, model: DungeonMapModel, boostMargin: Int) {
        val iconCells = model.all.filter { it.skillDoorSkill != null }
        for ((key, requirement) in session.doors.entries()) {
            val level = requirement.level ?: continue
            val skill = requirement.skill ?: continue
            val cell = skillIconCellFor(session, iconCells, key, skill) ?: continue
            val effectiveMax = session.entryLevel(skill)
            cell.doorLevel = level
            cell.doorClass = when {
                session.doors.isPassable(key) || level <= effectiveMax -> DoorClass.REACHABLE
                level <= effectiveMax + boostMargin -> DoorClass.BOOSTABLE
                else -> DoorClass.IMPOSSIBLE
            }
        }
        for (cell in model.all) {
            if (cell.skillDoorSkill != null && cell.doorClass == DoorClass.NONE) cell.doorClass = DoorClass.UNKNOWN
        }
    }

    // Match an examined door requirement to the map cell drawing its skill icon. A skill can gate MORE THAN
    // ONE door on a floor, so we can't pick "the single cell for this skill". Nearest-cell matching is
    // ambiguous — a door tile is equidistant to the icon cells on BOTH sides of its wall, so two doors of one
    // skill can collapse onto the same cell (one shown wrong, the other blank). Instead resolve it by the wall
    // the door sits on: the icon is drawn on the room the door GATES, exactly one cell across that wall from
    // the door tile's own cell (same geometry as the impossible-branch flood). Deterministic, one door → one
    // cell. Falls back to the lone same-skill cell only when the door tile can't be placed yet.
    private fun skillIconCellFor(session: DungeonSession, iconCells: List<MapCell>, key: DoorKey, skill: Skill): MapCell? {
        val ofSkill = iconCells.filter { it.skillDoorSkill == skill }
        if (ofSkill.size <= 1) return ofSkill.firstOrNull()
        val near = session.calibration.cellFor(Tile.of(key.x, key.y, 0)) ?: return null
        val (dx, dy) = blockedMapDir(key.x, key.y)
        return ofSkill.firstOrNull { it.gx == near.first + dx && it.gy == near.second + dy }
            ?: ofSkill.firstOrNull { it.gx == near.first && it.gy == near.second }
    }

    // Boss found + graph-connected to start ⇒ every room on the start→boss route is the critical path.
    private fun markConfirmedBossPath(model: DungeonMapModel, start: MapCell?) {
        val boss = model.bossCell()
        model.bossFound = boss != null
        boss?.roomClass = RoomClass.CONFIRMED_CRITICAL
        if (start == null || boss == null || start == boss) return
        val path = shortestPath(model, start, boss) ?: return
        for (cell in path) {
            cell.onCriticalPath = true
            cell.roomClass = RoomClass.CONFIRMED_CRITICAL
        }
    }

    // An impossible skill door blocks the wall between its room and the room across it. Any drawn room
    // that becomes unreachable from start once that single edge is cut is behind the door ⇒ bonus. The
    // blocked edge comes from the door's own world tile (which room wall it sits on), so it needs the
    // reliable pip calibration, not the map icon.
    private fun shadeImpossibleDoorBranches(session: DungeonSession, model: DungeonMapModel, start: MapCell, boostMargin: Int, goals: Set<MapCell>) {
        // Only the boss and doors we can pass NOW must stay reachable; an unexplored "?" behind the door is
        // legitimately sealed off and should be marked, so it is not treated as a must-reach goal here.
        val hardGoals = goals.filter { it.boss || hasUsableDoor(it) }
        for ((key, requirement) in session.doors.entries()) {
            if (session.doors.isPassable(key)) continue
            val level = requirement.level ?: continue
            val skill = requirement.skill ?: continue
            if (level <= session.entryLevel(skill) + boostMargin) continue
            val near = session.calibration.cellFor(Tile.of(key.x, key.y, 0))?.let { model.get(it.first, it.second) } ?: continue
            val (dx, dy) = blockedMapDir(key.x, key.y)
            val far = model.get(near.gx + dx, near.gy + dy) ?: continue
            val reachable = floodBlockingEdge(model, start, near, far)
            // Boss / usable door behind an "impossible" door ⇒ our edge or level read is wrong; don't seal it.
            if (hardGoals.any { it !in reachable }) continue
            for (cell in model.all) {
                if (!cell.occupied || cell in reachable) continue
                // A room (including a "?" frontier) sealed behind a door you can't pass is off-path.
                if (canShadeBehindDoor(session, cell)) cell.roomClass = RoomClass.LIKELY_BONUS
            }
        }
    }

    private fun canShadeBehindDoor(session: DungeonSession, cell: MapCell): Boolean =
        !cell.start && !cell.boss && !cell.onCriticalPath &&
            cell.roomClass != RoomClass.CONFIRMED_CRITICAL &&
            (cell.gx to cell.gy) != session.currentCell

    // Which room wall the door tile sits on → the map direction across it. Rooms are 16 tiles; the door
    // is at/near an edge. World +Y is north = map −gy, so a south-edge door gates the cell below (+gy).
    private fun blockedMapDir(x: Int, y: Int): Pair<Int, Int> {
        val ox = ((x % 16) + 16) % 16
        val oy = ((y % 16) + 16) % 16
        val toWest = ox
        val toEast = 15 - ox
        val toNorth = 15 - oy
        val toSouth = oy
        return when (minOf(toWest, toEast, toNorth, toSouth)) {
            toWest -> -1 to 0
            toEast -> 1 to 0
            toNorth -> 0 to -1
            else -> 0 to 1
        }
    }

    private fun floodBlockingEdge(model: DungeonMapModel, start: MapCell, a: MapCell, b: MapCell): Set<MapCell> {
        val reached = LinkedHashSet<MapCell>()
        reached += start
        val queue = ArrayDeque<MapCell>()
        queue += start
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            for (next in neighbours(model, cell)) {
                if ((cell === a && next === b) || (cell === b && next === a)) continue
                if (reached.add(next)) queue += next
            }
        }
        return reached
    }

    // A "?" room gated by a door we can't pass this instant is not a real explore target, and fair to mark
    // off-path (e.g. the key room behind a resource room). That means: a key we don't hold (NEED_KEY), a skill
    // door above our reach (IMPOSSIBLE), a skill door not yet classified passable (UNKNOWN — unexamined), OR a
    // BOOSTABLE door — one needing a stat boost we realistically can't make (esp. at low Herblore); a boost on
    // the critical path is vanishingly rare, so a "?" behind one is treated as sealed, not a real lead. Only a
    // REACHABLE skill door (level met) or an openable key door counts as passable now.
    private fun isBlockedFrontier(cell: MapCell): Boolean =
        cell.doorClass == DoorClass.NEED_KEY || cell.doorClass == DoorClass.IMPOSSIBLE ||
            cell.doorClass == DoorClass.UNKNOWN || cell.doorClass == DoorClass.BOOSTABLE

    // A genuinely open "?" room — criticality unknown, must stay explorable and never off-path even
    // behind a bonus room. A key/skill-blocked "?" is NOT protected (see isBlockedFrontier).
    private fun isOpenFrontier(cell: MapCell): Boolean = cell.unknownRoom && !isBlockedFrontier(cell)

    private fun hasUsableDoor(cell: MapCell): Boolean =
        cell.doorClass == DoorClass.OPEN_NOW || cell.doorClass == DoorClass.REACHABLE

    private fun eligibleForBonus(session: DungeonSession, cell: MapCell): Boolean =
        !cell.start && !cell.boss && !isOpenFrontier(cell) && !cell.onCriticalPath && !hasUsableDoor(cell) &&
            cell.roomClass != RoomClass.CONFIRMED_CRITICAL &&
            (cell.gx to cell.gy) != session.currentCell

    private fun recommendFrontier(session: DungeonSession, model: DungeonMapModel, start: MapCell?) {
        val clean = start?.let { reachable(model, it, avoidBonus = true) }
        val candidates = collectCandidates(model, clean, session.visited)
        if (candidates.isEmpty()) return
        val origin = session.currentCell?.let { model.get(it.first, it.second) } ?: start
        val best = if (origin == null) {
            candidates.maxByOrNull { it.score }
        } else {
            // Rank by value discounted for travel, not raw category — "best nearby lead", so a far openable
            // door never outranks productive work right next to us. Unreachable candidates drop out.
            // A cell with no currently-unblocked approach is not somewhere to send the bot. Derived live from
            // blockedEdges, so it becomes a candidate again the moment they clear — this replaces what the ban
            // used to do without writing anything off. No fallback: if nothing is reachable there is genuinely
            // no objective, and the escalation ladder is the honest answer rather than a cell we cannot enter.
            candidates
                .map { it to distance(model, origin, it.cell, session.blockedEdges) }
                .filter { it.second != Int.MAX_VALUE }
                .maxByOrNull { it.first.score - DIST_WEIGHT * it.second }
                ?.first
        }
        best?.let { model.goTarget = GoTarget(it.targetGx, it.targetGy, it.reason) }
    }

    // Test seam: the objectives the frontier picker is offered for this model, in collection order.
    internal fun candidateReasons(model: DungeonMapModel): List<String> =
        collectCandidates(model, null, emptySet()).map { it.reason }

    private fun collectCandidates(model: DungeonMapModel, clean: Set<MapCell>?, visited: Set<Pair<Int, Int>>): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        // The boss outranks everything, but a banned boss cell (one that has killed us repeatedly) must drop
        // out like any other banned room — nothing else paints a boss cell LIKELY_BONUS, so this only fires
        // when the bot has explicitly given the fight up.
        model.bossCell()
            ?.takeIf { it.roomClass != RoomClass.LIKELY_BONUS }
            ?.let { candidates += Candidate(it, 1000, "boss", it.gx, it.gy) }
        for (cell in model.all) {
            if (cell.start) continue
            if (cell.roomClass == RoomClass.LIKELY_BONUS || cell.roomClass == RoomClass.CONFIRMED_BONUS) continue
            if (cell.unknownRoom) {
                // Only OPEN "?" rooms are GO targets. One gated by a door we can't pass (or a key we don't
                // hold) is not somewhere to send the player — ringing it just says "go stand at a door you
                // can't open". Prefer a frontier reachable without crossing a bonus room.
                if (!isBlockedFrontier(cell)) {
                    val score = if (clean == null || cell in clean) 60 else 15
                    candidates += Candidate(cell, score, "explore", cell.gx, cell.gy)
                }
                continue
            }
            // Opening a door onto UNDRAWN ground reveals brand-new rooms — the real point of exploring, so it
            // outranks re-entering an already-revealed room. A door is a lead only while it still gates
            // unexplored ground AND we can pass it now; locked/impassable/unexamined doors are never GO targets.
            val onward = unexploredDir(model, cell)
            if (cell.doorClass == DoorClass.NONE) {
                onward?.let { candidates += Candidate(cell, 65, "unexplored", cell.gx + it.dx, cell.gy + it.dy) }
            } else if (onward != null) {
                when (cell.doorClass) {
                    DoorClass.OPEN_NOW -> candidates += Candidate(cell, 80, "open the key door", cell.gx, cell.gy)
                    DoorClass.REACHABLE -> candidates += Candidate(cell, 75, "reachable skill door", cell.gx, cell.gy)
                    // BOOSTABLE = a door needing a stat boost we realistically can't make (esp. at this Herblore
                    // level); a boost on the critical path is vanishingly rare, so never send the bot to stand
                    // at one — let it route to the real path (an openable key door / puzzle) even if far.
                    else -> {}
                }
            }
            // Last resort: a revealed room we've never stood in whose drawn openings all face explored rooms.
            // Still worth a look (it may hold a key/resource) so the bot never idles, but far below opening new
            // ground — this is what used to make it wander into already-open rooms while real doors sat unopened.
            if (cell.occupied && (cell.gx to cell.gy) !in visited && onward == null) {
                candidates += Candidate(cell, 25, "explore room", cell.gx, cell.gy)
            }
        }
        return candidates
    }

    private fun unexploredDir(model: DungeonMapModel, cell: MapCell): Dir? =
        DIRS.firstOrNull { d -> cell.openings and d.bit != 0 && model.get(cell.gx + d.dx, cell.gy + d.dy) == null }

    // A skill/key door's map icon lingers after it's opened/passed (and a used key is no longer held, so it
    // would re-read as NEED_KEY). Once every side it opens onto is an already-explored room, the door is
    // resolved — drop its class so a cleared room stops being outlined red forever.
    private fun deactivateResolvedDoors(model: DungeonMapModel) {
        for (cell in model.all) {
            if (cell.doorClass == DoorClass.NONE) continue
            if (!gatesUnexplored(model, cell)) cell.doorClass = DoorClass.NONE
        }
    }

    // A door still gates unexplored ground if the cell it sits on is itself an unentered "?" room (the door
    // gates ENTRY into it — its own opening faces back at the explored room you're coming from), or if any
    // side it opens onto is undrawn / a "?" room. Only when the cell is a known room fully surrounded by
    // explored rooms is the door resolved.
    private fun gatesUnexplored(model: DungeonMapModel, cell: MapCell): Boolean =
        cell.unknownRoom || DIRS.any { d ->
            cell.openings and d.bit != 0 && model.get(cell.gx + d.dx, cell.gy + d.dy).let { it == null || it.unknownRoom }
        }

    private fun neighbours(model: DungeonMapModel, cell: MapCell, blockedEdges: Set<Long> = emptySet()): List<MapCell> =
        DIRS.mapNotNull { d ->
            val next = model.get(cell.gx + d.dx, cell.gy + d.dy) ?: return@mapNotNull null
            if (!connected(cell, next, d)) return@mapNotNull null
            if (edgeKey(cell.gx to cell.gy, next.gx to next.gy) in blockedEdges) return@mapNotNull null
            // A "?" room whose door we can't open right now (key we lack / level too low / unexamined) is a
            // wall for routing: cells reachable only past it must drop out of targeting, or the picker sends us
            // ping-ponging at a door we can't pass instead of at a genuinely reachable frontier.
            if (next.unknownRoom && isBlockedFrontier(next)) return@mapNotNull null
            next
        }

    // A passage exists iff at least one side actually shows a door there, and neither side is a fully-known
    // room that positively lacks it. A partial cell (start/boss/"?") missing the door isn't proof of no
    // passage, so an authoritative neighbour's decoded door is enough to connect them.
    private fun connected(a: MapCell, b: MapCell, dir: Dir): Boolean {
        val aHasDoor = a.openings and dir.bit != 0
        val bHasDoor = b.openings and dir.opposite != 0
        if (a.openingsKnown && !aHasDoor) return false
        if (b.openingsKnown && !bHasDoor) return false
        return aHasDoor || bHasDoor
    }

    // Test seam: does A connect to B, where B sits in direction `dirBit` (N/E/S/W) from A.
    internal fun connects(a: MapCell, b: MapCell, dirBit: Int): Boolean =
        DIRS.first { it.bit == dirBit }.let { connected(a, b, it) }

    private fun shortestPath(model: DungeonMapModel, start: MapCell, target: MapCell, blockedEdges: Set<Long> = emptySet()): List<MapCell>? {
        val prev = HashMap<MapCell, MapCell>()
        val seen = hashSetOf(start)
        val queue = ArrayDeque<MapCell>()
        queue += start
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (cell == target) {
                val path = ArrayList<MapCell>()
                var cursor: MapCell? = cell
                while (cursor != null) { path += cursor; cursor = prev[cursor] }
                return path
            }
            for (next in neighbours(model, cell)) {
                if (edgeKey(cell.gx to cell.gy, next.gx to next.gy) in blockedEdges) continue
                if (seen.add(next)) { prev[next] = cell; queue += next }
            }
        }
        return null
    }

    // Packs a directional cell→cell passage into a stable key so the navigator can mark it impassable and the
    // router can skip it (see DungeonSession.blockedEdges). Cell coords are small and may be negative.
    fun edgeKey(from: Pair<Int, Int>, to: Pair<Int, Int>): Long =
        ((from.first + 256).toLong() shl 48) or ((from.second + 256).toLong() shl 32) or
            ((to.first + 256).toLong() shl 16) or (to.second + 256).toLong()

    // Public nav helpers for the auto-solver. `shortestPath` returns [to .. from] (walked back via prev),
    // so the neighbour of `from` on that path is the second-to-last element = the next cell to head to.
    fun pathBetween(model: DungeonMapModel, from: MapCell, to: MapCell): List<MapCell>? =
        shortestPath(model, from, to)?.asReversed()

    fun nextStep(model: DungeonMapModel, from: MapCell, to: MapCell, blockedEdges: Set<Long> = emptySet()): MapCell? {
        val path = shortestPath(model, from, to, blockedEdges) ?: return null
        return if (path.size >= 2) path[path.size - 2] else null
    }

    private fun distance(model: DungeonMapModel, from: MapCell, to: MapCell, blockedEdges: Set<Long>): Int {
        if (from == to) return 0
        val dist = hashMapOf(from to 0)
        val queue = ArrayDeque<MapCell>()
        queue += from
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            val d = dist.getValue(cell)
            for (next in neighbours(model, cell, blockedEdges)) {
                if (next in dist) continue
                if (next == to) return d + 1
                dist[next] = d + 1
                queue += next
            }
        }
        return Int.MAX_VALUE
    }
}
