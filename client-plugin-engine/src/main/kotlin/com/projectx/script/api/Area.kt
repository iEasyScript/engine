package com.projectx.script.api

import world.gregs.voidps.type.Tile
import com.projectx.pathfinder.WorldCollision
import com.projectx.util.random
import java.awt.Point
import java.awt.Polygon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

sealed class Area {
    fun getArea(): Area = this

    @JvmOverloads
    fun getOverlap(other: Area, ignoreZ: Boolean = false): Set<Tile> {
        val otherCoordinates = other.getCoordinates().toSet()
        if (otherCoordinates.isEmpty()) return emptySet()

        val result = mutableSetOf<Tile>()

        for (thisCoord in getCoordinates()) {
            for (otherCoord in otherCoordinates) {
                if (thisCoord.x == otherCoord.x && 
                    thisCoord.y == otherCoord.y && 
                    (ignoreZ || thisCoord.plane == otherCoord.plane)) {
                    result.add(thisCoord)
                    break
                }
            }
        }
        
        return result
    }

    // This called itself, so any use overflowed the stack.
    fun overlaps(other: Tile, ignoreZ: Boolean = false): Boolean =
        getCoordinates().any { it.x == other.x && it.y == other.y && (ignoreZ || it.plane == other.plane) }

    /** Whether ([x], [y]) on [plane] lies inside this area. */
    fun contains(x: Int, y: Int, plane: Int): Boolean = contains(Tile.of(x, y, plane))

    @JvmOverloads
    fun overlaps(other: Area, ignoreZ: Boolean = false): Boolean {
        val thisCoords = getCoordinates()
        val otherCoords = other.getCoordinates()

        if (thisCoords.isEmpty() || otherCoords.isEmpty()) return false

        return thisCoords.any { thisCoord ->
            otherCoords.any { otherCoord ->
                thisCoord.x == otherCoord.x &&
                thisCoord.y == otherCoord.y &&
                (ignoreZ || thisCoord.plane == otherCoord.plane)
            }
        }
    }

    abstract fun getRandomCoordinate(): Tile

    fun getRandomWalkableCoordinate(): Tile? {
        val walkableCoords = getWalkableCoordinates()
        return if (walkableCoords.isEmpty()) null else walkableCoords[random(walkableCoords.size)]
    }

    fun getWalkableCoordinates(): List<Tile> {
        return getCoordinates().filter { tile ->
            val flags = WorldCollision.getFlags(tile.x, tile.y, tile.plane)
            flags == -1 || flags == 0
        }
    }

    fun getCoordinatesInArea(coordinate: Tile): List<Tile> {
        return getCoordinates().filter { it.plane == coordinate.plane }
    }

    fun getCentroid(): Tile? {
        val coords = getCoordinates()
        if (coords.isEmpty()) return null

        var totalX = 0
        var totalY = 0
        var totalZ = 0

        for (coord in coords) {
            totalX += coord.x
            totalY += coord.y
            totalZ += coord.plane
        }

        val size = coords.size
        return Tile.of(totalX / size, totalY / size, totalZ / size)
    }

    fun getCoordinate(): Tile? = getCentroid()

    // Functions returning a Tile compile to mangled names, and a non-null Tile reaches Java as a packed int. These
    // name themselves and return a Tile object, so Java can read getX(), getY() and getLevel().

    /** A random tile in the area. */
    @JvmName("randomTile")
    fun randomTile(): Tile? = getRandomCoordinate()

    /** A random tile in the area that nothing blocks, or null when every tile is blocked. */
    @JvmName("randomWalkableTile")
    fun randomWalkableTile(): Tile? = getRandomWalkableCoordinate()

    /** The tile at the centre of the area, or null when it is empty. */
    @JvmName("centreTile")
    fun centreTile(): Tile? = getCentroid()

