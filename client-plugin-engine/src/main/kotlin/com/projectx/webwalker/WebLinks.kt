package com.projectx.webwalker

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.projectx.script.api.Lodestone
import java.util.concurrent.ConcurrentHashMap

/**
 * The links a route may use: those taken from a place in the world, indexed by the tile the player stands on to
 * take one, and the global ones - teleports - that can be taken from anywhere.
 *
 * The shipped set is loaded once from `/webwalker/links.json`, converted from navpathService's
 * `worldReachableTiles.db` with the author's permission. Only the endpoints, what to use and the requirements are
 * kept: walkability comes from the game cache, so the tile table that makes up the bulk of that database is not
 * needed here. Its fairy rings are expanded into a link from every ring to every other.
 *
 * A script can add its own with [register]. That is how a script that finds its own way somewhere - through a
 * dungeon the shipped set does not cover, say - can hand what it learned to the walker, so later routes are
 * planned through it instead of being searched for again. Registered links live until the client restarts.
 */
object WebLinks {
    private const val RESOURCE = "/webwalker/links.json"
    private const val FAIRY_RING_REACH = 2
    private const val FAIRY_RING_SEARCH = 8

    // Upstream spells it out; the enum kept an older spelling that scripts already use.
    private val LODESTONE_ALIASES = mapOf("BURTHORPE" to Lodestone.BURTHOPE)

    private class Document {
        val source: String? = null
        val upstream: String? = null
        val generated: String? = null
        val requirements: Map<String, RawRequirement> = emptyMap()
        val links: List<RawLink> = emptyList()
        val fairyRings: List<RawRing> = emptyList()
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
        val global: Boolean = false
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
        val npcId: Int = -1
        val npcName: String? = null
        val itemId: Int = -1
        val itemName: String? = null
        val lodestone: String? = null
        val menu: List<String>? = null

        @SerializedName("requirements")
        val requirementIds: List<Int>? = null

        /** The destination to pick when this link's object asks where to go. */
        val choice: String? = null

        /**
         * What follows the first action, in order: `[interfaceId, componentId, slot, option]` for an interface
         * click, or `{objectId, action, searchRadius}` for another object.
         */
        val steps: List<JsonElement>? = null
    }

    private class RawRing {
        val objectId: Int = -1
        val x: Int = 0
        val y: Int = 0
        val plane: Int = 0
        val code: String? = null
        val action: String? = null
        val costTiles: Int = 12

        @SerializedName("requirements")
        val requirementIds: List<Int>? = null
    }

    // Buckets are replaced rather than mutated, so the planner thread always reads a whole list while a script
    // registers on the game thread.
    private val byTile = ConcurrentHashMap<Int, List<WebLink>>()
    private val known = ConcurrentHashMap.newKeySet<WebLink>()
    private val teleports = ConcurrentHashMap.newKeySet<WebLink>()

    @Volatile
    private var loaded = false

    /** Every link the walker knows, shipped and registered alike, teleports included. */
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

    /** The links a route can take from wherever it is: lodestones, teleport items, spells, the Passage of the Abyss. */
    val globals: List<WebLink>
        get() {
            ensureLoaded()
            return teleports.toList()
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
        if (!link.isGlobal && (link.from.maxX < link.from.minX || link.from.maxY < link.from.minY)) return false
        if (link.to.maxX < link.to.minX || link.to.maxY < link.to.minY) return false
        if (!known.add(link)) return false
        if (link.isGlobal) teleports.add(link) else index(link)
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
            println("[WebLinks] $RESOURCE is missing; routes will not use stairs, shortcuts, doors or teleports")
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
            val link = toLink(raw, requirements)
            if (link == null || !add(link)) skipped++
        }
        for (link in fairyRingLinks(document.fairyRings, requirements)) if (!add(link)) skipped++

