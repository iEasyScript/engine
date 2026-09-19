package com.projectx.webwalker

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * The curated links a route may use, indexed by the tile the player stands on to take one.
 *
 * Loaded once from `/webwalker/links.json`, converted from navpathService's `worldReachableTiles.db` with the
 * author's permission. Only the endpoints, the object to click and the requirements are kept: walkability comes
 * from the game cache, so the tile table that makes up the bulk of that database is not needed here.
 */
object WebLinks {
    private const val RESOURCE = "/webwalker/links.json"

    private class Document {
        val source: String? = null
        val upstream: String? = null
        val generated: String? = null
        val requirements: Map<String, RawRequirement> = emptyMap()
        val links: List<RawLink> = emptyList()
    }

    private class RawRequirement {
        val about: String? = null
        val key: String? = null
        val value: String? = null
        val comparison: String? = null
    }

    private class RawLink {
        val kind: String? = null
        val action: String? = null
        val objectId: Int = -1
        val searchRadius: Int = 16
        val costTiles: Int = 1
        val fromMinX: Int = 0
        val fromMaxX: Int = 0
        val fromMinY: Int = 0
        val fromMaxY: Int = 0
        val fromPlane: Int = 0
        val toMinX: Int = 0
        val toMaxX: Int = 0
        val toMinY: Int = 0
        val toMaxY: Int = 0
        val toPlane: Int = 0

        @SerializedName("requirements")
        val requirementIds: List<Int>? = null
    }

    /** Links usable from a tile, keyed by [WebPathfinder.key]. A link with a wide origin appears under each tile. */
    private val byTile: Map<Int, List<WebLink>> by lazy { load() }

    /** Every link, for diagnostics. */
    val all: List<WebLink> by lazy { byTile.values.flatten().distinct() }

    val size: Int get() = all.size

    /** The links that can be taken standing on this tile, or an empty list. */
    fun from(x: Int, y: Int, plane: Int): List<WebLink> = byTile[WebPathfinder.key(x, y, plane)] ?: emptyList()

    /** True when any link starts on this plane, so a search can skip the lookup entirely on planes without one. */
    fun hasLinks(): Boolean = byTile.isNotEmpty()

    private fun load(): Map<Int, List<WebLink>> {
        val stream = WebLinks::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            println("[WebLinks] $RESOURCE is missing; routes will not use stairs, shortcuts or doors")
            return emptyMap()
        }
        val document = stream.use { Gson().fromJson(it.reader(), Document::class.java) } ?: return emptyMap()
        val requirements = document.requirements.mapNotNull { (id, raw) ->
            val key = raw.key ?: return@mapNotNull null
            id.toIntOrNull()?.let { it to WebRequirement(raw.about, key, raw.value.orEmpty(), raw.comparison ?: "=") }
        }.toMap()

        val index = HashMap<Int, MutableList<WebLink>>()
        var skipped = 0
        for (raw in document.links) {
            val kind = raw.kind?.let { name -> WebLinkKind.entries.firstOrNull { it.name == name } }
            if (kind == null || raw.objectId < 0) {
                skipped++
                continue
            }
            val from = WebArea(raw.fromMinX, raw.fromMaxX, raw.fromMinY, raw.fromMaxY, raw.fromPlane)
            val to = WebArea(raw.toMinX, raw.toMaxX, raw.toMinY, raw.toMaxY, raw.toPlane)
            if (from.maxX < from.minX || from.maxY < from.minY || to.maxX < to.minX || to.maxY < to.minY) {
                skipped++
                continue
            }
            val link = WebLink(
                kind = kind,
                from = from,
                to = to,
                cost = raw.costTiles.coerceAtLeast(1) * WebPathfinder.STEP_COST,
                action = raw.action ?: "Use",
                objectId = raw.objectId,
                searchRadius = raw.searchRadius.coerceIn(1, 64),
                requirements = raw.requirementIds.orEmpty().mapNotNull(requirements::get),
            )
            for (x in from.minX..from.maxX) for (y in from.minY..from.maxY) {
                index.getOrPut(WebPathfinder.key(x, y, from.plane)) { ArrayList(1) } += link
            }
        }
        val total = index.values.sumOf { it.size }
        println("[WebLinks] ${document.links.size - skipped} links over $total origin tiles" + if (skipped > 0) " ($skipped skipped)" else "")
        return index
    }
}
