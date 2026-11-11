package org.projectx.tools.navdoors

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.map.ObjectShape
import java.io.File
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Lists every door and gate placed in the world: a straight wall whose loc offers Open, Go-through or Pass-through,
 * itself or, for a loc that changes with a var, in any of its forms. The route planner's own data only carries some
 * of them, so the server's navpath build adds the rest from this list.
 *
 *   ./gradlew :tools:navDoors -Pargs="--cache <copy of the client cache> --out navpath-doors.tsv
 *       --known-tiles worldReachableTiles.db --out-tiles navpath-tiles.tsv"
 *   ./gradlew :tools:navDoors -Pargs="--cache <copy> --near 2885,3450,0[,radius]"   (what is placed around a tile)
 *   ./gradlew :tools:navDoors -Pargs="--cache <copy> --known-tiles <db> --compare x1,y1,x2,y2,plane"
 *
 * One line per door: loc id, option, x, y, plane, and the side of its tile the wall stands on (0 west, 1 north,
 * 2 east, 3 south). The planner's tiles were flooded out from reachable ground through its own doors only, so the
 * rooms behind the rest are missing from it: with --known-tiles, every door side it lacks is flooded through the
 * cache's collision until it meets ground the planner has, and those tiles are written with their walk masks, one
 * per line as x, y, plane, mask. --compare checks those masks against the planner's own over an area. Open a copy
 * of the cache: it is opened read-only, but the live one may be in use.
 */
private val DOOR_OPTIONS = listOf("Open", "Go-through", "Pass-through")
private const val SQUARE_TILES = 64
private const val SQUARES_X = 128
private const val SQUARES_Y = 256
private const val BRIDGE_FLAG = 0x2
private const val NEAR_TILES = 2

/** A flood that has not met the planner's ground by now is open country or somewhere nobody goes; it stops. */
private const val FLOOD_TILES = 2500
private val ACROSS = arrayOf(-1 to 0, 0 to 1, 1 to 0, 0 to -1)

fun main(args: Array<String>) {
    var cacheDir: String? = null
    var out: String? = null
    var near: List<Int>? = null
    var knownTiles: String? = null
    var outTiles: String? = null
    var compare: List<Int>? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--cache" -> cacheDir = args.getOrNull(++i)
            "--out" -> out = args.getOrNull(++i)
            "--near" -> near = args.getOrNull(++i)?.split(",")?.map(String::toInt)
            "--known-tiles" -> knownTiles = args.getOrNull(++i)
            "--out-tiles" -> outTiles = args.getOrNull(++i)
            "--compare" -> compare = args.getOrNull(++i)?.split(",")?.map(String::toInt)
        }
        i++
    }
    val needsKnown = outTiles != null || compare != null
    if (cacheDir == null || (out == null && near == null && compare == null) || (needsKnown && knownTiles == null)) {
        System.err.println(
            "usage: navDoors --cache <dir> (--out <doors.tsv> [--known-tiles <db> --out-tiles <tiles.tsv>]" +
                " | --near x,y,plane[,radius] | --known-tiles <db> --compare x1,y1,x2,y2,plane)",
        )
        exitProcess(2)
    }
    Cache.init(Paths.get(cacheDir), readOnly = true)
    if (near != null) return printNear(near[0], near[1], near[2], near.getOrElse(3) { NEAR_TILES })
    val known = knownTiles?.let(::readKnownTiles)
    if (compare != null) return compareMasks(known!!, compare)

    val lines = ArrayList<String>()
    eachPlacement { id, shape, rotation, x, y, plane ->
        if (shape != ObjectShape.WALL_STRAIGHT.id) return@eachPlacement
        val option = doorOption(id) ?: return@eachPlacement
        lines += "$id\t$option\t$x\t$y\t$plane\t${rotation and 3}"
    }
    File(out!!).writeText(lines.joinToString("\n", postfix = "\n"))
    println("${lines.size} doors written to $out")
    if (outTiles != null) writeRoomsBehindDoors(lines, known!!, outTiles)
}

