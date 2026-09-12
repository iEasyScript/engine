package com.projectx.script.impl.trent.leagues

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.MapSquare
import world.gregs.voidps.type.Tile

/**
 * Where things are. Scenery carries real tiles in the map data, so a loc id resolves to every
 * placement of it in the world. NPC spawn records name the type but never its coordinates, so an
 * npc only resolves to the map squares it is listed in - [LeagueNpcCoordinates] supplies precise
 * tiles for the ones that need them.
 *
 * Scanning every map square is expensive, so the index is built once per requested square and
 * memoised; callers walk outwards from the player rather than indexing the whole world.
 */
object LeagueWorldIndex {

    private val scanned = HashSet<Int>()
    private val locTiles = HashMap<Int, MutableList<Tile>>()
    private val npcSquares = HashMap<Int, MutableSet<Int>>()

    @Synchronized
    fun scan(square: MapSquare) {
        if (!scanned.add(square.id)) {
            return
        }
        val map = Cache.mapSquare(square.id) ?: return
        val originX = square.x shl 6
        val originY = square.y shl 6
        for (obj in map.objects) {
            locTiles.getOrPut(obj.id) { mutableListOf() }
                .add(Tile(originX + obj.localX, originY + obj.localY, obj.plane))
        }
        for (spawn in map.npcSpawns) {
            npcSquares.getOrPut(spawn.id) { mutableSetOf() }.add(square.id)
        }
    }

    /** Scans the [radius] ring of map squares around [centre], nearest first. */
    fun scanAround(centre: Tile, radius: Int) {
        val square = centre.mapSquare
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                scan(square.add(dx, dy))
            }
        }
    }

    @Synchronized
    fun tilesOf(locId: Int): List<Tile> = locTiles[locId].orEmpty().toList()

    @Synchronized
    fun squaresOf(npcId: Int): Set<Int> = npcSquares[npcId].orEmpty().toSet()

    fun nearestLoc(locIds: Collection<Int>, from: Tile): Tile? = locIds
        .flatMap { tilesOf(it) }
        .minByOrNull { it.distanceTo(from) }

    /** Centre tile of the closest map square any of [npcIds] is listed in. */
    fun nearestNpcSquare(npcIds: Collection<Int>, from: Tile): Tile? = npcIds
        .flatMap { squaresOf(it) }
        .map { MapSquare(it).tile.add(SQUARE_CENTRE, SQUARE_CENTRE) }
        .minByOrNull { it.distanceTo(from) }

    private const val SQUARE_CENTRE = 32
}
