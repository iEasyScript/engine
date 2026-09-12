package com.projectx.script.impl.trent.necrorc

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.util.format
import com.projectx.util.formatElapsedTime
import com.projectx.util.gaussian
import com.projectx.util.getFormattedUnitsPerHour
import com.projectx.util.getFormattedXpPerHour

private const val SPIRIT_ID = 127380
private const val BONE_ID = 127381
private const val FLESH_ID = 127382
private const val MIASMA_ID = 127383

@ScriptDescription(
    name = "Necromancy Runecrafting",
    version = "1.0.0",
    author = "Trent",
    description = "Necromancy runecrafting with altar cycling"
)
class NecromancyRunecrafting : StateMachineScript<NecromancyRunecrafting>(), ConfigurableScript {
    val cycleAltars = BooleanConfigItem(
        name = "Cycle Altars",
        description = "Cycle between different altars",
        initialValue = true
    )

    val tripsBeforeCycle = IntConfigItem(
        name = "Trips Before Cycle",
        description = "Number of trips before cycling to next altar",
        initialValue = 15,
        min = 1,
        max = 100
    )

    private val startXp = getXp(Skill.RUNECRAFTING)
    private val startTime = System.currentTimeMillis()
    var trips = 0
    var currentAltarId = SPIRIT_ID

    override fun getStartState() = CheckInventory

    fun cycleAltar() {
        currentAltarId = when (currentAltarId) {
            SPIRIT_ID -> BONE_ID
            BONE_ID -> FLESH_ID
            FLESH_ID -> MIASMA_ID
            MIASMA_ID -> SPIRIT_ID
            else -> SPIRIT_ID
        }
    }

    fun getAltarDive() = when (currentAltarId) {
        SPIRIT_ID -> Tile.of(1315, 1967, 1)
        BONE_ID -> Tile.of(1300, 1958, 1)
        FLESH_ID -> Tile.of(1315, 1939, 1)
        MIASMA_ID -> Tile.of(1324, 1951, 1)
        else -> Tile.of(1315, 1967, 1)
    }

    fun printProgressReport() {
        val currentXp = getXp(Skill.RUNECRAFTING)
        val diffXp = currentXp - startXp
        val currentLevel = getRealLevel(Skill.RUNECRAFTING)
        val timeStr = formatElapsedTime(System.currentTimeMillis(), startTime)
        println("$timeStr | Runecrafting: $currentLevel | XP/H: ${getFormattedXpPerHour(startXp, currentXp, startTime)} | XP: ${format(diffXp)} | Trips/Ph: ${getFormattedUnitsPerHour(trips, startTime)}")
    }
}

object CheckInventory : State<NecromancyRunecrafting>() {
    override suspend fun NecromancyRunecrafting.checkNext() =
        if (inventory.count("Impure essence") <= 0) LoadPreset
        else CraftRunes

    override suspend fun NecromancyRunecrafting.stateLoop() {
        printProgressReport()
        delay(100, 150)
    }
}

private const val fishingBankId = 110591
private const val umSmithyBankId = 127271

object LoadPreset : State<NecromancyRunecrafting>() {
    override suspend fun NecromancyRunecrafting.checkNext() =
        if (inventory.count("Impure essence") > 0) CraftRunes
        else null

    override suspend fun NecromancyRunecrafting.stateLoop() {
        if (findClosestObject(fishingBankId) == null) {
            trips++
            if (cycleAltars.value && trips % tripsBeforeCycle.value == 0)
                cycleAltar()
            val gote = IFSlot(1464, 15, 2)
            if (gote.click(2)) {
                delayUntil(7000, 200) { findClosestObject(fishingBankId) != null }
                delay(452, 625)
            }
            return
        }
        interactClosestObject(fishingBankId, "Load Last Preset from")
        delayWhile(7000) { inventory.count("Impure essence") <= 0 }
    }
}

object CraftRunes : State<NecromancyRunecrafting>() {
    override suspend fun NecromancyRunecrafting.checkNext() =
        if (inventory.count("Impure essence") <= 0) LoadPreset
        else null

    override suspend fun NecromancyRunecrafting.stateLoop() {
        if (bankOpen) {
            closeBank()
            delayUntil { !bankOpen }
        }

        if (findClosestObject(fishingBankId) != null) {
            if (!dialogueOptionVisible("City of Um: Haunt on the Hill")) {
                if (inventory.clickItem("Passing bracelet", "Rub"))
                    delayUntil { dialogueOptionVisible("City of Um: Haunt on the Hill") }
                return
            }
            if (continueDialogueContaining("City of Um: Haunt on the Hill")) {
                delayWhile(7000) { findClosestObject(fishingBankId) != null }
                delay(225, 625)
            }
            return
        }

        if (localPlayer.tile.getDistance(Tile.of(1164, 1829, 1)) < 15) {
            if (!interactClosestObject(127376, "Enter"))
                return delay(1200, 500)
            waitThenDelayUntil(gaussian(922L, 1200L), gaussian(3200L, 1200L)) { localPlayer.isMoving }
            if (surge())
                delay(625, 625)
            if (interactClosestObject(127376, "Enter"))
                delayWhile(20000) { localPlayer.tile.getDistance(Tile.of(1164, 1829, 1)) < 15 }
            return
        }

        if (interactClosestObject(currentAltarId, "Craft runes", 25)) {
            delay(855, 1100)
            if (varps.getVarBit(41918) <= 0)
                inventory.firstOrNull { it.name.contains("Extreme runecrafting") }?.click("Drink")

            val diveTarget = getAltarDive()
            if (dive(diveTarget))
                delay(425, 625)

            if (interactClosestObject(currentAltarId, "Craft runes", 25)) {
                delayWhile(20000) { inventory.count("Impure essence") > 0 }
                waitUntilNotAniMoving()
            }
        }
    }
}