/**
 * The option that opens loc [id], from the loc or, when it changes with a var, from whichever form offers one. A
 * loc that blocks nothing is no door to open, as the engine's local planner also judges it.
 */
private fun doorOption(id: Int): String? {
    val type = Cache.loc(id) ?: return null
    val forms = sequenceOf(type) + (type.transforms?.asSequence()?.filter { it >= 0 }?.mapNotNull(Cache::loc) ?: emptySequence())
    return forms.filter { it.clipType != 0 || type.clipType != 0 }.firstNotNullOfOrNull(::openOption)
}

private fun openOption(type: LocType): String? =
    type.options?.firstOrNull { op -> op != null && DOOR_OPTIONS.any { it.equals(op, ignoreCase = true) } }

private fun eachPlacement(visit: (id: Int, shape: Int, rotation: Int, x: Int, y: Int, plane: Int) -> Unit) {
    for (squareX in 0 until SQUARES_X) for (squareY in 0 until SQUARES_Y) {
        val square = Cache.mapSquare((squareX shl 8) or squareY) ?: continue
        val flags = square.surface.flags
        for (obj in square.objects) {
            val plane = if (flags[1][obj.localX][obj.localY] and BRIDGE_FLAG != 0) obj.plane - 1 else obj.plane
            if (plane < 0) continue
            visit(obj.id, obj.shape, obj.rotation, squareX * SQUARE_TILES + obj.localX, squareY * SQUARE_TILES + obj.localY, plane)
        }
    }
}

private fun key(x: Int, y: Int, plane: Int): Long = (plane.toLong() shl 40) or (x.toLong() shl 20) or y.toLong()

/** The planner's tiles and their walk masks, read from its database without writing to it. */
private fun readKnownTiles(path: String): HashMap<Long, Int> {
    val tiles = HashMap<Long, Int>(1 shl 21)
    DriverManager.getConnection("jdbc:sqlite:file:$path?immutable=1").use { db ->
        db.createStatement().executeQuery("SELECT x, y, plane, walk_mask FROM tiles").use { rows ->
            while (rows.next()) tiles[key(rows.getInt(1), rows.getInt(2), rows.getInt(3))] = rows.getInt(4)
        }
    }
    return tiles
}

private fun compareMasks(known: Map<Long, Int>, box: List<Int>) {
    val walk = CacheWalkMap()
    var same = 0
    val differ = ArrayList<String>()
    for (x in box[0]..box[2]) for (y in box[1]..box[3]) {
        val theirs = known[key(x, y, box[4])] ?: continue
        val ours = walk.mask(x, y, box[4])
        if (ours == theirs) same++ else differ += "$x,$y planner $theirs cache $ours"
    }
    println("$same of ${same + differ.size} tiles have the planner's walk mask")
    differ.take(25).forEach(::println)
}

/**
 * Floods every door side the planner lacks. A room is kept when it hangs off the planner's ground: through the door
 * itself, whose other side the planner has, or because the flood ran into that ground elsewhere. Rooms reached by
 * neither are somewhere nobody walks to, and are left out.
 */
private fun writeRoomsBehindDoors(doors: List<String>, known: Map<Long, Int>, out: String) {
    val walk = CacheWalkMap()
    val found = LinkedHashMap<Long, String>()
    var rooms = 0
    for (line in doors) {
        val parts = line.split("\t")
        val x = parts[2].toInt()
        val y = parts[3].toInt()
        val plane = parts[4].toInt()
        val (dx, dy) = ACROSS[parts[5].toInt()]
        val sides = listOf(x to y, x + dx to y + dy)
        val doorTouchesKnown = sides.any { (sx, sy) -> key(sx, sy, plane) in known }
        for ((sx, sy) in sides) {
            if (key(sx, sy, plane) in known || key(sx, sy, plane) in found) continue
            val room = flood(walk, sx, sy, plane, known, found) ?: continue
            if (!doorTouchesKnown && !room.metKnown) continue
            found.putAll(room.tiles)
            rooms++
        }
    }
    File(out).writeText(found.values.joinToString("\n", postfix = "\n"))
    println("${found.size} tiles in $rooms rooms behind doors written to $out")
}

