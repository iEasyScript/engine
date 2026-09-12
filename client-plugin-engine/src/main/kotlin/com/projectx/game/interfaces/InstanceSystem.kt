package com.projectx.game.interfaces

import com.projectx.script.Script
import com.projectx.script.api.clickKey
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptionVisible
import com.projectx.script.api.interfaces
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import world.gregs.voidps.gameval.Gameval

private const val INSTANCE_SYSTEM_INTERFACE_ID = 1591

/** Resolved by dev-name so a component-id reshuffle fails loudly at load instead of silently clicking
 *  whatever now sits at a stale id — 1591 has already drifted once (start was 60, now 56). */
private fun boxSlot(name: String) =
    Gameval.requireComponentHash("boss_instance:$name").let { IFSlot(it shr 16, it and 0xFFFF) }

private const val INSTANCE_EXPIRY_TIME_ID = 9925
private const val CLIENT_TIMER_TICK_ID = 6930
private const val TICK_SMOOTHING_OFFSET_ID = 6932
private const val PREVIOUS_TICK_POSITION_ID = 6931
private const val TICKS_PER_MINUTE = 3000
private const val TICKS_PER_SECOND = 50
private const val MILLISECONDS_PER_TICK = 20

data class InstanceDetails(
    val name: String,
    val cost: String,
    val maxPlayers: Int,
    val minCombat: Int,
    val spawnSpeed: String,
    val protection: String
)

