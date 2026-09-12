package com.projectx.script.impl.devin.zuk

import com.projectx.pathfinder.WorldCollision
import com.projectx.pathfinder.hasLineOfSight
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.collision.CollisionStrategies
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.getAllSpotAnimsWithinRange
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class ArenaLayout(val zuk: Tile) {
    val minX = zuk.x - 15
    val maxX = zuk.x + 15
    val minY = zuk.y - 35
    val maxY = zuk.y - 4

    operator fun contains(tile: Tile): Boolean = tile.x in minX..maxX && tile.y in minY..maxY
}

/**
 * Everything positional is measured from the anchor NPC rather than the player, which keeps the
 * overlay usable inside the instance where player coordinates and scene coordinates disagree.
 */
object ZukArena {

    private const val ANCHOR_SEARCH_RANGE = 60
    private const val HAZARD_SEARCH_RANGE = 40

    var layout: ArenaLayout? = null
        private set

    private var anchorSeenAt = 0L

    fun reset() {
        layout = null
        anchorSeenAt = 0
        lavaFalls.clear()
    }

    fun refresh() {
        scanLavaFalls()
        val anchor = allNpcsWithinRange(ANCHOR_SEARCH_RANGE) { it.exists() && it.anchorsArena() }
            .firstOrNull() ?: return
        anchorSeenAt = System.currentTimeMillis()
        val tile = runCatching { anchor.tile }.getOrNull() ?: return
        layout = ArenaLayout(zuk = tile)
    }

    /**
     * Ground lava is spot anims, not npcs — Har-Aken's falling lava and the HM wall's sweep tiles
     * both telegraph as gfx on the tiles they will burn, and each tile stays dangerous for a few
     * seconds after the marker, so sightings are held on a TTL rather than read live.
     * Concurrent: the bombardment star is written from the chat-event thread while the loop
     * thread scans and expires.
     */
    private val lavaFalls = ConcurrentHashMap<Tile, Long>()

    @Volatile
    private var rainUntil = 0L

    @Volatile
    private var wallUntil = 0L

    /** Read on the render thread for the wave-title banner, so they are volatile snapshots. */
    fun lavaRainActive(): Boolean = System.currentTimeMillis() < rainUntil

    fun lavaWallActive(): Boolean = System.currentTimeMillis() < wallUntil

    private fun scanLavaFalls() {
        val now = System.currentTimeMillis()
        runCatching {
            for (anim in getAllSpotAnimsWithinRange(HAZARD_SEARCH_RANGE) { it.id in ZukIds.LAVA_HAZARD_SPOTANIMS }) {
                runCatching {
                    lavaFalls[anim.tile] = now + ZukIds.LAVA_RAIN_HAZARD_MS
                    if (anim.id == ZukIds.LAVA_WALL_SPOTANIM) {
                        wallUntil = now + ZukIds.LAVA_RAIN_HAZARD_MS
                    } else {
                        rainUntil = now + ZukIds.LAVA_RAIN_HAZARD_MS
                    }
                }
            }
        }
        lavaFalls.values.removeIf { it < now }
    }

    /**
     * The bombardment pattern is fixed at the player's position the moment it is announced — a
     * plus, then the two diagonals across it — so the whole star is marked immediately, before any
     * lava is airborne, and dodging means simply stepping off the star. Arm reach approximates the
     * wiki's "run at least 3 tiles" with a tile of margin; the exact footprint gets measured from
     * the next capture's spot-anim stream.
     */
    fun markBombardment(centre: Tile) {
        val expiry = System.currentTimeMillis() + BOMBARDMENT_HAZARD_MS
        for (dx in -1..1) for (dy in -1..1) {
            lavaFalls[Tile.of(centre.x + dx, centre.y + dy, centre.plane)] = expiry
        }
        for ((dx, dy) in STAR_DIRECTIONS) {
            for (step in 2..BOMBARDMENT_ARM_TILES) {
                lavaFalls[Tile.of(centre.x + dx * step, centre.y + dy * step, centre.plane)] = expiry
            }
        }
        rainUntil = expiry
    }

    private val STAR_DIRECTIONS =
        listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private const val BOMBARDMENT_ARM_TILES = 4
    private const val BOMBARDMENT_HAZARD_MS = 8_000L

