package com.projectx.script.api

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.util.FeatureIds
import com.projectx.util.componentIdFromHash
import com.projectx.util.gaussian
import com.projectx.util.interfaceIdFromHash
import world.gregs.voidps.gameval.Gameval
import java.util.Collections
import java.util.WeakHashMap

private class DeathIds(val death: Int, val reclaim: IFSlot, val confirmYes: IFSlot) {
    val window get() = reclaim.interfaceId
}

private fun deathComponent(name: String): IFSlot {
    val hash = Gameval.requireComponentHash("death:$name")
    return IFSlot(interfaceIdFromHash(hash), componentIdFromHash(hash))
}

private val deathIds = FeatureIds("Death's Office") {
    DeathIds(Gameval.requireId(Gameval.NPC, "death_office_multi"), deathComponent("confirm_button"), deathComponent("popup_yes"))
}

private const val DEATH_RANGE = 30
private const val OFFICE_SETTLE_TICKS = 6
private const val RECLAIM_OPTION = "Reclaim items"

private val officeArrivalTick: MutableMap<Script, Long> = Collections.synchronizedMap(WeakHashMap())

private fun deathNpc(ids: DeathIds): NPC? = findClosestNPC(ids.death, DEATH_RANGE)

/** Whether the player is in Death's Office, after dying. */
val inDeathsOffice: Boolean
    get() = deathIds.get()?.let(::deathNpc) != null

/**
 * Reclaims the items lost on death from Death's Office and pays from the coffer, confirming the payment when the
 * game asks. Gives the office a few ticks to settle after arriving, as the game does not take the option at once.
 * Returns true once the reclaim window has closed after paying; false when not in the office or a step did not land,
 * so call it again on the next pass.
 */
suspend fun Script.reclaimFromDeath(): Boolean {
    val ids = deathIds.get() ?: return false
    val death = deathNpc(ids)
    if (death == null) {
        officeArrivalTick.remove(this)
        return false
    }
    val arrived = officeArrivalTick.getOrPut(this) { ServerTick.count }
    val settle = OFFICE_SETTLE_TICKS - (ServerTick.count - arrived)
    if (settle > 0) awaitServerTicks(settle.toInt())

    if (!interfaces.isOpen(ids.window)) {
        if (!death.interact(RECLAIM_OPTION)) return false
        delayUntil(gaussian(6000L, 1500L)) { interfaces.isOpen(ids.window) }
        if (!interfaces.isOpen(ids.window)) return false
        delay(gaussian(250, 80))
    }
    // The popup's Yes reads as visible even while the popup is hidden, so Confirm always goes first and Yes only
    // follows when the window is still up afterwards.
    if (!ids.reclaim.click(1)) return false
    delayUntil(gaussian(4500L, 1000L)) { !interfaces.isOpen(ids.window) }
    if (interfaces.isOpen(ids.window)) {
        delay(gaussian(250, 80))
        if (ids.confirmYes.click(1)) delayUntil(gaussian(4500L, 1000L)) { !interfaces.isOpen(ids.window) }
    }
    val reclaimed = !interfaces.isOpen(ids.window)
    if (reclaimed) officeArrivalTick.remove(this)
    return reclaimed
}
