package org.projectx.tools.gamevalexport

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index

/**
 * Index 67 is beta-only, so component names always carry BETA slot numbers. When beta adds or
 * removes a component in an interface, every slot after it shifts, and those names no longer address
 * the interface the server actually serves. Aligning beta's components against the server cache's
 * recovers the server slot for each name.
 *
 * Components are matched on a shape key (the leading structural bytes plus length) rather than full
 * content: only ~16% of components are byte-identical across the two caches, but the shape survives
 * content edits. A remap is only accepted when nearly every aligned pair agrees on shape — an
 * interface beta has rebuilt outright has no recoverable alignment and is left alone.
 */
class ComponentAlignment(private val server: Cache, private val beta: Cache) {

    data class Result(
        val remapped: Map<Int, Map<Int, Int>>,
        val unalignable: List<Unalignable>,
    )

    data class Unalignable(val interfaceId: Int, val serverCount: Int, val betaCount: Int, val agreement: Double)

    private data class Component(val content: Int, val shape: Int)

    fun align(): Result {
        val remapped = HashMap<Int, Map<Int, Int>>()
        val unalignable = ArrayList<Unalignable>()
        for (archive in beta.archives(Index.INTERFACES)) {
            val betaParts = components(beta, archive)
            val serverParts = components(server, archive)
            if (serverParts.isEmpty() || betaParts.isEmpty()) continue
            if (serverParts.map { it.shape } == betaParts.map { it.shape }) continue

            val pairs = alignSequences(serverParts, betaParts)
            val matched = pairs.filter { it.first >= 0 && it.second >= 0 }
            if (matched.isEmpty()) continue
            val agreement = matched.count { serverParts[it.first].shape == betaParts[it.second].shape }
                .toDouble() / matched.size
            val identity = (0 until minOf(serverParts.size, betaParts.size))
                .count { serverParts[it].shape == betaParts[it].shape }
                .toDouble() / minOf(serverParts.size, betaParts.size)

            val map = matched.associate { it.second to it.first }
            val shifts = map.count { (betaSlot, serverSlot) -> betaSlot != serverSlot }
            val drops = betaParts.indices.count { it !in map }
            if (shifts == 0 && drops == 0) continue

            if (agreement >= MIN_AGREEMENT && agreement > identity) {
                remapped[archive] = map
            } else {
                unalignable.add(Unalignable(archive, serverParts.size, betaParts.size, agreement))
            }
        }
        return Result(remapped, unalignable)
    }

    private fun components(cache: Cache, archive: Int): List<Component> {
        val files = cache.files(Index.INTERFACES, archive)
        if (files.isEmpty()) return emptyList()
        return files.map { file ->
            val data = cache.data(Index.INTERFACES, archive, file) ?: ByteArray(0)
            Component(data.contentHashCode(), shapeOf(data))
        }
    }

    private fun shapeOf(data: ByteArray): Int {
        var hash = data.size
        for (i in 0 until minOf(SHAPE_BYTES, data.size)) hash = hash * 31 + data[i]
        return hash
    }

    private fun ByteArray.contentHashCode(): Int {
        var hash = 1
        for (b in this) hash = hash * 31 + b
        return hash
    }

    /** Needleman-Wunsch; returns (serverIndex, betaIndex) pairs with -1 marking a gap. */
    private fun alignSequences(a: List<Component>, b: List<Component>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        var previous = IntArray(m + 1) { it * GAP }
        val pointers = Array(n + 1) { ByteArray(m + 1) }
        for (j in 1..m) pointers[0][j] = LEFT
        for (i in 1..n) {
            val current = IntArray(m + 1)
            current[0] = i * GAP
            pointers[i][0] = UP
            val ai = a[i - 1]
            for (j in 1..m) {
                val diagonal = previous[j - 1] + score(ai, b[j - 1])
                val up = previous[j] + GAP
                val left = current[j - 1] + GAP
                when {
                    diagonal >= up && diagonal >= left -> { current[j] = diagonal; pointers[i][j] = DIAGONAL }
                    up >= left -> { current[j] = up; pointers[i][j] = UP }
                    else -> { current[j] = left; pointers[i][j] = LEFT }
                }
            }
            previous = current
        }

        val pairs = ArrayList<Pair<Int, Int>>(maxOf(n, m))
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (pointers[i][j]) {
                DIAGONAL -> { pairs.add(--i to --j) }
                UP -> { pairs.add(--i to -1) }
                else -> { pairs.add(-1 to --j) }
            }
        }
        pairs.reverse()
        return pairs
    }

    private fun score(a: Component, b: Component): Int = when {
        a.content == b.content -> IDENTICAL
        a.shape == b.shape -> SAME_SHAPE
        else -> DIFFERENT
    }

    private companion object {
        const val SHAPE_BYTES = 9
        const val MIN_AGREEMENT = 0.9

        const val IDENTICAL = 6
        const val SAME_SHAPE = 3
        const val DIFFERENT = -1
        const val GAP = -4

        const val DIAGONAL: Byte = 0
        const val UP: Byte = 1
        const val LEFT: Byte = 2
    }
}