        println(
            "[WebLinks] ${known.size} links (${teleports.size} teleports) over ${byTile.size} origin tiles" +
                if (skipped > 0) " ($skipped skipped)" else "",
        )
    }

    private fun add(link: WebLink): Boolean {
        if (!link.isGlobal && (link.from.maxX < link.from.minX || link.from.maxY < link.from.minY)) return false
        if (link.to.maxX < link.to.minX || link.to.maxY < link.to.minY) return false
        if (!known.add(link)) return false
        if (link.isGlobal) teleports.add(link) else index(link)
        return true
    }

    private fun toLink(raw: RawLink, requirements: Map<Int, WebRequirement>): WebLink? {
        val kind = raw.kind?.let { name -> WebLinkKind.entries.firstOrNull { it.name == name } } ?: return null
        val usesObject = kind == WebLinkKind.OBJECT || kind == WebLinkKind.DOOR || kind == WebLinkKind.USE_ON
        if (usesObject && raw.objectId < 0) return null
        if (kind.isGlobal != raw.global && kind != WebLinkKind.FAIRY_RING) return null

        val lodestone = if (kind == WebLinkKind.LODESTONE) lodestoneNamed(raw.lodestone) ?: return null else null
        val target = when (kind) {
            WebLinkKind.NPC -> WebTarget(raw.npcId, raw.npcName)
            WebLinkKind.ITEM, WebLinkKind.POA, WebLinkKind.USE_ON -> WebTarget(raw.itemId, raw.itemName)
            else -> null
        }
        if (target != null && target.id < 0 && target.name.isNullOrBlank()) return null

        val chain = raw.steps.orEmpty().map { toStep(it) ?: return null }
        if (kind == WebLinkKind.INTERFACE && chain.firstOrNull() !is WebInterfaceStep) return null

        var link = WebLink(
            kind = kind,
            from = if (kind.isGlobal) NOWHERE else WebArea(raw.fromMinX, raw.fromMaxX, raw.fromMinY, raw.fromMaxY, raw.fromPlane),
            to = WebArea(raw.toMinX, raw.toMaxX, raw.toMinY, raw.toMaxY, raw.toPlane),
            cost = raw.costTiles.coerceAtLeast(1) * WebPathfinder.STEP_COST,
            action = raw.action ?: "Use",
            objectId = raw.objectId,
            searchRadius = raw.searchRadius.coerceIn(1, 64),
            requirements = raw.requirementIds.orEmpty().mapNotNull(requirements::get),
        )
        if (!raw.choice.isNullOrBlank()) link = link.choosing(raw.choice)
        if (chain.isNotEmpty()) link = link.then(*chain.toTypedArray())
        if (target != null) link = link.using(target)
        if (lodestone != null) link = link.toLodestone(lodestone)
        if (!raw.menu.isNullOrEmpty()) link = link.picking(*raw.menu.toTypedArray())
        return link
    }

    private fun toStep(element: JsonElement): WebStep? = when {
        element.isJsonArray -> {
            val values = element.asJsonArray.map { it.asInt }
            if (values.size < 2) null else WebInterfaceStep(values[0], values[1], values.getOrElse(2) { -1 }, values.getOrElse(3) { 1 })
        }
        element.isJsonObject -> {
            val step = element.asJsonObject
            val objectId = step.get("objectId")?.asInt ?: -1
            val action = step.get("action")?.takeUnless { it.isJsonNull }?.asString
            if (objectId < 0 || action == null) null
            else WebObjectStep(objectId, action, (step.get("searchRadius")?.asInt ?: 16).coerceIn(1, 64))
        }
        else -> null
    }

    private fun lodestoneNamed(name: String?): Lodestone? {
        val key = name?.trim()?.uppercase() ?: return null
        return LODESTONE_ALIASES[key] ?: Lodestone.entries.firstOrNull { it.name == key }
    }

    /** A link from every ring to every other: standing at one, dial the other's code. */
    private fun fairyRingLinks(rings: List<RawRing>, requirements: Map<Int, WebRequirement>): List<WebLink> {
        val usable = rings.filter { it.objectId >= 0 && !it.code.isNullOrBlank() }
        return usable.flatMap { here ->
            usable.filter { it !== here && it.code != here.code }.map { there ->
                WebLink(
                    kind = WebLinkKind.FAIRY_RING,
                    from = WebArea(here.x - FAIRY_RING_REACH, here.x + FAIRY_RING_REACH, here.y - FAIRY_RING_REACH, here.y + FAIRY_RING_REACH, here.plane),
                    to = WebArea(there.x - FAIRY_RING_REACH, there.x + FAIRY_RING_REACH, there.y - FAIRY_RING_REACH, there.y + FAIRY_RING_REACH, there.plane),
                    cost = here.costTiles.coerceAtLeast(1) * WebPathfinder.STEP_COST,
                    action = here.action ?: "Configure",
                    objectId = here.objectId,
                    searchRadius = FAIRY_RING_SEARCH,
                    requirements = (here.requirementIds.orEmpty() + there.requirementIds.orEmpty()).distinct().mapNotNull(requirements::get),
                ).dialling(there.code!!)
            }
        }
    }

    /** Where a teleport is taken from: nowhere in particular, so it is never indexed by tile. */
    private val NOWHERE = WebArea(0, 0, 0, 0, -1)
}
