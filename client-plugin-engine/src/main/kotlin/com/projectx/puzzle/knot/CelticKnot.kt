package com.projectx.puzzle.knot

import com.projectx.game.nxt.interfaces.ScreenRect
import world.gregs.voidps.gameval.Gameval

const val EMPTY_RUNE = 0

private const val KNOT_PREFIX = "trail_knot"
private const val MAX_TRACKS = 8

class KnotTrack(
    val index: Int,
    val slotComponents: IntArray,
    val runeComponents: IntArray,
    val clockwiseButton: Int,
    val counterClockwiseButton: Int,
    /** True when slot 0 -> 1 -> 2 ... walks the ring clockwise on screen. */
    val forwardIsClockwise: Boolean,
) {
    val size get() = slotComponents.size
}

class KnotSlotRef(val track: Int, val slot: Int)

class KnotCrossing(val a: KnotSlotRef, val b: KnotSlotRef, val tileComponent: Int)

class KnotLayout(
    val interfaceId: Int,
    val windowComponent: Int,
    val tracks: List<KnotTrack>,
    val crossings: List<KnotCrossing>,
)

class KnotRotation(val track: KnotTrack, val steps: Int, val clockwise: Boolean) {
    val buttonComponent get() = if (clockwise) track.clockwiseButton else track.counterClockwiseButton
}

object CelticKnotLayouts {

    private val cache = HashMap<Int, KnotLayout>()

    val interfaceIds: IntArray by lazy {
        Gameval.entries(Gameval.INTERFACE)
            .filterValues { it.startsWith(KNOT_PREFIX) }
            .keys.sorted().toIntArray()
    }

    /**
     * Layouts are only cached once every tile has a screen rect: the crossings and each ring's winding
     * are read off the live geometry, and an interface that has not been laid out yet would otherwise
     * bake a crossing-less layout in for the rest of the session.
     */
    fun forInterface(interfaceId: Int, rectOf: (Int) -> ScreenRect?): KnotLayout? =
        cache[interfaceId] ?: build(interfaceId, rectOf)?.also { cache[interfaceId] = it }

    fun readRunes(layout: KnotLayout, graphicOf: (Int) -> Int): List<IntArray> =
        layout.tracks.map { track -> IntArray(track.size) { graphicOf(track.runeComponents[it]) } }

    private fun build(interfaceId: Int, rectOf: (Int) -> ScreenRect?): KnotLayout? {
        val prefix = Gameval.interfaceName(interfaceId)?.takeIf { it.startsWith(KNOT_PREFIX) } ?: return null
        val tracks = ArrayList<KnotTrack>()
        val tiles = HashMap<ScreenRect, MutableList<KnotSlotRef>>()

        for (index in 0 until MAX_TRACKS) {
            val clockwise = Gameval.componentId("$prefix:track_${index}_cw") ?: break
            val counterClockwise = Gameval.componentId("$prefix:track_${index}_ccw") ?: break
            val slots = ArrayList<Int>()
            val runes = ArrayList<Int>()
            val rects = ArrayList<ScreenRect>()
            while (true) {
                val slot = Gameval.componentId(slotName(prefix, "track", index, slots.size)) ?: break
                val rune = Gameval.componentId(slotName(prefix, "rune", index, slots.size)) ?: break
                val rect = rectOf(slot)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
                slots.add(slot)
                runes.add(rune)
                rects.add(rect)
            }
            if (slots.size < 3) break
            tracks.add(
                KnotTrack(index, slots.toIntArray(), runes.toIntArray(), clockwise, counterClockwise, isClockwise(rects))
            )
            rects.forEachIndexed { slot, rect -> tiles.getOrPut(rect) { mutableListOf() }.add(KnotSlotRef(index, slot)) }
        }

        if (tracks.size < 2) return null
        val crossings = crossingsOf(tracks, tiles)
        if (crossings.isEmpty()) return null
        return KnotLayout(interfaceId, windowComponent(prefix), tracks, crossings)
    }

