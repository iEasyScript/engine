package com.projectx.script.impl.trent.gigginwhirlies

import org.projectx.core.game.combat.Effect
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.util.gaussian
import com.projectx.util.random

internal enum class FlowerBox(val objectId: Int, val varbitId: Int) {
    NORTH_1(122489, 50786),
    NORTH_2(122490, 50787),
    SOUTH_1(122491, 50788),
    SOUTH_2(122492, 50789),
}

internal enum class FlowerSide(val box1: FlowerBox, val box2: FlowerBox) {
    NORTH(FlowerBox.NORTH_1, FlowerBox.NORTH_2),
    SOUTH(FlowerBox.SOUTH_1, FlowerBox.SOUTH_2),
}

private fun detectSide() =
    FlowerSide.entries.firstOrNull { side -> findClosestObject(40) { it.id == side.box1.objectId } != null }

private val handlingCroc
    get() = varps.getVarBit(50810) > 0
private val crocActive
    get() = varps.getVarBit(50811) > 0

@ScriptDescription(
    name = "Giggin Whirlies",
    version = "1.0.0",
    author = "Trent",
    description = "Whirligig hunter."
)
class GigginWhirlies : StateMachineScript<GigginWhirlies>(), ConfigurableScript {
    val stackUpgrade = BooleanConfigItem(name = "Unlocked 5 stacks", description = "Check if unlocked 5 stacks", initialValue = false)

    var catchPlain = inventory.count(52807, 52808, 52809, 52810, 52811) <= 0
    internal var side: FlowerSide = FlowerSide.SOUTH

    override fun onStart() {
        side = detectSide() ?: FlowerSide.SOUTH
    }

    override fun getStartState() = Catch
}

object Catch : State<GigginWhirlies>() {
    override suspend fun GigginWhirlies.checkNext(): State<GigginWhirlies>? {
        return if (!catchPlain && (varps.getVarBit(side.box1.varbitId) < 25 || varps.getVarBit(side.box2.varbitId) < 25))
            RestockFlowers
        else
            null
    }

    override suspend fun GigginWhirlies.stateLoop() {
        if (!handlingCroc) {
            if (interactClosestNPC("Handle"))
                delayUntil(gaussian(16298L, 11692L)) { handlingCroc }
            delay(1629, 9928)
            return
        }
        var skipCatching = -1
        if (!crocActive) {
            val first = findClosestNPC { it.name.contains("whirligig") && (catchPlain || !it.name().contains("Plain")) }
            first?.let {
                if (it.interact("Catch")) {
                    delayUntil(gaussian(6228L, 5420L)) { crocActive }
                    skipCatching = it.serverIndex
                }
            }
            return
        }
        while(Effect.SCARAB_STACK.stacks < (if (stackUpgrade.value) 5 else 3)) {
            allNpcsWithinRange(14) { it.serverIndex != skipCatching && it.name.contains("whirligig") && (catchPlain || !it.name().contains("Plain")) }.randomOrNull()?.interact("Catch")
            delay(521, 882)
            if (random(250) == 0)
                delay(1622, 562)
        }
        return delay(352, 629)
    }
}

object RestockFlowers : State<GigginWhirlies>() {
    override suspend fun GigginWhirlies.checkNext(): State<GigginWhirlies>? {
        return if (varps.getVarBit(side.box1.varbitId) > 28 && varps.getVarBit(side.box2.varbitId) > 28)
            Catch
        else
            null
    }

    override suspend fun GigginWhirlies.stateLoop() {
        delayUntil(gaussian(16298L, 11692L)) { !crocActive }
        if (interactClosestObject(side.box1.objectId, "Fill all"))
            delayUntil(gaussian(16298L, 11692L)) { varps.getVarBit(side.box1.varbitId) > 28 }
        if (interactClosestObject(side.box2.objectId, "Fill all"))
            delayUntil(gaussian(16298L, 11692L)) { varps.getVarBit(side.box2.varbitId) > 28 }
        return delay(1529, 2215)
    }
}
