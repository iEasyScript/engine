package com.projectx.script.impl.devin.zuk

import com.projectx.script.api.getAllSpotAnimsWithinRange
import world.gregs.voidps.type.Tile

/**
 * Live decoder for the zone-update mechanics the wall analysis proved ride op 109: per-tile hazard
 * pre-warnings that land 1.8s before the tile's spot anim, which is what makes the HM wall gap
 * knowable before the sweep is visible.
 *
 * Wire tiles are zone-relative; the absolute base differs per instance placement, so it is
 * calibrated at runtime by matching sub-op-6 records (which carry the gfx id) against the world
 * spot-anim list. Pre-warn records use a mirrored coord byte whose decode sits one zone west of
 * the sub-op-6 base.
 */
object ZukZone {

    private const val OP_UPDATE_ZONE_PARTIAL_ENCLOSED = 109
    private const val SUB_PREWARN = 1
    private const val SUB_SPOTANIM = 6
    private const val CALIBRATION_MATCH_MS = 1500L
    private const val CALIBRATION_VOTES = 2
    private const val WALL_ACTIVE_MS = 6_000L
    private const val WARN_RETAIN_MS = 12_000L
    private const val HAZARD_TTL_MS = 8_000L
    private const val MIN_WALL_TILES = 12
    private const val PREWARN_ZONE_SHIFT_X = 8

    private val SUB_SIZES = mapOf(
        0 to 10, 1 to 6, 2 to 2, 3 to 7, 4 to 20, 5 to 21, 6 to 11, 7 to 3, 8 to 2, 9 to 5,
        10 to 14, 11 to 8, 12 to 7, 13 to 6, 14 to 7, 15 to 11, 16 to 29, 17 to 4, 19 to 28
    )

    private class Rel(val x: Int, val y: Int)
    private class PendingGfx(val rel: Rel, val gfx: Int, val at: Long)
    private class Warn(val rel: Rel, val at: Long)

    private val lock = Any()
    private val pendingGfx = ArrayList<PendingGfx>()
    private val warns = ArrayList<Warn>()
    private val instantRows = ArrayList<Warn>()
    private val badSubOps = HashSet<Int>()

    @Volatile
    private var base: Tile? = null

    @Volatile
    private var wallActiveUntil = 0L

    @Volatile
    private var hazardSnapshot: List<Tile> = emptyList()

    @Volatile
    private var gapSnapshot: List<Tile> = emptyList()

    val listener: (Int, ByteArray) -> Unit = { opcode, payload ->
        if (opcode == OP_UPDATE_ZONE_PARTIAL_ENCLOSED) runCatching { parse(payload) }
    }

    fun reset() {
        synchronized(lock) {
            pendingGfx.clear()
            warns.clear()
            instantRows.clear()
        }
        base = null
        wallActiveUntil = 0
        hazardSnapshot = emptyList()
        gapSnapshot = emptyList()
    }

    fun wallActive(): Boolean = System.currentTimeMillis() < wallActiveUntil

    fun gapTiles(): List<Tile> = gapSnapshot

    fun pendingHazardTiles(): List<Tile> = hazardSnapshot

    fun calibrated(): Boolean = base != null

    private fun parse(payload: ByteArray) {
        if (payload.size < 3) return
        val zoneX = (128 - (payload[1].toInt() and 0xFF)) and 0xFF
        val zoneY = (128 - (payload[2].toInt() and 0xFF)) and 0xFF
        val now = System.currentTimeMillis()
        var i = 3
        while (i < payload.size) {
            val idx = payload[i].toInt() and 0xFF
            i++
            val size = SUB_SIZES[idx]
            if (size == null || i + size > payload.size) {
                if (synchronized(lock) { badSubOps.add(idx) }) {
                    println("[ZukCap] ZONE BADIDX idx=$idx at=$i len=${payload.size}")
                }
                return
            }
            when (idx) {
                SUB_PREWARN -> readPreWarn(payload, i, zoneX, zoneY, now)
                SUB_SPOTANIM -> readSpotAnim(payload, i, zoneX, zoneY, now)
            }
            i += size
        }
    }