    /**
     * PvME hard-mode start positions, as offsets from the throne anchor (the arena's north end;
     * the 5×5 anchor tile is its SW corner, so +2 recentres). Quadrant-coarse on purpose — the
     * guide says "start north-east", so a small area is the suggestion, not a pixel; rock-precise
     * tiles ride the next capture's calibration. Igneous/challenge/Aken waves have no static
     * start and are absent.
     */
    private class StartHint(val label: String, val dx: Int, val dy: Int)

    private val START_HINTS = mapOf(
        1 to StartHint("EAST", 11, -16),
        2 to StartHint("NORTH-EAST", 10, -8),
        3 to StartHint("SOUTH-EAST", 10, -26),
        6 to StartHint("NORTH", 0, -8),
        7 to StartHint("EAST", 11, -16),
        8 to StartHint("EAST", 11, -16),
        11 to StartHint("NORTH", 0, -8),
        12 to StartHint("NORTH", 0, -8),
        13 to StartHint("NORTH", 0, -8),
        16 to StartHint("NORTH-WEST ROCK", -10, -8)
    )

    /**
     * The compass labels match PvME's HM-necro guide verbatim (re-verified 2026-07-20), so the
     * text suffix stays on; the drawn TILE offsets are still guesses the user flagged in play,
     * so tile rendering stays off until placement is validated.
     */
    fun startLabel(wave: Int): String? = START_HINTS[wave]?.label

    private const val START_HINTS_VERIFIED = false

    fun startHint(wave: Int): Pair<String, Tile>? {
        if (!START_HINTS_VERIFIED) return null
        val hint = START_HINTS[wave] ?: return null
        val anchor = layout?.zuk ?: return null
        return hint.label to Tile.of(anchor.x + 2 + hint.dx, anchor.y + hint.dy, anchor.plane)
    }

    /**
     * The encounter varbits carry the player's saved checkpoint even at War's Retreat, so the mode
     * varbit alone is not an "in the arena" test — the anchor npc actually being nearby is. The TTL
     * rides out momentary scan misses.
     */
    fun anchorNearby(): Boolean = System.currentTimeMillis() - anchorSeenAt <= ANCHOR_TTL_MS

    private const val ANCHOR_TTL_MS = 10_000L

    /**
     * Geysers are npcs (`egwd_ful_boss_geyser`) that persist while their tile is dangerous;
     * Har-Aken's lava falls are TTL-held spot-anim sightings. Both are ground to stand off.
     * A geyser hurts out to Chebyshev 2 of its tile, not just the tile itself. Wall tiles decoded
     * off the wire arrive 1.8s before their spot anim, so they join the set early.
     */
    fun hazardTiles(): List<Tile> =
        (allNpcsWithinRange(HAZARD_SEARCH_RANGE) { it.exists() && it.isHazard() }
            .mapNotNull { npc -> runCatching { npc.tile }.getOrNull() }
            .flatMap { tile ->
                (-GEYSER_REACH..GEYSER_REACH).flatMap { dx ->
                    (-GEYSER_REACH..GEYSER_REACH).map { dy -> Tile.of(tile.x + dx, tile.y + dy, tile.plane) }
                }
            } + lavaFalls.keys + ZukZone.pendingHazardTiles()).distinct()

    private const val GEYSER_REACH = 2

    /**
     * A threat reaches [escapeGap] tiles by footprint geometry: 1 for melee, the flat trash attack
     * range for ranged/magic. A [ranged] threat additionally needs sight of the tile, so a projectile
     * blocker can deny it wherever the instance collision map is populated.
     */
    class DodgeThreat(val southWest: Tile, val size: Int, val escapeGap: Int, val ranged: Boolean)

    /**
     * Closest tile to [from] that no threat can reach: inside the arena, off every hazard, out of
     * range and sight of every attacker. A straight step beats a diagonal at equal walk distance,
     * then a stable order settles the rest. With no fully safe tile in range it degrades to the
     * closest tile of least exposure.
     */
    fun dodgeTarget(from: Tile, hazards: Collection<Tile>, threats: List<DodgeThreat>, steps: Map<Long, Int>?): Tile? {
        val area = layout ?: return null
        val blocked = hazards.toHashSet()
        val choices = ArrayList<DodgeChoice>()
        for (dx in -DODGE_SEARCH_RADIUS..DODGE_SEARCH_RADIUS) {
            for (dy in -DODGE_SEARCH_RADIUS..DODGE_SEARCH_RADIUS) {
                if (dx == 0 && dy == 0) continue
                val tile = Tile.of(from.x + dx, from.y + dy, from.plane)
                if (tile !in area || tile in blocked) continue
                val walk = if (steps != null) steps[packTile(tile.x, tile.y)] ?: continue else max(abs(dx), abs(dy))
                val exposure = threats.count { threatens(it, tile) }
                choices += DodgeChoice(tile, exposure, walk, dx * dx + dy * dy, dy, dx)
            }
        }
        return choices.minWithOrNull(DODGE_ORDER)?.tile
    }

