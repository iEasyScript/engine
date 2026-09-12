package com.projectx.util

import world.gregs.voidps.type.Tile

data class Area(
    val minX: Short,
    val maxX: Short,
    val minY: Short,
    val maxY: Short
) {
    constructor(t1: Tile, t2: Tile) : this(
        minX = minOf(t1.x, t2.x).toShort(),
        maxX = maxOf(t1.x, t2.x).toShort(),
        minY = minOf(t1.y, t2.y).toShort(),
        maxY = maxOf(t1.y, t2.y).toShort()
    )

    constructor(cornerX1: Int, cornerY1: Int, cornerX2: Int, cornerY2: Int) : this(
        minX = minOf(cornerX1, cornerX2).toShort(),
        maxX = maxOf(cornerX1, cornerX2).toShort(),
        minY = minOf(cornerY1, cornerY2).toShort(),
        maxY = maxOf(cornerY1, cornerY2).toShort()
    )

    fun inside(tile: Tile): Boolean = with(tile) {
        x in minX..maxX && y in minY..maxY
    }

    fun toTiles(): Array<Tile> = buildList {
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                add(Tile(x, y, 0))
            }
        }
    }.toTypedArray()

    fun toCopyString(): String =
        "new Area(new WorldTile($minX, $minY), new WorldTile($maxX, $maxY))"

    override fun toString(): String = "[$minX, $minY] x [$maxX, $maxY]"
}