    private fun readPreWarn(payload: ByteArray, at: Int, zoneX: Int, zoneY: Int, now: Long) {
        val v0 = payload[at].toInt() and 0xFF
        val v1 = payload[at + 1].toInt() and 0xFF
        if (v0 != 0xA8) return
        val coord = payload[at + 5].toInt() and 0xFF
        val d = (0x80 - coord) and 0xFF
        val rel = Rel(zoneX * 8 + (d shr 4) - PREWARN_ZONE_SHIFT_X, zoneY * 8 + (d and 15))
        when (v1) {
            0xCE -> synchronized(lock) {
                warns += Warn(rel, now)
                wallActiveUntil = now + WALL_ACTIVE_MS
            }
            0xD4 -> synchronized(lock) { instantRows += Warn(rel, now) }
        }
    }

    private fun readSpotAnim(payload: ByteArray, at: Int, zoneX: Int, zoneY: Int, now: Long) {
        val coord = payload[at].toInt() and 0xFF
        val gfx = ((payload[at + 1].toInt() and 0xFF) shl 8) or (payload[at + 2].toInt() and 0xFF)
        val rel = Rel(zoneX * 8 + (coord shr 4), zoneY * 8 + (coord and 15))
        synchronized(lock) { pendingGfx += PendingGfx(rel, gfx, now) }
    }

    /** Runs on the script loop thread: prunes, calibrates, and publishes render-safe snapshots. */
    fun tick() {
        val now = System.currentTimeMillis()
        val warnCopy: List<Warn>
        val rowCopy: List<Warn>
        synchronized(lock) {
            pendingGfx.removeIf { now - it.at > CALIBRATION_MATCH_MS * 2 }
            warns.removeIf { now - it.at > WARN_RETAIN_MS }
            instantRows.removeIf { now - it.at > HAZARD_TTL_MS }
            warnCopy = ArrayList(warns)
            rowCopy = ArrayList(instantRows)
        }
        if (base == null) calibrate(now)
        val origin = base
        if (origin == null) {
            hazardSnapshot = emptyList()
            gapSnapshot = emptyList()
            return
        }
        val gap = if (wallActive()) findGap(warnCopy, origin) else emptyList()
        gapSnapshot = gap
        val live = (warnCopy + rowCopy).filter { now - it.at <= HAZARD_TTL_MS }
        val projected = projectRemainingPath(warnCopy)
        hazardSnapshot = (live + projected)
            .flatMap { w ->
                (-1..1).flatMap { dx -> (-1..1).map { dy -> Rel(w.rel.x + dx, w.rel.y + dy) } }
            }
            .map { toWorld(origin, it) }
            .distinct()
            .filterNot { it in gap }
    }

    private fun toWorld(origin: Tile, rel: Rel): Tile =
        Tile.of(origin.x + rel.x, origin.y + rel.y, origin.plane)

    private fun calibrate(now: Long) {
        val candidates = synchronized(lock) { ArrayList(pendingGfx) }
        if (candidates.isEmpty()) return
        val votes = HashMap<Tile, Int>()
        runCatching {
            for (spot in getAllSpotAnimsWithinRange(80) { true }) {
                val spotTile = runCatching { spot.tile }.getOrNull() ?: continue
                for (p in candidates) {
                    if (p.gfx != spot.id || now - p.at > CALIBRATION_MATCH_MS) continue
                    val origin = Tile.of(spotTile.x - p.rel.x, spotTile.y - p.rel.y, spotTile.plane)
                    val count = votes.merge(origin, 1, Int::plus) ?: 1
                    if (count >= CALIBRATION_VOTES) {
                        base = origin
                        println("[ZukCap] ZONE CAL base=(${origin.x},${origin.y},${origin.plane}) gfx=${p.gfx}")
                        return
                    }
                }
            }
        }
    }

