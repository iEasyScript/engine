package com.projectx.webwalker

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

/**
 * The links a route may use, indexed by the tile the player stands on to take one.
 *
 * The shipped set is loaded once from `/webwalker/links.json`, converted from navpathService's
 * `worldReachableTiles.db` with the author's permission. Only the endpoints, the object to click and the
 * requirements are kept: walkability comes from the game cache, so the tile table that makes up the bulk of that
 * database is not needed here.
 *
 * A script can add its own with [register]. That is how a script that finds its own way somewhere - through a
 * dungeon the shipped set does not cover, say - can hand what it learned to the walker, so later routes are
 * planned through it instead of being searched for again. Registered links live until the client restarts.
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

        /** The destination to pick when this link's object asks where to go. */
        val choice: String? = null
    }

    // Buckets are replaced rather than mutated, so the planner thread always reads a whole list while a script
    // registers on the game thread.
    private val byTile = ConcurrentHashMap<Int, List<WebLink>>()
    private val known = ConcurrentHashMap.newKeySet<WebLink>()

    @Volatile
    private var loaded = false

    /** Every link the walker knows, shipped and registered alike. */
    val all: List<WebLink>
        get() {
            ensureLoaded()
            return known.toList()
        }

    val size: Int
        get() {
            ensureLoaded()
            return known.size
        }

    /** True when the walker knows any link at all, so a caller can tell an empty set from a missing one. */
    fun hasLinks(): Boolean {
        ensureLoaded()
        return known.isNotEmpty()
    }

    /** The links that can be taken standing on this tile, or an empty list. */
    fun from(x: Int, y: Int, plane: Int): List<WebLink> {
        ensureLoaded()
        return byTile[WebPathfinder.key(x, y, plane)] ?: emptyList()
    }

    /**
     * Teaches the walker a link a script worked out for itself, so later routes can be planned through it.
     *
     * Returns false when the link is already known or its rectangles are inverted. Safe to call from a script
     * body; the search sees it on its next plan.
     */
    fun register(link: WebLink): Boolean {
        ensureLoaded()
        if (link.from.maxX < link.from.minX || link.from.maxY < link.from.minY) return false
        if (link.to.maxX < link.to.minX || link.to.maxY < link.to.minY) return false
        if (!known.add(link)) return false
        index(link)
        return true
    }

    /** Builds an object link between two tiles and registers it; the common case for a script that found a way through. */
    @JvmStatic
    @JvmOverloads
    fun registerObjectLink(
        fromX: Int,
        fromY: Int,
        fromPlane: Int,
        toX: Int,
        toY: Int,
        toPlane: Int,
        objectId: Int,
        action: String,
        costTiles: Int = 1,
        searchRadius: Int = 16,
    ): Boolean = register(
        WebLink(
            kind = WebLinkKind.OBJECT,
            from = WebArea(fromX, fromX, fromY, fromY, fromPlane),
            to = WebArea(toX, toX, toY, toY, toPlane),
            cost = costTiles.coerceAtLeast(1) * WebPathfinder.STEP_COST,
            action = action,
            objectId = objectId,
            searchRadius = searchRadius.coerceIn(1, 64),
            requirements = emptyList(),
        ),
    )

    private fun index(link: WebLink) {
        val from = link.from
        for (x in from.minX..from.maxX) {
            for (y in from.minY..from.maxY) {
                val key = WebPathfinder.key(x, y, from.plane)
                byTile.compute(key) { _, existing -> if (existing == null) listOf(link) else existing + link }
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            load()
            loaded = true
        }
    }

    private fun load() {
        val stream = WebLinks::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            println("[WebLinks] $RESOURCE is missing; routes will not use stairs, shortcuts or doors")
            return
        }
        val document = runCatching { stream.use { Gson().fromJson(it.reader(), Document::class.java) } }
            .onFailure { println("[WebLinks] $RESOURCE could not be read: ${it.message}") }
            .getOrNull() ?: return

        val requirements = document.requirements.mapNotNull { (id, raw) ->
            val key = raw.key ?: return@mapNotNull null
            id.toIntOrNull()?.let { it to WebRequirement(raw.about, key, raw.value.orEmpty(), raw.comparison ?: "=") }
        }.toMap()

        var skipped = 0
        for (raw in document.links) {
            val kind = raw.kind?.let { name -> WebLinkKind.entries.firstOrNull { it.name == name } }
            if (kind == null || raw.objectId < 0) {
                skipped++
                continue
            }
            val link = WebLink(
                kind = kind,
                from = WebArea(raw.fromMinX, raw.fromMaxX, raw.fromMinY, raw.fromMaxY, raw.fromPlane),
                to = WebArea(raw.toMinX, raw.toMaxX, raw.toMinY, raw.toMaxY, raw.toPlane),
                cost = raw.costTiles.coerceAtLeast(1) * WebPathfinder.STEP_COST,
                action = raw.action ?: "Use",
                objectId = raw.objectId,
                searchRadius = raw.searchRadius.coerceIn(1, 64),
                requirements = raw.requirementIds.orEmpty().mapNotNull(requirements::get),
            ).let { if (raw.choice.isNullOrBlank()) it else it.choosing(raw.choice) }
            if (link.from.maxX < link.from.minX || link.from.maxY < link.from.minY ||
                link.to.maxX < link.to.minX || link.to.maxY < link.to.minY
            ) {
                skipped++
                continue
            }
            known.add(link)
            index(link)
        }
        val tiles = byTile.size
        println("[WebLinks] ${known.size} links over $tiles origin tiles" + if (skipped > 0) " ($skipped skipped)" else "")
    }
}
