package com.projectx.pathfinder.navpath

import com.projectx.webwalker.WebArea
import com.projectx.webwalker.WebInterfaceStep
import com.projectx.webwalker.WebLink
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebLinks
import com.projectx.webwalker.WebObjectStep
import com.projectx.webwalker.WebPath
import com.projectx.webwalker.WebPathfinder
import com.projectx.webwalker.WebStep
import com.projectx.webwalker.WebTarget
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a hosted route into the [WebPath] the web walker already knows how to walk: walked tiles one per step, and
 * every macro action as a [WebLink] on the tile it lands on, so doors, objects, NPCs, items, interfaces, lodestones and
 * fairy rings run through the same executors as the shipped link set. Planned Surges and Dives become the straight
 * walk they cover; the walker places its own hops on the live map.
 */
internal object NavRouteMapper {

    sealed interface Mapped {
        data class Route(val path: WebPath) : Mapped

        data class Refused(val reason: String) : Mapped
    }

    private class Builder(start: Tile) {
        val xs = arrayListOf(start.x)
        val ys = arrayListOf(start.y)
        val planes = arrayListOf(start.plane)
        val links = arrayListOf<WebLink?>(null)

        val last: Tile get() = Tile.of(xs.last(), ys.last(), planes.last())

        fun walk(tile: Tile) {
            if (tile == last) return
            val from = last
            if (from.plane == tile.plane && from.getLongestDelta(tile) > 1) {
                val steps = max(abs(tile.x - from.x), abs(tile.y - from.y))
                for (k in 1 until steps) {
                    val t = k.toDouble() / steps
                    add((from.x + (tile.x - from.x) * t).roundToInt(), (from.y + (tile.y - from.y) * t).roundToInt(), from.plane, null)
                }
            }
            add(tile.x, tile.y, tile.plane, null)
        }

        fun link(approach: Tile?, landing: Tile, link: WebLink) {
            if (approach != null && !link.isGlobal) walk(approach)
            add(landing.x, landing.y, landing.plane, link)
        }

        private fun add(x: Int, y: Int, plane: Int, link: WebLink?) {
            xs += x
            ys += y
            planes += plane
            links += link
        }

        fun build(): WebPath = WebPath(xs.toIntArray(), ys.toIntArray(), planes.toIntArray(), BooleanArray(xs.size), links.toTypedArray())
    }

    fun map(start: Tile, route: NavRoute): Mapped {
        if (!route.found) return Mapped.Refused("the planner found no route")
        if (route.enteredByTeleport) return Mapped.Refused("the start is off the planner's graph")
        val builder = Builder(start)
        for (step in route.steps) {
            val refused = add(builder, step)
            if (refused != null) return Mapped.Refused(refused)
        }
        return Mapped.Route(builder.build())
    }

    /** Adds [step]; the reason it cannot be walked, or null. */
    private fun add(builder: Builder, step: NavStep): String? {
        when (step) {
            is NavStep.Move -> builder.walk(step.tile)
            is NavStep.TileExact -> builder.walk(step.tile)
            is NavStep.Surge -> builder.walk(step.to)
            is NavStep.Dive -> builder.walk(step.to)
            is NavStep.Unsupported -> return "a ${step.type} step (${step.reason})"
            is NavStep.FairyRing -> {
                val link = fairyRing(step, builder.last) ?: return "a fairy ring with no known ring object"
                builder.link(step.from, step.to, link)
            }
            is NavStep.Chained -> {
                val landing = step.to ?: return "a ${step.type} step with no landing"
                val link = chained(step, step.from ?: builder.last, landing) ?: return "a ${step.type} step this walker cannot perform"
                builder.link(step.from, landing, link)
            }
        }
        return null
    }