private class Room(val tiles: Map<Long, String>, val metKnown: Boolean)

/** Walks out from a tile the planner lacks, over the cache's collision, until it meets the planner's ground. */
private fun flood(walk: CacheWalkMap, x: Int, y: Int, plane: Int, known: Map<Long, Int>, found: Map<Long, String>): Room? {
    val start = key(x, y, plane)
    if (walk.mask(x, y, plane) == 0) return null
    val tiles = LinkedHashMap<Long, String>()
    val queue = ArrayDeque<Long>()
    queue += start
    tiles[start] = ""
    var metKnown = false
    while (queue.isNotEmpty() && tiles.size < FLOOD_TILES) {
        val k = queue.removeFirst()
        val tx = ((k shr 20) and 0xfffff).toInt()
        val ty = (k and 0xfffff).toInt()
        val mask = walk.mask(tx, ty, plane)
        tiles[k] = "$tx\t$ty\t$plane\t$mask"
        for (d in 0 until 8) {
            if (mask and (1 shl d) == 0) continue
            val (sx, sy) = CacheWalkMap.STEPS[d]
            val next = key(tx + sx, ty + sy, plane)
            if (next in tiles || next in found) continue
            if (!walkEdge(mask, walk.mask(tx + sx, ty + sy, plane), d)) continue
            if (next in known) {
                metKnown = true
                continue
            }
            tiles[next] = ""
            queue += next
        }
    }
    // Tiles queued but never reached when the cap stopped the walk have no mask yet; they wait for another room.
    return Room(tiles.filterValues { it.isNotEmpty() }, metKnown)
}

/** The planner's own rule for a step: the far tile must allow the step back, and a diagonal both sides of it. */
private fun walkEdge(from: Int, to: Int, d: Int): Boolean {
    fun has(mask: Int, bits: Int) = mask and bits == bits
    return when (d) {
        0 -> has(to, CacheWalkMap.EAST)
        1 -> has(to, CacheWalkMap.NORTH)
        2 -> has(to, CacheWalkMap.WEST)
        3 -> has(to, CacheWalkMap.SOUTH)
        4 -> has(from, CacheWalkMap.NORTH or CacheWalkMap.WEST) && has(to, CacheWalkMap.SOUTH or CacheWalkMap.EAST)
        5 -> has(from, CacheWalkMap.SOUTH or CacheWalkMap.WEST) && has(to, CacheWalkMap.NORTH or CacheWalkMap.EAST)
        6 -> has(from, CacheWalkMap.SOUTH or CacheWalkMap.EAST) && has(to, CacheWalkMap.NORTH or CacheWalkMap.WEST)
        7 -> has(from, CacheWalkMap.NORTH or CacheWalkMap.EAST) && has(to, CacheWalkMap.SOUTH or CacheWalkMap.WEST)
        else -> false
    }
}

private fun printNear(x: Int, y: Int, plane: Int, radius: Int) {
    val square = Cache.mapSquare(((x shr 6) shl 8) or (y shr 6)) ?: return println("map square for $x,$y is not in this cache")
    val baseX = (x shr 6) * SQUARE_TILES
    val baseY = (y shr 6) * SQUARE_TILES
    for (obj in square.objects) {
        val ox = baseX + obj.localX
        val oy = baseY + obj.localY
        if (obj.plane != plane || abs(ox - x) > radius || abs(oy - y) > radius) continue
        val type = Cache.loc(obj.id)
        println("$ox,$oy id ${obj.id} '${type?.name}' shape ${obj.shape} rot ${obj.rotation} options ${type?.options?.filterNotNull()} door ${doorOption(obj.id)}")
    }
}
