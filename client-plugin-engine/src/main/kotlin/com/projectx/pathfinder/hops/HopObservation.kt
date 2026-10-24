package com.projectx.pathfinder.hops

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.pathfinder.HopModel
import com.projectx.pathfinder.StepRule
import com.projectx.pathfinder.WalkMap
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * One hop, as cast and as it came out, with a window of the walking map around the cast tile and the learned edges
 * inside it, so it can be explained again later by any model without going back to the spot.
 */
internal class HopObservation {
    var id = ""
    var time = 0L
    var ability = ""
    var from: Tile = Tile.EMPTY
    val starts = ArrayList<Tile>()
    var direction: Direction? = null
    var aim: Tile? = null
    var predicted: Tile? = null
    var predictedLearned: Tile? = null
    var landed: Tile? = null
    var outcome = ""

    /** No walk click between the cast and the jump: the first tile seen after it is the landing. */
    var held = true
    var model = HopModel.VERSION
    var build = ""
    var originX = 0
    var originY = 0
    var plane = 0
    val masks = ByteArray(N * N)
    val known = BooleanArray(N * N)
    val learnedPass = ArrayList<Long>()
    val learnedStop = ArrayList<Long>()
    val occupied = HashSet<Int>()

    fun capture(center: Tile, map: WalkMap, knowledge: HopKnowledge?) {
        plane = center.plane
        originX = center.x - R
        originY = center.y - R
        val rule = map.rule()
        for (ly in 0 until N) for (lx in 0 until N) {
            val x = originX + lx
            val y = originY + ly
            val i = ly * N + lx
            known[i] = map.trusted(Tile.of(x, y, plane))
            var bits = 0
            if (known[i]) MASK_ORDER.forEachIndexed { bit, d -> if (rule.canStep(x, y, plane, d)) bits = bits or (1 shl bit) }
            masks[i] = bits.toByte()
        }
        if (knowledge != null) {
            learnedPass += knowledge.learnedIn(true, originX - 1, originY - 1, originX + N, originY + N, plane)
            learnedStop += knowledge.learnedIn(false, originX - 1, originY - 1, originX + N, originY + N, plane)
        }
    }

    fun inWindow(x: Int, y: Int): Boolean = x >= originX && y >= originY && x < originX + N && y < originY + N

    fun known(x: Int, y: Int): Boolean = inWindow(x, y) && known[(y - originY) * N + (x - originX)]

    fun markOccupied(x: Int, y: Int) {
        if (inWindow(x, y)) occupied += (y - originY) * N + (x - originX)
    }

    fun occupiedAt(x: Int, y: Int): Boolean = inWindow(x, y) && (y - originY) * N + (x - originX) in occupied

    fun walkStep(x: Int, y: Int, plane: Int, direction: Direction): Boolean {
        if (plane != this.plane || !known(x, y) || !known(x + direction.delta.x, y + direction.delta.y)) return false
        return masks[(y - originY) * N + (x - originX)].toInt() and (1 shl MASK_ORDER.indexOf(direction)) != 0
    }

    /** The recorded walking rule, plus the edges in [open], minus those in [closed]. */
    fun rule(open: Set<Long>?, closed: Set<Long>?): StepRule = StepRule { x, y, p, d ->
        val edge = HopEdges.pack(x, y, p, d)
        when {
            closed != null && edge in closed -> false
            walkStep(x, y, p, d) -> true
            else -> open != null && edge in open && p == plane && known(x, y) && known(x + d.delta.x, y + d.delta.y)
        }
    }

