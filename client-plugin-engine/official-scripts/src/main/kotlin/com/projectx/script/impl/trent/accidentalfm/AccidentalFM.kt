package com.projectx.script.impl.trent.accidentalfm

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*

@ScriptDescription(
    name = "Accidental Firemaking/Fletching",
    version = "1.0.0",
    author = "Trent",
    description = "Does all the din-arrow creation activities on anachronia"
)
class AccidentalFM : StateMachineScript<AccidentalFM>() {
    override fun getStartState() =
        if (Tile.of(5234, 2328, 0).withinDistance(localPlayer.tile, distance = 5))
            CutAndDry
        else if (Tile.of(5220, 2348, 0).withinDistance(localPlayer.tile))
            GrabEggs
        else if (Tile.of(5305, 2286, 0).withinDistance(localPlayer.tile, distance = 30))
            TakeDinosFromBarn
        else
            error("Unknown start area for Accidental FM")
}

object GrabEggs : State<AccidentalFM>() {
    override suspend fun AccidentalFM.checkNext() = if (inventory.isFull) DepositEggs() else null

    override suspend fun AccidentalFM.stateLoop() {
        if (interactClosestObject("Pile of dinosaur eggs", "Collect", 25)) {
            waitThenDelayUntil(1500, 15200) { localPlayer.isAnimating }
            waitThenDelayUntil(1500, 45000) { inventory.isFull }
        }
    }
}

class DepositEggs : State<AccidentalFM>() {
    val goodEggs = inventory.count { it.name == "Good egg" }
    val badEggs = inventory.count { it.name == "Bad egg" }
    val depositType = if (goodEggs > badEggs) "Good egg" else "Bad egg"

    override suspend fun AccidentalFM.checkNext() = if (!inventory.hasItem(depositType)) GrabEggs else null

    override suspend fun AccidentalFM.stateLoop() {
        val interaction = when(depositType) {
            "Good egg" -> "Fast incubator" to "Incubate"
            else -> "Fast compost bin" to "Compost"
        }
        if (interactClosestObject(interaction.first, interaction.second, 25)) {
            waitForXPDrop(Skill.FIREMAKING, 15000)
            waitThenDelayUntil(1500) { !inventory.hasItem(depositType) || timeSinceLastXpDrop > 3000 }
        }
    }
}

object CutAndDry : State<AccidentalFM>() {
    var currentTarget: NPC? = null

    override suspend fun AccidentalFM.checkNext() = null

    override suspend fun AccidentalFM.stateLoop() {
        var currentTarget = currentTarget ?: npcs.values
            .filter { it.id == 28978 }
            .maxByOrNull { it.headbars.filter { it -> it.type == 13 }.maxOfOrNull { it -> it.timeLeftMillis } ?: 0 }
        if (currentTarget != null && currentTarget.exists() && currentTarget.interact(0)) {
            waitForXPDrop(Skill.FIREMAKING, 5000)
            delayUntil(120000) { currentTarget?.exists() == false || currentTarget?.headbars?.any { it.type == 13 && it.timeLeftMillis >= 1000 } != true }
            currentTarget = null
        }
    }
}

private const val ITEM_FERTILIZER = 53078

enum class DinoType(val itemId: Int, val feedObjectId: Int, val feedTile: Tile, val nextStageName: String?) {
    NO_FOOD(56281, 123408, Tile.of(5289, 2261, 0), "BROWN"),
    BROWN(53082, 123406, Tile.of(5327, 2269, 0), "YELLOW"),
    YELLOW(53081, 123402, Tile.of(5329, 2289, 0), "PINK"),
    PINK(53079, 123404, Tile.of(5311, 2306, 0), "VIOLET"),
    VIOLET(53079, 0, Tile.of(5311, 2306, 0), null);

    val nextStage: DinoType?
        get() = nextStageName?.let { valueOf(it) }
}

object TakeDinosFromBarn : State<AccidentalFM>() {
    override suspend fun AccidentalFM.checkNext(): State<AccidentalFM>? {
        if (inventory.count(ITEM_FERTILIZER) > 0)
            return BurnFertilizer
        if (inventory.isFull)
            return FeedDinos(DinoType.NO_FOOD)
        return null
    }

    override suspend fun AccidentalFM.stateLoop() {
        val barnTile = Tile.of(5303, 2279, 0)
        if (!barnTile.withinDistance(localPlayer.tile, 7)) {
            walkTo(barnTile.randomize(3), true)
            waitThenDelayUntil(1200, 19000) { barnTile.withinDistance(localPlayer.tile, 7) }
            return
        }

        if (interactClosestObject(123399, "Collect")) {
            delay(300, 450)
            waitThenDelayUntil(1200, 30000) { inventory.isFull }
        }
    }
}

class FeedDinos(private val stage: DinoType) : State<AccidentalFM>() {
    override suspend fun AccidentalFM.checkNext(): State<AccidentalFM>? {
        if (!inventory.hasItem(stage.itemId)) {
            val nextStage = stage.nextStage
            if (nextStage != null)
                return FeedDinos(nextStage)
            if (inventory.count(ITEM_FERTILIZER) > 0)
                return BurnFertilizer
        }
        return null
    }

    override suspend fun AccidentalFM.stateLoop() {
        if (!stage.feedTile.withinDistance(localPlayer.tile, 7)) {
            walkTo(stage.feedTile.randomize(3), true)
            waitThenDelayUntil(1200, 19000) { stage.feedTile.withinDistance(localPlayer.tile, 7) }
            return
        }
        if (interactClosestObject(stage.feedObjectId, "Feed")) {
            delay(300, 450)
            waitThenDelayUntil(1200, 60000) { inventory.count(stage.itemId) == 0 }
        }
    }
}

object BurnFertilizer : State<AccidentalFM>() {
    override suspend fun AccidentalFM.checkNext(): State<AccidentalFM>? =
        if (inventory.hasItem(ITEM_FERTILIZER)) null else TakeDinosFromBarn

    override suspend fun AccidentalFM.stateLoop() {
        if (!hasActiveMakeXProgress && inventory.clickItem(ITEM_FERTILIZER, "Ignite"))
            waitThenDelayUntil(1200, 4000) { hasActiveMakeXProgress }
    }
}