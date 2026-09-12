@file:Suppress("MemberVisibilityCanBePrivate")

package world.gregs.voidps.path

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.roundToInt

data class Route(
    val coords: ArrayDeque<RouteCoordinates>,
    val alternative: Boolean,
    val success: Boolean,
    val distance: Int,
) : List<RouteCoordinates> by coords {
    val failed: Boolean
        get() = !success
}

@JvmInline
value class RouteCoordinates(val packed: Int) {
    val x: Int
        get() = packed and 0xFFFF

    val y: Int
        get() = (packed shr 16) and 0xFFFF

    constructor(x: Int, y: Int) : this(
        (x and 0xFFFF) or ((y and 0xFFFF) shl 16)
    )

    fun translate(xOffset: Int, yOffset: Int): RouteCoordinates {
        return RouteCoordinates(
            x = x + xOffset,
            y = y + yOffset
        )
    }

    fun translateX(offset: Int): RouteCoordinates = translate(offset, 0)

    fun translateY(offset: Int): RouteCoordinates = translate(0, offset)

    override fun toString(): String {
        return "${javaClass.simpleName}{x=$x, y=$y}"
    }
}

fun Route.toTiles(plane: Int = 0): List<Tile> {
    if (coords.isEmpty()) return emptyList()
    if (coords.size == 1) {
        val coord = coords.first()
        return listOf(Tile(coord.x, coord.y, plane))
    }

    val tiles = mutableListOf<Tile>()

    coords.zipWithNext { current, next ->
        val dx = next.x - current.x
        val dy = next.y - current.y

        val steps = maxOf(abs(dx), abs(dy))

        if (steps > 0) {
            val xIncrement = dx.toDouble() / steps
            val yIncrement = dy.toDouble() / steps

            for (step in 0..steps) {
                val interpolatedX = (current.x + xIncrement * step).roundToInt()
                val interpolatedY = (current.y + yIncrement * step).roundToInt()
                tiles.add(Tile(x = interpolatedX, y = interpolatedY, level = plane))
            }
        }
    }

    return tiles.distinctBy { Triple(it.x, it.y, it.plane) }
}
