package com.projectx.webwalker

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.magic.CastResult
import com.projectx.game.magic.SpellTeleports
import com.projectx.game.items.Item
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.Script
import com.projectx.script.api.LODESTONE_MAP_INTERFACE
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.interactComponent
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.isLodestoneUiOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.openLodestoneMap
import com.projectx.script.api.varps
import world.gregs.voidps.type.Tile
import kotlin.math.max

/**
 * How the walker starts each kind of link: the first thing it does, before any answer or chained step. Each returns
 * true once that has been done, after which the walker answers what the link asks and waits to arrive.
 */
internal object WebLinkActions {
    private const val PROMPT_TIMEOUT_MS = 4_000L
    private const val LODESTONE_MAP_TIMEOUT_MS = 5_000L
    private const val OBJECT_STEP_SETTLE_MS = 2_500L

    private const val FAIRY_RING_INTERFACE = 784
    private const val FAIRY_RING_TELEPORT = 23
    private const val FAIRY_RING_ROUNDS = 4

    /** Of the objects matching a link, only those this close to the nearest to its approach are told apart. */
    private const val ANCHOR_SLACK = 2

    /** A destination this close decides between twin objects at the approach: the stairs that lead there. */
    private const val DEST_NEAR = 8

    /** How far past the approach an object offering the action is looked for when none has the link's id. */
    private const val FALLBACK_REACH = 4

    // A door is the one on the crossing. Once it stands open it no longer offers "Open", and a wider search would
    // click a closed twin elsewhere in town instead.
    private const val DOOR_REACH = 1

    /** A dial's varbit counts clockwise turns, so its letters are listed in the order clockwise turns bring them up. */
    private class FairyDial(val varbit: Int, val letters: String, val anticlockwise: Int, val clockwise: Int) {
        val position get() = varps.getVarBit(varbit) and 3
    }

    private val FAIRY_RING_DIALS = listOf(
        FairyDial(12072, "adcb", 7, 8),
        FairyDial(12073, "ilkj", 16, 17),
        FairyDial(12074, "psrq", 25, 26),
    )

    /** Worn items' options only fire while the worn equipment tab is open. */
    private const val WORN_EQUIPMENT_INTERFACE = 1464

    private const val PORTABLE_RING_OPTION = "Teleport"
    private const val ACTIVATE_OPTION = "Activate"
    private const val ACTIVATE_REFUSED = "already have an active version"

    suspend fun start(script: Script, link: WebLink): Boolean = when (link.kind) {
        WebLinkKind.OBJECT -> script.useObject(link.objectId, link.action, link.searchRadius, link.from, link.to)
        WebLinkKind.DOOR -> script.useObject(link.objectId, link.action, DOOR_REACH, link.from, link.to, DOOR_REACH)
        WebLinkKind.NPC -> useNpc(link)
        WebLinkKind.USE_ON -> useItemOnObject(link)
        WebLinkKind.ITEM -> script.useCarriedItem(link)
        WebLinkKind.INTERFACE -> true
        WebLinkKind.POA -> script.useCarriedItem(link) && script.pickMenu(link)
        WebLinkKind.LODESTONE -> script.teleportToLodestone(link)
        WebLinkKind.FAIRY_RING -> script.dialFairyRing(link)
        WebLinkKind.SPELL -> link.spell?.let { SpellTeleports.cast(script, it) { script.stopped } == CastResult.STARTED } == true
    }

    /** Does one chained step: clicks an interface once it is up, or uses the next object. */
    suspend fun perform(script: Script, link: WebLink, step: WebStep): Boolean = perform(script, link, step) { false }

    suspend fun perform(script: Script, link: WebLink, step: WebStep, arrived: () -> Boolean): Boolean = when (step) {
        is WebInterfaceStep -> {
            script.delayUntil(PROMPT_TIMEOUT_MS) { interfaces.isOpen(step.interfaceId) || arrived() }
            if (arrived()) {
                true
            } else if (!interfaces.isOpen(step.interfaceId)) {
                println("[WebWalk] $link expected interface ${step.interfaceId} and it never opened")
                false
            } else {
                val slot = IFSlot(step.interfaceId, step.componentId, step.slot)
                val clicked = if (step.option <= 0) slot.dialogueContinue() else slot.click(step.option)
                if (!clicked) println("[WebWalk] $link could not click $step")
                clicked.also { if (it) script.delay(600, 250) }
            }
        }
        is WebObjectStep -> {
            val used = script.useObject(step.objectId, step.action, step.searchRadius, null, link.to)
            if (used) script.delayUntil(OBJECT_STEP_SETTLE_MS) { localPlayer.isAnimating || localPlayer.isMoving }
            used
        }
    }