    /**
     * Walk-step distance from [from] to every reachable tile within the Chebyshev [radius] box,
     * flooded over live collision (cardinal + diagonal movement rules). Null where collision reads
     * unpopulated, so callers fall back to straight-line distance rather than trust an empty grid.
     */
    fun pathDistances(from: Tile, radius: Int = DODGE_SEARCH_RADIUS, blocked: Set<Long> = emptySet()): Map<Long, Int>? {
        if (WorldCollision.getFlags(from.x, from.y, from.plane) == -1) return null
        val plane = from.plane
        val dist = HashMap<Long, Int>()
        val queue = ArrayDeque<Tile>()
        dist[packTile(from.x, from.y)] = 0
        queue += from
        while (queue.isNotEmpty()) {
            val tile = queue.removeFirst()
            val d = dist.getValue(packTile(tile.x, tile.y))
            for ((dx, dy) in STEP_DIRECTIONS) {
                val nx = tile.x + dx
                val ny = tile.y + dy
                if (abs(nx - from.x) > radius || abs(ny - from.y) > radius) continue
                val key = packTile(nx, ny)
                if (key in dist || key in blocked) continue
                if (!canStep(tile.x, tile.y, dx, dy, plane)) continue
                dist[key] = d + 1
                queue += Tile.of(nx, ny, plane)
            }
        }
        return dist
    }

    private val STEP_DIRECTIONS =
        arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

    private val NORMAL = CollisionStrategies.NORMAL

    private fun canStep(x: Int, y: Int, dx: Int, dy: Int, plane: Int): Boolean {
        val nx = x + dx
        val ny = y + dy
        if (dx == 0) return NORMAL.canMove(flagsAt(x, ny, plane), if (dy > 0) CollisionFlag.BLOCK_NORTH else CollisionFlag.BLOCK_SOUTH)
        if (dy == 0) return NORMAL.canMove(flagsAt(nx, y, plane), if (dx > 0) CollisionFlag.BLOCK_EAST else CollisionFlag.BLOCK_WEST)
        val horizontal = NORMAL.canMove(flagsAt(nx, y, plane), if (dx > 0) CollisionFlag.BLOCK_EAST else CollisionFlag.BLOCK_WEST)
        val vertical = NORMAL.canMove(flagsAt(x, ny, plane), if (dy > 0) CollisionFlag.BLOCK_NORTH else CollisionFlag.BLOCK_SOUTH)
        val diagonal = NORMAL.canMove(flagsAt(nx, ny, plane), diagonalFlag(dx, dy))
        return horizontal && vertical && diagonal
    }

    private fun diagonalFlag(dx: Int, dy: Int): Int = when {
        dx > 0 && dy > 0 -> CollisionFlag.BLOCK_NORTH_EAST
        dx < 0 && dy > 0 -> CollisionFlag.BLOCK_NORTH_WEST
        dx > 0 && dy < 0 -> CollisionFlag.BLOCK_SOUTH_EAST
        else -> CollisionFlag.BLOCK_SOUTH_WEST
    }

    private fun flagsAt(x: Int, y: Int, plane: Int): Int = WorldCollision.getFlags(x, y, plane)

    private class DodgeChoice(
        val tile: Tile,
        val exposure: Int,
        val steps: Int,
        val euclidSquared: Int,
        val tieY: Int,
        val tieX: Int
    )

    /** Nearest out-of-hazard tile wins outright; threat exposure only splits equidistant ties. */
    private val DODGE_ORDER =
        compareBy<DodgeChoice>({ it.steps }, { it.euclidSquared }, { it.exposure }, { it.tieY }, { it.tieX })

    private fun threatens(threat: DodgeThreat, tile: Tile): Boolean {
        if (edgeGap(tile, threat.southWest, threat.size) > threat.escapeGap) return false
        return !threat.ranged || withinSight(threat, tile)
    }