    private fun chained(step: NavStep.Chained, approach: Tile, landing: Tile): WebLink? {
        val chain = chain(step.chain) ?: return null
        val link = when (step) {
            is NavStep.Door -> link(WebLinkKind.DOOR, approach, NavArea.of(landing), landing, step.openAction ?: "Open", step.realIdClosed ?: step.realIdOpen ?: return null, null)
            is NavStep.Loc -> link(WebLinkKind.OBJECT, approach, step.destination, landing, step.action, step.id ?: -1, step.searchRadius)
            is NavStep.Npc -> link(WebLinkKind.NPC, approach, step.destination, landing, step.action, -1, step.searchRadius)
                .using(WebTarget(step.id ?: -1, step.name))
            is NavStep.UseOn -> link(WebLinkKind.USE_ON, approach, step.destination, landing, "Use", step.objectId ?: return null, step.searchRadius)
                .using(WebTarget(step.itemId ?: -1, step.itemName))
            is NavStep.ItemClick -> link(WebLinkKind.ITEM, approach, step.destination, landing, step.option ?: return null, -1, null)
                .using(WebTarget(step.id ?: -1, step.name))
            is NavStep.POAItem -> link(WebLinkKind.POA, approach, step.destination, landing, step.action, -1, null)
                .using(WebTarget(step.id ?: -1, step.name)).picking(*listOfNotNull(step.action2, step.action3).toTypedArray())
            is NavStep.IFSlot -> link(WebLinkKind.INTERFACE, approach, step.destination, landing, "Click", -1, null)
                .then(WebInterfaceStep(step.interfaceId, step.component, step.slot, step.clickId), *chain.toTypedArray())
            is NavStep.Lodestone -> link(WebLinkKind.LODESTONE, approach, null, landing, "Teleport", -1, null)
                .toLodestone(WebLinks.lodestoneNamed(step.name) ?: return null)
        }
        return if (chain.isEmpty() || step is NavStep.IFSlot) link else link.then(*chain.toTypedArray())
    }

    private fun link(kind: WebLinkKind, approach: Tile, area: NavArea?, landing: Tile, action: String, objectId: Int, radius: Int?): WebLink =
        WebLink(
            kind = kind,
            from = WebArea(approach.x, approach.x, approach.y, approach.y, approach.plane),
            to = area(area, landing),
            cost = WebPathfinder.STEP_COST,
            action = action,
            objectId = objectId,
            searchRadius = (radius ?: DEFAULT_SEARCH_RADIUS).coerceIn(1, 64),
            requirements = emptyList(),
        )

    /** The area the action lands in, kept on the landing's plane and always holding the landing tile. */
    private fun area(area: NavArea?, landing: Tile): WebArea {
        if (area == null || (area.plane != null && area.plane != landing.plane)) return WebArea(landing.x, landing.x, landing.y, landing.y, landing.plane)
        return WebArea(
            minOf(area.minX, area.maxX, landing.x), maxOf(area.minX, area.maxX, landing.x),
            minOf(area.minY, area.maxY, landing.y), maxOf(area.minY, area.maxY, landing.y),
            landing.plane,
        )
    }

    private fun fairyRing(step: NavStep.FairyRing, approach: Tile): WebLink? {
        val from = step.from ?: approach
        val known = WebLinks.all.firstOrNull { it.kind == WebLinkKind.FAIRY_RING && !it.portable && it.objectId == step.objectId }
        if (!step.portable && step.objectId < 0) return null
        val link = WebLink(
            kind = WebLinkKind.FAIRY_RING,
            from = WebArea(from.x - RING_REACH, from.x + RING_REACH, from.y - RING_REACH, from.y + RING_REACH, from.plane),
            to = WebArea(step.to.x - RING_REACH, step.to.x + RING_REACH, step.to.y - RING_REACH, step.to.y + RING_REACH, step.to.plane),
            cost = WebPathfinder.STEP_COST,
            action = known?.action ?: step.action.ifBlank { DEFAULT_RING_ACTION },
            objectId = step.objectId,
            searchRadius = RING_SEARCH,
            requirements = emptyList(),
        ).dialling(step.code)
        return if (step.portable) link.fromPortableRing() else link
    }

    /** The chain as the walker's own steps, or null when it holds something only a node could do. */
    private fun chain(steps: List<NavStep>): List<WebStep>? = steps.map { step ->
        when (step) {
            is NavStep.IFSlot -> WebInterfaceStep(step.interfaceId, step.component, step.slot, step.clickId)
            is NavStep.Loc -> WebObjectStep(step.id ?: return null, step.action, (step.searchRadius ?: DEFAULT_SEARCH_RADIUS).coerceIn(1, 64))
            else -> return null
        }
    }

    private const val DEFAULT_SEARCH_RADIUS = 16
    private const val RING_REACH = 2
    private const val RING_SEARCH = 8
    private const val DEFAULT_RING_ACTION = "Configure"
}