    /**
     * The object nearest the link's approach [from] (the player when none), told apart from its twins by [to]: the
     * search is centred on where the link is taken, so a ladder beside the one the route uses is never climbed.
     */
    private fun Script.useObject(
        objectId: Int,
        action: String,
        searchRadius: Int,
        from: WebArea?,
        to: WebArea?,
        fallbackReach: Int = FALLBACK_REACH,
    ): Boolean {
        val me = localPlayer.tile
        val anchor = from ?: WebArea(me.x, me.x, me.y, me.y, me.plane)
        val centre = Tile.of(anchor.centreX, anchor.centreY, anchor.plane)
        val span = max(anchor.maxX - anchor.minX, anchor.maxY - anchor.minY) / 2
        val nearby = getAllObjectsWithinRange(centre, searchRadius + span).filter { it.tile.plane == anchor.plane && it.hasOption(action) }
        val target = pickAnchored(nearby.filter { it.id == objectId || it.visibleTypeId == objectId }, { it.tile }, anchor, to, me)
            ?: pickAnchored(nearby.filter { areaDistance(anchor, it.tile) <= fallbackReach }, { it.tile }, anchor, to, me)
        if (target == null) {
            println("[WebWalk] No '$action' object $objectId in range")
            return false
        }
        return target.interact(action)
    }

    private fun useNpc(link: WebLink): Boolean {
        val wanted = link.target ?: return false
        val npc = findClosestNPC(link.searchRadius) { npc ->
            val name = runCatching { npc.name() }.getOrNull()
            (wanted.matches(npc.id, name) || npc.typeId != -1 && wanted.matches(npc.typeId, name)) && npc.hasOption(link.action)
        }
        if (npc == null) {
            println("[WebWalk] No NPC $wanted offering '${link.action}' in range")
            return false
        }
        return npc.interact(link.action)
    }

    private fun useItemOnObject(link: WebLink): Boolean {
        val item = carried(inventory.toList(), link.target ?: return false, null) ?: run {
            println("[WebWalk] ${link.target} is not in the backpack for $link")
            return false
        }
        val me = localPlayer.tile
        val anchor = link.from
        val centre = Tile.of(anchor.centreX, anchor.centreY, anchor.plane)
        val nearby = getAllObjectsWithinRange(centre, link.searchRadius).filter { it.id == link.objectId || it.visibleTypeId == link.objectId }
        val target = pickAnchored(nearby, { it.tile }, anchor, link.to, me)
        if (target == null) {
            println("[WebWalk] No object ${link.objectId} in range to use ${link.target} on")
            return false
        }
        return item.useOn(target)
    }

    /**
     * The backpack copy first: a worn item's options do nothing unless the worn equipment tab happens to be open. The
     * Spirit tree re-rooter is activated first when only an inactive one is carried.
     */
    private suspend fun Script.useCarriedItem(link: WebLink): Boolean {
        val wanted = link.target ?: return false
        WebLinkPermissions.rerooterIds.get()?.let { (active, inactive) ->
            if (wanted.id == active && carried(inventory.toList(), wanted, link.action) == null && carried(equipment.toList(), wanted, link.action) == null) {
                return useActivated(active, inactive, link.action, "Spirit tree re-rooter", WebLinkPermissions::activeRerooterOutOfReach::set)
            }
        }
        carried(inventory.toList(), wanted, link.action)?.let { return it.click(link.action) }
        val worn = carried(equipment.toList(), wanted, link.action)
        if (worn == null) {
            println("[WebWalk] $wanted is not carried for $link")
            return false
        }
        if (!interfaces.isOpen(WORN_EQUIPMENT_INTERFACE)) {
            println("[WebWalk] $wanted is worn and the equipment tab is closed, so its '${link.action}' cannot be used")
            return false
        }
        return worn.click(link.action)
    }