    /**
     * An unpopulated instance zone reads as fully blocked, so a "no sight" result there is noise, not
     * cover — sight may only excuse a threat when both endpoints sit in a populated zone; otherwise
     * the range gap alone decides and the range-only behaviour stands.
     */
    private fun withinSight(threat: DodgeThreat, tile: Tile): Boolean {
        if (WorldCollision.getFlags(tile) == -1 || WorldCollision.getFlags(threat.southWest) == -1) return true
        return hasLineOfSight(threat.southWest, threat.size, tile, 1)
    }

    private const val DODGE_SEARCH_RADIUS = 8

    private fun edgeGap(tile: Tile, npcSouthWest: Tile, size: Int): Int {
        val dx = max(max(npcSouthWest.x - tile.x, tile.x - (npcSouthWest.x + size - 1)), 0)
        val dy = max(max(npcSouthWest.y - tile.y, tile.y - (npcSouthWest.y + size - 1)), 0)
        return max(dx, dy)
    }
}

internal fun packTile(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

/**
 * During Igneous Rain the arena floods with lava and only one sector stays safe: the one the next
 * igneous minion spawns in. Each sector is 135° clockwise of the last.
 *
 * Anchored on the **observed** position of the minion that is up, never on the player: Zuk drags the
 * player to a random spot *after* announcing the mechanic, so reading the player's angle when the
 * message arrives predicts the whole sequence one sector out. Verified against the successful kill —
 * spawns sat at exactly 135°, 0° and 225° from Zuk's centre, all at Chebyshev radius 9.
 */
object ZukIgneousRain {

    private const val STEP_DEGREES = 135.0
    private const val SECTOR_DEGREES = 45.0
    private const val RADIUS = 9
    private const val TOTAL_MINIONS = 3

    private var active = false
    private var risen = 0
    private var lastSectorAngle: Double? = null

    fun reset() {
        active = false
        risen = 0
        lastSectorAngle = null
    }

    val isActive: Boolean get() = active

    fun begin() {
        active = true
        risen = 0
        lastSectorAngle = null
    }

    fun onMinionRisen() {
        if (active) risen++
    }

    fun end() = reset()

    /**
     * Where the next minion will rise, or null while that cannot be known — before the first one
     * appears, and once all three are done and the instruction becomes the extra-action button.
     */
    fun nextSafeTile(): Tile? {
        if (!active || risen >= TOTAL_MINIONS) return null
        val centre = zukCentre() ?: return null
        val anchor = liveIgneousAngle(centre)?.also { lastSectorAngle = it } ?: lastSectorAngle ?: return null
        return tileAt(centre, snapToSector(anchor) - STEP_DEGREES)
    }

    private fun liveIgneousAngle(centre: Tile): Double? =
        allNpcsWithinRange(ZukWaves.OVERLAY_RANGE) { it.exists() && it.zukMinion() in IGNEOUS_MINIONS }
            .firstNotNullOfOrNull { npc -> runCatching { angleOf(centre, npc.tile) }.getOrNull() }

    private fun zukCentre(): Tile? {
        val zuk = findClosestNPC(ZukIds.ZUK_SHOWDOWN)?.takeIf { it.exists() } ?: return null
        return runCatching {
            val tile = zuk.tile
            Tile.of(tile.x + zuk.size / 2, tile.y + zuk.size / 2, tile.plane)
        }.getOrNull()
    }

    private fun angleOf(centre: Tile, point: Tile): Double =
        Math.toDegrees(atan2((point.y - centre.y).toDouble(), (point.x - centre.x).toDouble()))

    /** The arena is eight sectors and 135° is three of them, so angles are snapped before stepping. */
    private fun snapToSector(angleDegrees: Double): Double =
        (angleDegrees / SECTOR_DEGREES).roundToInt() * SECTOR_DEGREES

    /**
     * The sectors sit on a square ring, not a circle — every observed spawn was Chebyshev distance 9
     * from the centre, so the radius is scaled by the dominant axis rather than used directly.
     */
    private fun tileAt(centre: Tile, angleDegrees: Double): Tile {
        val radians = Math.toRadians(angleDegrees)
        val cos = cos(radians)
        val sin = sin(radians)
        val scale = RADIUS / max(abs(cos), abs(sin))
        return Tile.of(
            centre.x + (cos * scale).roundToInt(),
            centre.y + (sin * scale).roundToInt(),
            centre.plane
        )
    }

    private val IGNEOUS_MINIONS = setOf(ZukMinion.IGNEOUS_HUR, ZukMinion.IGNEOUS_XIL, ZukMinion.IGNEOUS_MEJ)
}