    private fun crossingsOf(
        tracks: List<KnotTrack>,
        tiles: Map<ScreenRect, List<KnotSlotRef>>,
    ): List<KnotCrossing> = buildList {
        tiles.values.forEach { refs ->
            val distinct = refs.distinctBy { it.track }
            for (i in distinct.indices) for (j in i + 1 until distinct.size) {
                val a = distinct[i]
                add(KnotCrossing(a, distinct[j], tracks[a.track].slotComponents[a.slot]))
            }
        }
    }

    private fun slotName(prefix: String, kind: String, track: Int, slot: Int) =
        "%s:%s_%d_%02d".format(prefix, kind, track, slot)

    private fun windowComponent(prefix: String) =
        Gameval.componentId("$prefix:mainmodal_window") ?: 0

    /**
     * Screen space has Y growing downwards, so the shoelace sum of a ring walked in increasing slot
     * order is positive exactly when that walk appears clockwise to the player — which is what decides
     * whether the `cw` or the `ccw` button advances a slot index.
     */
    private fun isClockwise(tiles: List<ScreenRect>): Boolean {
        var sum = 0L
        for (i in tiles.indices) {
            val a = tiles[i]
            val b = tiles[(i + 1) % tiles.size]
            sum += a.x.toLong() * b.y - b.x.toLong() * a.y
        }
        return sum > 0
    }
}

object CelticKnotSolver {

    /**
     * Rotating a track shifts every rune along it, so a solution is one offset per track. The spaces are
     * small enough to settle exactly by enumeration; crossings are checked as soon as both their tracks
     * are bound, and the cheapest total click count wins rather than the first hit.
     */
    fun solve(layout: KnotLayout, runes: List<IntArray>): List<KnotRotation>? {
        val tracks = layout.tracks
        if (runes.size != tracks.size) return null
        if (tracks.indices.any { runes[it].size != tracks[it].size }) return null

        val gatedBy = Array(tracks.size) { track -> layout.crossings.filter { maxOf(it.a.track, it.b.track) == track } }
        val offsets = IntArray(tracks.size)
        val best = IntArray(tracks.size)
        var bestClicks = Int.MAX_VALUE

        fun search(track: Int, clicks: Int) {
            if (track == tracks.size) {
                bestClicks = clicks
                offsets.copyInto(best)
                return
            }
            val size = runes[track].size
            for (offset in 0 until size) {
                val cost = clicks + minOf(offset, size - offset)
                if (cost >= bestClicks) continue
                offsets[track] = offset
                if (gatedBy[track].all { aligned(runes, it, offsets) }) search(track + 1, cost)
            }
            offsets[track] = 0
        }

        search(0, 0)
        if (bestClicks == Int.MAX_VALUE) return null
        return tracks.mapIndexedNotNull { index, track -> rotationOf(track, best[index]) }
    }

    fun aligned(runes: List<IntArray>, crossing: KnotCrossing, offsets: IntArray): Boolean {
        val a = runeAt(runes, crossing.a, offsets[crossing.a.track])
        val b = runeAt(runes, crossing.b, offsets[crossing.b.track])
        return a == EMPTY_RUNE || b == EMPTY_RUNE || a == b
    }

    fun runeAt(runes: List<IntArray>, ref: KnotSlotRef, offset: Int): Int {
        val ring = runes[ref.track]
        return ring[((ref.slot - offset) % ring.size + ring.size) % ring.size]
    }

    private fun rotationOf(track: KnotTrack, offset: Int): KnotRotation? {
        if (offset == 0) return null
        val backwards = track.size - offset
        return if (offset <= backwards) {
            KnotRotation(track, offset, track.forwardIsClockwise)
        } else {
            KnotRotation(track, backwards, !track.forwardIsClockwise)
        }
    }
}