    private suspend fun Script.pickMenu(link: WebLink): Boolean {
        for (entry in link.menu) {
            delayUntil(PROMPT_TIMEOUT_MS) { isDialogOpen() }
            if (!continueDialogueContaining(entry)) {
                println("[WebWalk] $link offered no \"$entry\" to pick")
                return false
            }
            delay(600, 250)
        }
        return true
    }

    private suspend fun Script.teleportToLodestone(link: WebLink): Boolean {
        val lodestone = link.lodestone ?: return false
        if (!isLodestoneUiOpen) {
            if (!openLodestoneMap()) {
                println("[WebWalk] No home teleport button on the minimap")
                return false
            }
            delayUntil(LODESTONE_MAP_TIMEOUT_MS) { isLodestoneUiOpen }
            if (!isLodestoneUiOpen) {
                println("[WebWalk] The lodestone map did not open")
                return false
            }
            delay(400, 200)
        }
        println("[WebWalk] Teleporting to the ${lodestone.name} lodestone")
        return interactComponent(1, LODESTONE_MAP_INTERFACE, lodestone.id)
    }

    /** Opens the ring, turns each dial until its top letter is the code's, and teleports. */
    private suspend fun Script.dialFairyRing(link: WebLink): Boolean {
        val code = link.fairyCode ?: return false
        val opened = if (link.portable) openPortableRing() else useObject(link.objectId, link.action, link.searchRadius, link.from, null)
        if (!opened) return false
        delayUntil(PROMPT_TIMEOUT_MS) { dialOpen() }
        if (!dialOpen()) {
            println("[WebWalk] The fairy ring did not open for $link")
            return false
        }
        val wanted = FAIRY_RING_DIALS.mapIndexed { index, dial ->
            val letter = code.getOrNull(index)?.lowercaseChar() ?: return false
            dial.letters.indexOf(letter).takeIf { it >= 0 } ?: return false
        }
        // Every dial still off its letter turns in the same round, one click each, the short way round.
        var rounds = 0
        while (FAIRY_RING_DIALS.indices.any { FAIRY_RING_DIALS[it].position != wanted[it] }) {
            if (++rounds > FAIRY_RING_ROUNDS || !dialOpen()) {
                println("[WebWalk] The fairy ring's dials never showed ${code.uppercase()}")
                return false
            }
            val before = FAIRY_RING_DIALS.map { it.position }
            for ((index, dial) in FAIRY_RING_DIALS.withIndex()) {
                if (before[index] == wanted[index]) continue
                val button = if (Math.floorMod(wanted[index] - before[index], 4) == 3) dial.anticlockwise else dial.clockwise
                if (!IFSlot(FAIRY_RING_INTERFACE, button, -1).click(1)) return false
            }
            delayUntil(PROMPT_TIMEOUT_MS) { FAIRY_RING_DIALS.indices.any { FAIRY_RING_DIALS[it].position != before[it] } || !dialOpen() }
            delay(250, 120)
        }
        println("[WebWalk] Dialled ${code.uppercase()}")
        return IFSlot(FAIRY_RING_INTERFACE, FAIRY_RING_TELEPORT, -1).click(1)
    }

    /** The dial is up only while its teleport button shows: one closed mid-turn stays attached with everything hidden. */
    private fun dialOpen(): Boolean =
        interfaces.isOpen(FAIRY_RING_INTERFACE) && (
            interfaces.getComponent(FAIRY_RING_INTERFACE, FAIRY_RING_TELEPORT) != null ||
                interfaces.getComponentRaw(FAIRY_RING_INTERFACE, FAIRY_RING_TELEPORT)?.visible == true
            )

    /** Opens the ring's dial from the portable fairy ring. */
    private suspend fun Script.openPortableRing(): Boolean {
        val (active, inactive) = WebLinkPermissions.portableRingIds.get() ?: return false
        if (WebLinkPermissions.activeRingOutOfReach && inventory.none { it.id == active } && equipment.none { it.id == active }) return false
        return useActivated(active, inactive, PORTABLE_RING_OPTION, "portable fairy ring", WebLinkPermissions::activeRingOutOfReach::set)
    }