class InstanceSystem {
    companion object {
        private val instanceCostSlot = boxSlot("item_text")
        private val instanceNameSlot = boxSlot("bossname_text")

        private val subtractMaxPlayersSlot = boxSlot("playercount_sub_button")
        private val addMaxPlayersSlot = boxSlot("playercount_add_button")
        private val valueMaxPlayersSlot = boxSlot("playercount_text")

        private val subtractMinCombatSlot = boxSlot("combatlevel_sub_button")
        private val addMinCombatSlot = boxSlot("combatlevel_add_button")
        private val valueMinCombatSlot = boxSlot("combatlevel_text")

        private val cycleLeftSpawnSpeedSlot = boxSlot("spawnspeed_sub_button")
        private val cycleRightSpawnSpeedSlot = boxSlot("spawnspeed_add_button")
        private val valueSpawnSpeedSlot = boxSlot("spawnspeed_text")

        private val cycleLeftProtectionSlot = boxSlot("protection_sub_button")
        private val cycleRightProtectionSlot = boxSlot("protection_add_button")
        private val valueProtectionSlot = boxSlot("protection_text")

        private val startInstanceSlot = boxSlot("start_instance")
        private val joinInstanceSlot = boxSlot("join_instance")
        private val rejoinInstanceSlot = boxSlot("rejoin_instance")

        fun isOpen() = interfaces.isOpen(INSTANCE_SYSTEM_INTERFACE_ID)

        private fun getComponentText(slot: IFSlot): String =
            interfaces[slot.interfaceId]?.get(slot.componentId)?.text ?: ""

        private fun getComponentInt(slot: IFSlot, default: Int = 0): Int =
            getComponentText(slot).toIntOrNull() ?: default

        val instanceDetails: InstanceDetails?
            get() = if (!isOpen()) null else InstanceDetails(
                name = getComponentText(instanceNameSlot),
                cost = getComponentText(instanceCostSlot),
                maxPlayers = getComponentInt(valueMaxPlayersSlot, 1),
                minCombat = getComponentInt(valueMinCombatSlot, 3),
                spawnSpeed = getComponentText(valueSpawnSpeedSlot),
                protection = getComponentText(valueProtectionSlot)
            )

        fun setMaxPlayers(increase: Boolean) {
            val slot = if (increase) addMaxPlayersSlot else subtractMaxPlayersSlot
            slot.click(1)
        }

        fun setMinCombat(increase: Boolean) {
            val slot = if (increase) addMinCombatSlot else subtractMinCombatSlot
            slot.click(1)
        }

        fun cycleSpawnSpeed(cycleRight: Boolean) {
            val slot = if (cycleRight) cycleRightSpawnSpeedSlot else cycleLeftSpawnSpeedSlot
            slot.click(1)
        }

        fun cycleProtection(cycleRight: Boolean) {
            val slot = if (cycleRight) cycleRightProtectionSlot else cycleLeftProtectionSlot
            slot.click(1)
        }

        fun startInstance() = startInstanceSlot.click(1)

        fun rejoinInstance() = rejoinInstanceSlot.click(1)


        suspend fun Script.joinInstance() {
            joinInstanceSlot.click(1)
            delay(600, 100)
            IFSlot(1469, 1, 0).click() //last entered player name click?
        }

        fun debugComponents() {
            println("=== Instance Interface Debug ===")
            println("Is interface open: ${isOpen()}")

            if (!isOpen()) {
                println("Instance interface is not open")
                return
            }

            println("\n=== Parsed Instance Details ===")
            instanceDetails?.let { details ->
                println("Instance Name: ${details.name}")
                println("Instance Cost: ${details.cost}")
                println("Max Players: ${details.maxPlayers}")
                println("Min Combat: ${details.minCombat}")
                println("Spawn Speed: ${details.spawnSpeed}")
                println("Protection: ${details.protection}")
            } ?: println("No instance details available")

            println("===========================")
        }

        fun getTimeRemainingMs(): Long {
            val instanceExpiryTimeMinutes = varps.getVar(INSTANCE_EXPIRY_TIME_ID)
            val minutesRemaining = (instanceExpiryTimeMinutes - currentTimeMins - 1).toLong()

            if (minutesRemaining < 0) return 0L

            val clientTimerTick = varcs.getVar(CLIENT_TIMER_TICK_ID) % TICKS_PER_MINUTE
            val tickSmoothingOffset = varcs.getVar(TICK_SMOOTHING_OFFSET_ID)
            val previousTickPosition = varcs.getVar(PREVIOUS_TICK_POSITION_ID)

            val adjustedOffset = if (previousTickPosition > 50) {
                val currentTickPosition = (clientTimerTick + tickSmoothingOffset % TICKS_PER_SECOND)
                val positionDiff = (previousTickPosition % TICKS_PER_SECOND) - (currentTickPosition % TICKS_PER_SECOND)
                if (positionDiff != 0) tickSmoothingOffset + positionDiff else tickSmoothingOffset
            } else
                tickSmoothingOffset

            val finalTickPosition = (clientTimerTick + adjustedOffset % TICKS_PER_SECOND)
            val ticksRemaining = TICKS_PER_MINUTE - finalTickPosition
            val totalMilliseconds = minutesRemaining * 60 * 1000 + ticksRemaining * MILLISECONDS_PER_TICK

            return maxOf(0L, totalMilliseconds)
        }

        fun getFormattedTimeRemaining(): String {
            val millisRemaining = getTimeRemainingMs()
            if (millisRemaining == 0L) return "00:00"

            val totalSeconds = millisRemaining / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return if (hours > 0)
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            else
                String.format("%02d:%02d", minutes, seconds)
        }

        fun isExpired() = getTimeRemainingMs() == 0L

        fun hasOngoingInstance() = getTimeRemainingMs() > 0L

        private val currentTimeMins get() = (System.currentTimeMillis() / 60000).toInt()
    }
}

suspend fun Script.confirmInstanceDialogue(): Boolean {
    if (dialogueOptionVisible("Yes")) {
        continueDialogueContaining("Yes")
        waitThenDelayUntil(1200, 10000) { !isDialogOpen() }
        return true
    }
    if (isDialogOpen()) {
        clickKey(' ')
        waitThenDelayUntil(900, 5000) { dialogueOptionVisible("Yes") || !isDialogOpen() }
        return true
    }
    return false
}

suspend fun Script.startOrRejoinInstance(): Boolean {
    if (confirmInstanceDialogue()) return true
    if (!InstanceSystem.isOpen()) return false
    val entered =
        if (InstanceSystem.hasOngoingInstance()) InstanceSystem.rejoinInstance() else InstanceSystem.startInstance()
    if (entered) {
        waitThenDelayUntil(1200, 10000) { !InstanceSystem.isOpen() || isDialogOpen() }
        return true
    }
    return false
}
