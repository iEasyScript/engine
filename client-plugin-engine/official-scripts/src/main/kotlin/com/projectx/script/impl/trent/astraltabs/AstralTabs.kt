package com.projectx.script.impl.trent.astraltabs

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

@ScriptDescription(
    name = "Astral Tab Runecrafting",
    version = "1.0.0",
    author = "Trent",
    description = "Runecrafts astral runes using teletabs"
)
class AstralTabs : StateMachineScript<AstralTabs>() {
    private val startXp = getXp(Skill.RUNECRAFTING)
    private val startTime = System.currentTimeMillis()
    var trips = 0

    override fun getStartState() = CheckInventory
}

object CheckInventory : State<AstralTabs>() {
    override suspend fun AstralTabs.checkNext() =
        if (inventory.count("Pure essence") <= 0) LoadPreset
        else CraftRunes

    override suspend fun AstralTabs.stateLoop() {
        delay(100, 150)
    }
}

private const val fishingBankId = 110591
private const val umSmithyBankId = 127271

object LoadPreset : State<AstralTabs>() {
    override suspend fun AstralTabs.checkNext() =
        if (inventory.count("Pure essence") > 0) CraftRunes
        else null

    override suspend fun AstralTabs.stateLoop() {
        if (findClosestObject(fishingBankId) == null) {
            trips++
            val gote = IFSlot(1464, 15, 2)
            if (gote.click(2)) {
                delayUntil(7000, 200) { findClosestObject(fishingBankId) != null }
                delay(452, 625)
            }
            return
        }
        interactClosestObject(fishingBankId, "Load Last Preset from")
        delayWhile(7000) { inventory.count("Pure essence") <= 0 }
    }
}

object CraftRunes : State<AstralTabs>() {
    override suspend fun AstralTabs.checkNext() =
        if (inventory.count("Pure essence") <= 0) LoadPreset
        else null

    override suspend fun AstralTabs.stateLoop() {
        if (bankOpen) {
            closeBank()
            delayUntil { !bankOpen }
        }

        if (findClosestObject(fishingBankId) != null) {
            if (inventory.clickItem("Astral altar teleport", "Break")) {
                delayWhile(7000) { findClosestObject(fishingBankId) != null }
                delay(225, 625)
            }
            return
        }

        if (interactClosestObject("Astral altar", "Craft runes", 25)) {
            delay(855, 1100)
            if (varps.getVarBit(41918) <= 0)
                inventory.firstOrNull { it.name.contains("Extreme runecrafting") }?.click("Drink")

            if (interactClosestObject("Astral altar", "Craft runes", 25)) {
                delayWhile(20000) { inventory.count("Pure essence") > 0 }
                waitUntilNotAniMoving()
            }
        }
    }
}