    /**
     * Uses a device that must be activated before it works, such as the portable fairy ring or the Spirit tree
     * re-rooter: the active one in the backpack first, then the worn one, else an inactive one is activated and then
     * used. The game refuses an activation while an active one exists anywhere, the bank included; after that
     * refusal [outOfReach] is set, so routes leave the device out until an active one is carried again.
     */
    private suspend fun Script.useActivated(active: Int, inactive: Int, option: String, what: String, outOfReach: (Boolean) -> Unit): Boolean {
        inventory.firstOrNull { it.id == active }?.let { return it.click(option) }
        equipment.firstOrNull { it.id == active }?.let { worn ->
            if (!interfaces.isOpen(WORN_EQUIPMENT_INTERFACE)) {
                println("[WebWalk] The $what is worn and the equipment tab is closed")
                return false
            }
            return worn.click(option)
        }
        val spare = inventory.firstOrNull { it.id == inactive } ?: return false
        val clickedAt = System.currentTimeMillis()
        if (!spare.click(ACTIVATE_OPTION)) return false
        delayUntil(PROMPT_TIMEOUT_MS) { inventory.any { it.id == active } || RecentChat.containsSince(ACTIVATE_REFUSED, clickedAt) }
        val device = inventory.firstOrNull { it.id == active }
        if (device == null) {
            if (RecentChat.containsSince(ACTIVATE_REFUSED, clickedAt)) {
                println("[WebWalk] The active $what is out of reach; routes leave it out")
                outOfReach(true)
            }
            return false
        }
        delay(400, 150)
        return device.click(option)
    }

    /** Chebyshev steps from [area] to [tile]; [Int.MAX_VALUE] on another plane. */
    fun areaDistance(area: WebArea, tile: Tile): Int {
        if (tile.plane != area.plane) return Int.MAX_VALUE
        val dx = if (tile.x < area.minX) area.minX - tile.x else if (tile.x > area.maxX) tile.x - area.maxX else 0
        val dy = if (tile.y < area.minY) area.minY - tile.y else if (tile.y > area.maxY) tile.y - area.maxY else 0
        return max(dx, dy)
    }

    /**
     * Of [candidates], those within [ANCHOR_SLACK] of the one nearest [anchor]; among them the nearest to [destination]
     * when it lies within [DEST_NEAR] on the same plane, then the nearest to [player].
     */
    fun <T> pickAnchored(candidates: List<T>, tile: (T) -> Tile, anchor: WebArea, destination: WebArea?, player: Tile): T? {
        if (candidates.isEmpty()) return null
        val nearest = candidates.minOf { areaDistance(anchor, tile(it)) }
        if (nearest == Int.MAX_VALUE) return null
        val near = candidates.filter { areaDistance(anchor, tile(it)) <= nearest + ANCHOR_SLACK }
        val toDestination = destination?.let { dest -> { c: T -> areaDistance(dest, tile(c)) } }
        val usable = toDestination != null && near.any { toDestination(it) <= DEST_NEAR }
        return near.minWith(
            compareBy<T> { if (usable) toDestination!!(it) else 0 }
                .thenBy { areaDistance(anchor, tile(it)) }
                .thenBy { tile(it).getLongestDelta(player) },
        )
    }

    /**
     * The carried item [target] names; with [option], one that offers it first, so that when several items share a
     * name, as a Slayer helmet and the Slayer cape do, the one the route means is clicked.
     */
    private fun carried(items: List<Item>, target: WebTarget, option: String?): Item? {
        val matching = items.filter { item -> target.matches(item.id, runCatching { item.name }.getOrNull()) }
        if (option == null) return matching.firstOrNull()
        return matching.firstOrNull { offers(it, option) } ?: matching.firstOrNull()
    }

    private fun offers(item: Item, option: String): Boolean = runCatching {
        val def = item.getDef()
        def.getInvOpIdForName(option) >= 0 || def.getEquipOpIdForName(option) >= 0
    }.getOrDefault(false)
}