    abstract fun toRectangular(): Rectangular
    abstract fun toPolygonal(): Polygonal
    abstract fun toCircular(): Circular
    abstract fun contains(locatable: Tile): Boolean
    abstract fun getCoordinates(): List<Tile>

    class Circular(
        private val center: Tile,
        private val radius: Double
    ) : Area() {
        constructor(centerX: Int, centerY: Int, plane: Int, radius: Double) : this(Tile.of(centerX, centerY, plane), radius)

        private var coordinatesCache: List<Tile>? = null

        override fun toRectangular(): Rectangular {
            val radiusInt = radius.toInt()
            return Rectangular(
                Tile.of(center.x - radiusInt, center.y - radiusInt, center.plane),
                Tile.of(center.x + radiusInt, center.y + radiusInt, center.plane)
            )
        }

        override fun toPolygonal(): Polygonal {
            return toRectangular().toPolygonal()
        }

        override fun toCircular(): Circular = this
        override fun contains(locatable: Tile): Boolean {
            if (locatable.plane != center.plane) return false

            val dx = (locatable.x - center.x).toDouble()
            val dy = (locatable.y - center.y).toDouble()
            val distance = sqrt(dx * dx + dy * dy)

            return distance <= radius
        }

        fun derive(xOffset: Int, yOffset: Int, zOffset: Int): Circular {
            return Circular(
                Tile.of(center.x + xOffset, center.y + yOffset, center.plane + zOffset),
                radius
            )
        }

        override fun getCoordinates(): List<Tile> {
            if (coordinatesCache == null) {
                val coords = mutableListOf<Tile>()
                val centerX = center.x
                val centerY = center.y
                val plane = center.plane

                for (angle in 0 until 360) {
                    val radians = Math.toRadians(angle.toDouble())
                    val x = (centerX + radius * cos(radians)).roundToInt()
                    val y = (centerY + radius * sin(radians)).roundToInt()
                    coords.add(Tile.of(x, y, plane))
                }

                coordinatesCache = coords.distinct()
            }

            return coordinatesCache!!
        }

        override fun getRandomCoordinate(): Tile {
            val angle = random(360)
            val radians = Math.toRadians(angle.toDouble())
            val x = (center.x + radius * cos(radians)).roundToInt()
            val y = (center.y + radius * sin(radians)).roundToInt()
            return Tile.of(x, y, center.plane)
        }
        
        fun getRadius(): Double = radius
        fun getCenter(): Tile = center
        fun getCenterX(): Int = center.x
        fun getCenterY(): Int = center.y
        fun getPlane(): Int = center.plane
    }

    class Polygonal(coordinates: List<Tile>) : Area() {
        /** A polygon through the corners ([xs] `[i]`, [ys] `[i]`) on [plane], for Java. */
        constructor(xs: IntArray, ys: IntArray, plane: Int) : this(xs.indices.map { Tile.of(xs[it], ys[it], plane) })

        private val polygon: Polygon
        private val plane: Int
        private var coordinatesCache: List<Tile>? = null

        init {
            require(coordinates.isNotEmpty()) { "Polygon must have at least one coordinate" }
            plane = coordinates[0].plane
            polygon = Polygon()

            for (coord in coordinates) {
                polygon.addPoint(coord.x, coord.y)
            }
        }

        override fun toRectangular(): Rectangular {
            val bounds = polygon.bounds
            return Rectangular(
                Tile.of(bounds.minX.toInt(), bounds.minY.toInt(), plane),
                Tile.of(bounds.maxX.toInt(), bounds.maxY.toInt(), plane)
            )
        }

        override fun toPolygonal(): Polygonal = this

        override fun toCircular(): Circular {
            return toRectangular().toCircular()
        }

        override fun contains(locatable: Tile): Boolean {
            if (locatable.plane != plane) return false

            return polygon.contains(Point(locatable.x, locatable.y))
        }

