package com.projectx.script.impl.qb.Temporary

import org.projectx.core.game.skill.Skill
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.util.gaussian
import java.lang.System.currentTimeMillis
import kotlin.random.Random

@ScriptDescription(
    name = "Gem Mining",
    version = "1.0.0",
    author = "Billy",
    description = "Mine, cut & drop gems. -- Updated to Devin's original script by Billy."
)
class Gemmining : StateMachineScript<Gemmining>() {
    override fun getStartState() = GatherGems
}

private val rock = "Uncommon gem rock"
private val uncuts = Regex("""Uncut\s+\w+""")
private val gems = listOf("Sapphire", "Emerald", "Ruby")

object GatherGems: State<Gemmining>() {
    private var randomDelay = Random.nextLong(2400, 20000)
    private var lastMineTime = currentTimeMillis() - randomDelay

    override suspend fun Gemmining.checkNext(): State<Gemmining>? = if (inventory.isFull) Craft else null

    override suspend fun Gemmining.stateLoop() {
        if (!localPlayer.isAniMoving || (currentTimeMillis() - lastMineTime >= randomDelay)) {
            randomDelay = Random.nextLong(2400, 20000)
            lastMineTime = currentTimeMillis()
            interactClosestReachableObject(rock, "Mine")
        }
        delay(3500, 5200)
    }
}

object Craft: State<Gemmining>() {
    override suspend fun Gemmining.checkNext() = if (!inventory.hasItem(uncuts)) Drop else null

    override suspend fun Gemmining.stateLoop() {
        val craftingLevel = getRealLevel(Skill.CRAFTING)

        val craftableGems = mutableListOf<String>()

        if (craftingLevel >= 20 && inventory.hasItem("Uncut sapphire")) {
            craftableGems.add("Uncut sapphire")
        }
        if (craftingLevel >= 27 && inventory.hasItem("Uncut emerald")) {
            craftableGems.add("Uncut emerald")
        }
        if (craftingLevel >= 34 && inventory.hasItem("Uncut ruby")) {
            craftableGems.add("Uncut ruby")
        }

        if (craftableGems.isEmpty()) {
            if (inventory.isFull) {
                inventory.filter { it.id != -1 && it.name.matches(uncuts) }.forEach { uncutGem ->
                    uncutGem.click("Drop")
                    delay(110, 100)
                }
            }
            return
        }

        if (timeSinceLastXpDrop > gaussian(1200, 2059)) {
            if (!makeXOpen) {
                val gemToCraft = craftableGems.lastOrNull()
                if (gemToCraft != null && inventory.clickItems(gemToCraft, "Craft")) {
                    delayUntil { makeXOpen || timeSinceLastXpDrop < 1200 }
                }
                return
            }
            if (makeXOpen) {
                continueMakeX()
                waitForXPDrop(Skill.CRAFTING)
            }
        }
    }
}

object Drop: State<Gemmining>() {
    override suspend fun Gemmining.checkNext(): State<Gemmining>? = if (inventory.filter { it.id != -1 && it.name.containsAny(gems, true) == true }.isEmpty()) GatherGems else null

    override suspend fun Gemmining.stateLoop() {
        inventory.filter { it.id != -1 && it.name.containsAny(gems, true) == true }.forEach {
            it.click("Drop")
            delay(200, 100)
        }
    }

    fun String.containsAny(keywords: List<String>, ignoreCase: Boolean = true): Boolean {
        return keywords.any { this.contains(it, ignoreCase) }
    }
}