    /**
     * The pre-warn burst traces the wall's birth line: the long axis is the wall segment and the
     * gap is the interior positions along it that were never warned. A real wall has exactly one
     * gap, so anything but a single contiguous missing run (bent walls project several phantom
     * clusters — one appeared in the 22:23 run) yields no call rather than a wrong one. The gap's
     * cross-axis position is interpolated from its adjacent warned tiles, since bent walls drift.
     */
    private fun findGap(warnList: List<Warn>, origin: Tile): List<Tile> {
        if (warnList.size < MIN_WALL_TILES) return emptyList()
        val dominantIsY = warnList.map { it.rel.y }.distinct().size >= warnList.map { it.rel.x }.distinct().size
        val minorByDominant = HashMap<Int, MutableList<Int>>()
        for (w in warnList) {
            val dom = if (dominantIsY) w.rel.y else w.rel.x
            val min = if (dominantIsY) w.rel.x else w.rel.y
            minorByDominant.getOrPut(dom) { ArrayList() }.add(min)
        }
        if (minorByDominant.size < MIN_WALL_TILES) return emptyList()
        val present = minorByDominant.keys.toSortedSet()
        val missing = (present.first()..present.last()).filter { it !in present }
        if (missing.isEmpty() || missing.size > MAX_GAP_WIDTH) return emptyList()
        val contiguous = missing.zipWithNext().all { (a, b) -> b == a + 1 }
        if (!contiguous) return emptyList()
        val before = minorByDominant[missing.first() - 1].orEmpty()
        val after = minorByDominant[missing.last() + 1].orEmpty()
        val neighbours = before + after
        if (neighbours.isEmpty()) return emptyList()
        val minorAtGap = neighbours.sorted()[neighbours.size / 2]
        val tiles = missing.map { v ->
            if (dominantIsY) toWorld(origin, Rel(minorAtGap, v)) else toWorld(origin, Rel(v, minorAtGap))
        }
        if (tiles != gapSnapshot) {
            println("[ZukCap] ZONE GAP n=${tiles.size} tiles=${tiles.joinToString { "(${it.x},${it.y})" }}")
        }
        return tiles
    }

    private const val MAX_GAP_WIDTH = 6

    /**
     * The trace materializes at ~5 tiles/tick, so waiting for it steals reaction time — once its
     * direction is readable (first vs last arrivals), the rest of the run to the arena edge is
     * marked immediately, drifting the cross-axis at the trace's own observed slope.
     */
    private fun projectRemainingPath(warnList: List<Warn>): List<Warn> {
        if (warnList.size < PROJECT_MIN_TILES || !wallActive()) return emptyList()
        val ordered = warnList.sortedBy { it.at }
        val first = ordered.first()
        val last = ordered.last()
        val dominantIsY = warnList.map { it.rel.y }.distinct().size >= warnList.map { it.rel.x }.distinct().size
        val firstDom = if (dominantIsY) first.rel.y else first.rel.x
        val lastDom = if (dominantIsY) last.rel.y else last.rel.x
        val step = if (lastDom >= firstDom) 1 else -1
        if (lastDom == firstDom) return emptyList()
        val slope = ((if (dominantIsY) last.rel.x - first.rel.x else last.rel.y - first.rel.y).toFloat()) /
            (lastDom - firstDom)
        val lastMinor = if (dominantIsY) last.rel.x else last.rel.y
        val projected = ArrayList<Warn>()
        for (i in 1..PROJECT_MAX_TILES) {
            val dom = lastDom + i * step
            val minor = lastMinor + (slope * i * step).toInt()
            projected += Warn(if (dominantIsY) Rel(minor, dom) else Rel(dom, minor), last.at)
        }
        return projected
    }

    private const val PROJECT_MIN_TILES = 4
    private const val PROJECT_MAX_TILES = 40
}
