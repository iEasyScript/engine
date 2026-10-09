package com.projectx.webwalker

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.script.Script
import com.projectx.script.api.LODESTONE_MAP_INTERFACE
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.interactComponent
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.isLodestoneUiOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.openLodestoneMap

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
    private const val FAIRY_RING_TURNS = 4

    /** Each dial's top letter and its clockwise button, left to right; the code's letters are read in that order. */
    private val FAIRY_RING_DIALS = listOf(10 to 8, 19 to 17, 28 to 26)

    /** Worn items' options only fire while the worn equipment tab is open. */
    private const val WORN_EQUIPMENT_INTERFACE = 1464

    suspend fun start(script: Script, link: WebLink): Boolean = when (link.kind) {
        WebLinkKind.OBJECT, WebLinkKind.DOOR -> script.useObject(link.objectId, link.action, link.searchRadius)
        WebLinkKind.NPC -> useNpc(link)
        WebLinkKind.USE_ON -> useItemOnObject(link)
        WebLinkKind.ITEM -> useCarriedItem(link)
        WebLinkKind.INTERFACE -> true
        WebLinkKind.POA -> useCarriedItem(link) && script.pickMenu(link)
        WebLinkKind.LODESTONE -> script.teleportToLodestone(link)
        WebLinkKind.FAIRY_RING -> script.dialFairyRing(link)
    }

    /** Does one chained step: clicks an interface once it is up, or uses the next object. */
    suspend fun perform(script: Script, link: WebLink, step: WebStep): Boolean = when (step) {
        is WebInterfaceStep -> {
            script.delayUntil(PROMPT_TIMEOUT_MS) { interfaces.isOpen(step.interfaceId) }
            if (!interfaces.isOpen(step.interfaceId)) {
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
            val used = script.useObject(step.objectId, step.action, step.searchRadius)
            if (used) script.delayUntil(OBJECT_STEP_SETTLE_MS) { localPlayer.isAnimating || localPlayer.isMoving }
            used
        }
    }

    private fun Script.useObject(objectId: Int, action: String, searchRadius: Int): Boolean {
        val target = findClosestObject(searchRadius) { obj ->
            (obj.id == objectId || obj.visibleTypeId == objectId) && obj.hasOption(action)
        } ?: findClosestObject(searchRadius) { it.hasOption(action) }
        if (target == null) {
            println("[WebWalk] No '$action' object $objectId in range")
            return false
        }
        return target.interact(action)
    }

    private fun useNpc(link: WebLink): Boolean {
        val wanted = link.target ?: return false
        val npc = findClosestNPC(link.searchRadius) { npc ->
            wanted.matches(npc.id, runCatching { npc.name() }.getOrNull()) && npc.hasOption(link.action)
        }
        if (npc == null) {
            println("[WebWalk] No NPC $wanted offering '${link.action}' in range")
            return false
        }
        return npc.interact(link.action)
    }

    private fun useItemOnObject(link: WebLink): Boolean {
        val item = carried(inventory.toList(), link.target ?: return false) ?: run {
            println("[WebWalk] ${link.target} is not in the backpack for $link")
            return false
        }
        val target = findClosestObject(link.searchRadius) { it.id == link.objectId || it.visibleTypeId == link.objectId }
        if (target == null) {
            println("[WebWalk] No object ${link.objectId} in range to use ${link.target} on")
            return false
        }
        return item.useOn(target)
    }

    /** The backpack copy first: a worn item's options do nothing unless the worn equipment tab happens to be open. */
    private fun useCarriedItem(link: WebLink): Boolean {
        val wanted = link.target ?: return false
        carried(inventory.toList(), wanted)?.let { return it.click(link.action) }
        val worn = carried(equipment.toList(), wanted)
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
        if (!useObject(link.objectId, link.action, link.searchRadius)) return false
        delayUntil(PROMPT_TIMEOUT_MS) { interfaces.isOpen(FAIRY_RING_INTERFACE) }
        if (!interfaces.isOpen(FAIRY_RING_INTERFACE)) {
            println("[WebWalk] The fairy ring did not open for $link")
            return false
        }
        for ((index, dial) in FAIRY_RING_DIALS.withIndex()) {
            val (letter, clockwise) = dial
            val wanted = code.getOrNull(index)?.toString() ?: return false
            var turns = 0
            while (!dialShows(letter, wanted)) {
                if (++turns > FAIRY_RING_TURNS) {
                    println("[WebWalk] The fairy ring's dial ${index + 1} never showed '$wanted'")
                    return false
                }
                if (!IFSlot(FAIRY_RING_INTERFACE, clockwise, -1).click(1)) return false
                delayUntil(PROMPT_TIMEOUT_MS) { dialShows(letter, wanted) || !interfaces.isOpen(FAIRY_RING_INTERFACE) }
                delay(250, 120)
            }
        }
        println("[WebWalk] Dialled ${code.uppercase()}")
        return IFSlot(FAIRY_RING_INTERFACE, FAIRY_RING_TELEPORT, -1).click(1)
    }

    private fun dialShows(component: Int, letter: String): Boolean =
        interfaces.getComponent(FAIRY_RING_INTERFACE, component)?.text?.trim()?.equals(letter, ignoreCase = true) == true

    private fun carried(items: List<Item>, target: WebTarget): Item? =
        items.firstOrNull { item -> target.matches(item.id, runCatching { item.name }.getOrNull()) }
}