        override fun getCoordinates(): List<Tile> {
            if (coordinatesCache == null || coordinatesCache!!.isEmpty()) {
                val coords = mutableListOf<Tile>()
                val bounds = polygon.bounds

                for (x in bounds.x until (bounds.x + bounds.width)) {
                    for (y in bounds.y until (bounds.y + bounds.height)) {
                        if (polygon.contains(x, y)) {
                            coords.add(Tile.of(x, y, plane))
                        }
                    }
                }

                coordinatesCache = coords
            }

            return coordinatesCache!!
        }

        override fun getRandomCoordinate(): Tile {
            val coords = getCoordinates()
            return coords[random(coords.size)]
        }
    }

    class Rectangular(
        private var bottomLeft: Tile,
        private var topRight: Tile
    ) : Area() {
        init {
            val minX = minOf(bottomLeft.x, topRight.x)
            val maxX = maxOf(bottomLeft.x, topRight.x)
            val minY = minOf(bottomLeft.y, topRight.y)
            val maxY = maxOf(bottomLeft.y, topRight.y)
            val maxZ = maxOf(bottomLeft.plane, topRight.plane)

            bottomLeft = Tile.of(minX, minY, maxZ)
            topRight = Tile.of(maxX, maxY, maxZ)
        }

        constructor(bottomLeft: Tile, width: Int, height: Int) : this(
            bottomLeft,
            Tile.of(bottomLeft.x + width, bottomLeft.y + height, bottomLeft.plane)
        )

        /** The rectangle between corners ([x1], [y1]) and ([x2], [y2]) on [plane], for Java. */
        constructor(x1: Int, y1: Int, x2: Int, y2: Int, plane: Int) : this(Tile.of(x1, y1, plane), Tile.of(x2, y2, plane))

        override fun toRectangular(): Rectangular = this

        override fun toPolygonal(): Polygonal {
            return Polygonal(
                listOf(
                    getBottomLeft(),
                    getBottomRight(),
                    getTopRight(),
                    getTopLeft()
                )
            )
        }

        override fun toCircular(): Circular {
            val width = topRight.x - bottomLeft.x
            val height = topRight.y - bottomLeft.y
            val radius = minOf(width, height).toDouble()
            return Circular(getCoordinate()!!, radius)
        }

        override fun contains(locatable: Tile): Boolean {
            return bottomLeft.x <= locatable.x && locatable.x <= topRight.x &&
                    bottomLeft.y <= locatable.y && locatable.y <= topRight.y &&
                    bottomLeft.plane == locatable.plane
        }

        override fun getCoordinates(): List<Tile> {
            val coords = mutableListOf<Tile>()

            for (z in bottomLeft.plane..topRight.plane) {
                for (x in bottomLeft.x..topRight.x) {
                    for (y in bottomLeft.y..topRight.y) {
                        coords.add(Tile.of(x, y, z))
                    }
                }
            }

            return coords
        }

        override fun getRandomCoordinate(): Tile {
            val x = bottomLeft.x + random((topRight.x - bottomLeft.x + 1))
            val y = bottomLeft.y + random((topRight.y - bottomLeft.y + 1))
            return Tile.of(x, y, bottomLeft.plane)
        }

        fun derive(xOffset: Int, yOffset: Int, zOffset: Int): Rectangular {
            bottomLeft = Tile.of(bottomLeft.x - xOffset, bottomLeft.y - yOffset, bottomLeft.plane - zOffset)
            topRight = Tile.of(topRight.x + xOffset, topRight.y + yOffset, topRight.plane + zOffset)
            return this
        }

        fun getBottomRight(): Tile = Tile.of(topRight.x, bottomLeft.y, bottomLeft.plane)
        fun getTopLeft(): Tile = Tile.of(bottomLeft.x, topRight.y, topRight.plane)
        fun getBottomLeft(): Tile = bottomLeft
        fun getTopRight(): Tile = topRight

        override fun toString(): String {
            return "Bottom-Left: $bottomLeft | Top-Right: $topRight"
        }
    }
}