    fun toJson(): String {
        val o = JsonObject()
        o.addProperty("id", id)
        o.addProperty("t", time)
        o.addProperty("ab", ability)
        if (!held) o.addProperty("held", false)
        o.add("from", tile(from))
        o.add("starts", JsonArray().apply { starts.forEach { add(tile(it)) } })
        direction?.let { o.addProperty("dir", HopEdges.name(it)) }
        aim?.let { o.add("aim", tile(it)) }
        predicted?.let { o.add("pred", tile(it)) }
        predictedLearned?.let { o.add("predL", tile(it)) }
        landed?.let { o.add("land", tile(it)) }
        o.addProperty("out", outcome)
        o.addProperty("mv", model)
        o.addProperty("b", build)
        val window = JsonObject()
        window.addProperty("ox", originX)
        window.addProperty("oy", originY)
        window.addProperty("p", plane)
        window.addProperty("n", N)
        window.addProperty("m", buildString { masks.forEach { append("%02x".format(it.toInt() and 0xFF)) } })
        window.addProperty("k", buildString { known.forEach { append(if (it) '1' else '0') } })
        o.add("w", window)
        if (learnedPass.isNotEmpty()) o.add("le", JsonArray().apply { learnedPass.forEach { add(HopEdges.key(HopEdges.PASS, it)) } })
        if (learnedStop.isNotEmpty()) o.add("ls", JsonArray().apply { learnedStop.forEach { add(HopEdges.key(HopEdges.STOP, it)) } })
        if (occupied.isNotEmpty()) o.add("occ", JsonArray().apply { occupied.forEach(::add) })
        return o.toString()
    }

    private fun tile(t: Tile) = JsonArray().apply {
        add(t.x)
        add(t.y)
    }

    companion object {
        /** The cast tile +- [R]: a ten-tile hop plus the walk while it arms. */
        const val R = 15
        const val N = 2 * R + 1

        val MASK_ORDER = arrayOf(
            Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.NORTH,
            Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST,
        )

        fun fromJson(line: String): HopObservation? {
            val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
            val window = o.getAsJsonObject("w") ?: return null
            if (window.get("n")?.asInt != N) return null
            val h = HopObservation()
            h.originX = window.get("ox").asInt
            h.originY = window.get("oy").asInt
            h.plane = window.get("p").asInt
            val masks = window.get("m").asString
            val known = window.get("k").asString
            for (i in 0 until N * N) {
                h.masks[i] = masks.substring(2 * i, 2 * i + 2).toInt(16).toByte()
                h.known[i] = known[i] == '1'
            }
            h.id = o.get("id")?.asString ?: ""
            h.time = o.get("t")?.asLong ?: 0L
            h.ability = o.get("ab")?.asString ?: ""
            h.held = o.get("held")?.asBoolean ?: true
            h.from = tileOf(o.get("from") as? JsonArray, h.plane) ?: return null
            o.getAsJsonArray("starts")?.forEach { s -> tileOf(s as? JsonArray, h.plane)?.let(h.starts::add) }
            h.direction = o.get("dir")?.asString?.let(HopEdges::directionNamed)
            h.aim = tileOf(o.get("aim") as? JsonArray, h.plane)
            h.predicted = tileOf(o.get("pred") as? JsonArray, h.plane)
            h.predictedLearned = tileOf(o.get("predL") as? JsonArray, h.plane)
            h.landed = tileOf(o.get("land") as? JsonArray, h.plane)
            h.outcome = o.get("out")?.asString ?: ""
            h.model = o.get("mv")?.asInt ?: 0
            h.build = o.get("b")?.asString ?: ""
            o.getAsJsonArray("le")?.forEach { HopEdges.parse(it.asString).takeIf { e -> e >= 0 }?.let(h.learnedPass::add) }
            o.getAsJsonArray("ls")?.forEach { HopEdges.parse(it.asString).takeIf { e -> e >= 0 }?.let(h.learnedStop::add) }
            o.getAsJsonArray("occ")?.forEach { h.occupied += it.asInt }
            return h
        }

        private fun tileOf(array: JsonArray?, plane: Int): Tile? =
            if (array == null || array.size() < 2) null else Tile.of(array[0].asInt, array[1].asInt, plane)
    }
}
