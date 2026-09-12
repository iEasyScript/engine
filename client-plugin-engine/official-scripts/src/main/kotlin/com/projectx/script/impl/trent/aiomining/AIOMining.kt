package com.projectx.script.impl.trent.aiomining

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.chat.MessageType
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.location.SceneObject
import world.gregs.voidps.collision.ClipFlag
import com.projectx.pathfinder.WorldCollision
import com.projectx.pathfinder.routeToObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.traversal.Traversal
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import com.projectx.util.*

private val rockertunitySpotAnims = intArrayOf(7164, 7165)

@ScriptDescription(
    name = "AIO Mining",
    version = "1.0.0",
    author = "Trent",
    description = "AIO Mining. Click the rock you want to mine."
)
class AIOMining : StateMachineScript<AIOMining>() {
    lateinit var miningSpot: MiningSpot
    fun hasMiningSpot() = ::miningSpot.isInitialized

    lateinit var otherName: String
    fun hasOtherName() = ::otherName.isInitialized

    var startTime = 0L
    var startingXp = 0
    var oresGained = 0

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.MINING)
    }

    override fun render() {
        ImGuiDsl.window("AIO Mining") {
            image(graphicTexture(GraphicIds.MINING), 32f, 32f)
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("Location: ${if (hasMiningSpot()) miningSpot else if (hasOtherName()) otherName else "None yet. Click a rock."}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.MINING), startTime)}")
            text("Ores/h: ${getUnitsPerHour(oresGained, startTime)}")
            xpProgressBar(Skill.MINING)
        }
    }

    override fun getStartState() = Init
}

object Init: State<AIOMining>() {
    override suspend fun AIOMining.checkNext() = if (hasMiningSpot() || hasOtherName()) Mine() else null

    override suspend fun AIOMining.stateLoop() { }

    override fun AIOMining.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is SceneObject) return
        val rock = event.target as SceneObject
        if (!hasMiningSpot() && rock.hasOption("Mine")) {
            val spot = MiningSpot.find(rock.name(), rock.tile)
            if (spot != null)
                miningSpot = spot
            else
                otherName = rock.name()
        }
    }
}

private val oreboxes = """.*ore box$""".toRegex()
private const val ADAMANTITE_ORE = 449

class Mine: State<AIOMining>() {
    var currentRock: SceneObject? = null
    var oreboxFull = false

    override suspend fun AIOMining.checkNext() = if (inventory.freeSlots <= 1 && oreboxFull && hasMiningSpot()) {
        miningSpot = when (miningSpot) {
            MiningSpot.COPPER_DWARVEN_MINE -> MiningSpot.TIN_DWARVEN_MINE
            MiningSpot.TIN_DWARVEN_MINE -> MiningSpot.COPPER_DWARVEN_MINE
            else -> miningSpot
        }
        miningSpot.bankTraversal()
    } else null

    override suspend fun AIOMining.stateLoop() {
        val name = if (hasMiningSpot()) miningSpot.rockName else otherName
        if (inventory.freeSlots <= 2 && inventory.hasItem(oreboxes)) {
            inventory.clickItem(oreboxes, "Fill")
            delayUntil(2500) { inventory.freeSlots > 2 }
            return
        }
        currentRock = findClosestReachableObject(24) { it.name() == name && it.hasOption("Mine") }
        val rockertunity = spotAnims.find { rockertunitySpotAnims.contains(it.id) }
        if (rockertunity != null)
            currentRock = findClosestObjectToTile(rockertunity.tile) { it.name() == name && it.hasOption("Mine") }
        if (currentRock?.interact("Mine") == true) {
            waitForXPDrop(Skill.MINING)
            delayUntil(gaussian(7529L, 6592L)) { inventory.freeSlots <= 2 }
        }
    }

    override fun AIOMining.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("You are not able to deposit anything in your backpack into your ore box."))
            oreboxFull = true
    }
}

enum class MiningSpot(val rockName: String, val areaTile: Tile, val bankTraversal: () -> Traversal<AIOMining>) {
    COPPER_DWARVEN_MINE("Copper rock", Tile.of(3026, 9805, 0), ::bankClosestAnvil),
    TIN_DWARVEN_MINE("Tin rock", Tile.of(3026, 9805, 0), ::bankClosestAnvil),
    COAL_BARBARIAN_VILLAGE("Coal rock", Tile.of(3083, 3418, 0), ::bankClosestAnvil),
    IRON_VARROCK_SOUTHWEST("Iron rock", Tile.of(3182, 3373, 0), ::bankVarrockSouthwest),
    MITHRIL_VARROCK_SOUTHWEST("Mithril rock", Tile.of(3182, 3373, 0), ::bankVarrockSouthwest),
    ADAMANTITE_RIMMINGTON("Adamantite rock", Tile.of(2972, 3231, 0), ::bankRimmington),
    ADAMANTITE_PRIF("Adamantite rock", Tile.of(2222, 3326, 1), ::bankTrahaearn),
    RUNITE_MINING_GUILD("Runite rock", Tile.of(3037, 9738, 0), ::depositFromMiningGuild),
    ORIK_MINING_GUILD("Orichalcite rock", Tile.of(3037, 9738, 0), ::depositFromMiningGuild),
    LUMINITE_MINING_GUILD_RESOURCE("Luminite rock", Tile.of(1055, 4516, 0), ::depositFromMiningGuildResourceDungeon),
    DRAKOLITH_MINING_GUILD_RESOURCE("Drakolith rock", Tile.of(1055, 4516, 0), ::depositFromMiningGuildResourceDungeon),
    PHASMATITE_RAX("Phasmatite rock", Tile.of(3690, 3397, 0), ::depositRaxToWars),
    NECRITE_WILDY("Necrite rock", Tile.of(3027, 3800, 0), ::depositNecriteWildy),
    NECRITE_DESERT("Necrite rock", Tile.of(3459, 3137, 0), ::depositNecriteDesert),
    BANITE_WILDERNESS("Banite rock", Tile.of(3057, 3942, 0), ::bankClosestAnvil),
    LIGHT_ANIMICA("Light animica rock", Tile.of(2277, 3160, 0), ::bankUmSmithyLightAnimica),
    DARK_ANIMICA("Dark animica rock", Tile.of(2875, 12638, 2), ::none),
    ;

    companion object {
        fun find(rockName: String, tile: Tile) = entries.find {
                spot -> spot.rockName.equals(rockName, true) && spot.areaTile.withinDistance(tile, 25)
        }
    }
}

private val none get() = traversal(Mine(), { Tile.of(2875, 12638, 2).withinDistance(localPlayer.tile, 25) }) { }

private val bankVarrockSouthwest
    get() = traversal(returnVarrockSouthwest, { inventory.freeSlots > 1 }) {
        path(Tile.of(3182, 3373, 0), Tile.of(3187, 3426, 0)) { Tile.of(3186, 3425, 0).withinDistance(localPlayer.tile, 17) }
        interactObj(113259, "Deposit-all (into metal bank)", 24)
    }

private val returnVarrockSouthwest
    get() = traversal(Mine(), { Tile.of(3182, 3373, 0).withinDistance(localPlayer.tile, 17) }) {
        path(Tile.of(3187, 3426, 0), Tile.of(3182, 3373, 0))
    }

private val bankRimmington
    get() = traversal(returnRimmington, { inventory.freeSlots > 1 }) {
        val orebox = inventory.find { oreboxes.matches(it.name) }?.id ?: -1
        path(Tile.of(2972, 3231, 0), Tile.of(2946, 3222, 0)) { Tile.of(2946, 3222, 0).withinDistance(localPlayer.tile, 14) }
        useItemOnObj(orebox, "Bank chest") { getOreboxCount(ADAMANTITE_ORE) == 0 }
        interactObj("Bank chest", "Load Last Preset from")
    }

private val returnRimmington
    get() = traversal(Mine(), { Tile.of(2972, 3231, 0).withinDistance(localPlayer.tile, 14) }) {
        path(Tile.of(2946, 3222, 0), Tile.of(2972, 3231, 0))
    }

private val bankTrahaearn
    get() = traversal(returnTrahaearn, { inventory.freeSlots > 1 }) {
        path(Tile.of(2222, 3326, 1), Tile.of(2232, 3288, 1)) { Tile.of(2232, 3288, 1).withinDistance(localPlayer.tile, 17) }
        interactObj(92717, "Deposit-all (into metal bank)", 24)
    }

private val returnTrahaearn
    get() = traversal(Mine(), { Tile.of(2222, 3326, 1).withinDistance(localPlayer.tile, 17) }) {
        path(Tile.of(2232, 3288, 1), Tile.of(2222, 3326, 1))
    }

private val depositFromMiningGuild
    get() = traversal(returnToMiningGuild, { inventory.freeSlots > 1 }) {
        interactObj(6226, "Climb-up") { Tile.of(3019, 3339, 0).withinDistance(localPlayer.tile, 4) }
        interactObj(113262, "Deposit-all (into metal bank)", 24)
    }

private val returnToMiningGuild
    get() = traversal(Mine(), { Tile.of(3037, 9738, 0).withinDistance(localPlayer.tile, 25) }) {
        interactObj(2113, "Climb-down", 24)
    }

private val depositFromMiningGuildResourceDungeon
    get() = traversal(returnToMiningGuildResourceDungeon, { inventory.freeSlots > 1 }) {
        interactObj(52866, "Exit") { Tile.of(3022, 9741, 0).withinDistance(localPlayer.tile, 4) }
        interactObj(6226, "Climb-up") { Tile.of(3019, 3339, 0).withinDistance(localPlayer.tile, 4) }
        interactObj(113262, "Deposit-all (into metal bank)", 24)
    }

private val returnToMiningGuildResourceDungeon
    get() = traversal(Mine(), { Tile.of(1055, 4516, 0).withinDistance(localPlayer.tile, 25) }) {
        interactObj(2113, "Climb-down", 24) { Tile.of(3021, 9739, 0).withinDistance(localPlayer.tile, 4) }
        interactObj(52856, "Enter")
    }

private val depositRaxToWars
    get() = traversal(returnToRax, { inventory.freeSlots > 1 }) {
        val umSmithyGrimoire = IFSlot(1464, 15, 17)
        clickIFSlot(umSmithyGrimoire, 2) { Tile.of(1146, 1809, 1).withinDistance(localPlayer.tile, 4) }
        interactObj(127312, "Deposit-all (into metal bank)")
    }

private val returnToRax
    get() = traversal(Mine(), { Tile.of(3690, 3397, 0).withinDistance(localPlayer.tile, 30) }) {
        val warsRetreatTele = IFSlot(1887, 1, 205)
        clickIFSlot(warsRetreatTele) { Tile.of(3294, 10127, 0).withinDistance(localPlayer.tile, 4) }
        path(Tile.of(3294, 10127, 0), Tile.of(3290, 10151, 0))
        interactObj(114762, "Enter")
    }

private val depositNecriteWildy
    get() = traversal(returnToWildyNecrite, { inventory.freeSlots > 1 }) {
        path(Tile.of(3027, 3793, 0), Tile.of(3000, 3689, 0))
        interactObj(113259, "Deposit-all (into metal bank)")
    }

private val returnToWildyNecrite
    get() = traversal(Mine(), { Tile.of(3027, 3800, 0).withinDistance(localPlayer.tile, 15) }) {
        path(Tile.of(3000, 3689, 0), Tile.of(3027, 3793, 0))
    }

private val depositNecriteDesert
    get() = traversal(returnNecriteDesert, { inventory.freeSlots > 1 }) {
        val umSmithyGrimoire = IFSlot(1464, 15, 17)
        clickIFSlot(umSmithyGrimoire, 2) { Tile.of(1146, 1809, 1).withinDistance(localPlayer.tile, 4) }
        interactObj(127312, "Deposit-all (into metal bank)")
    }

private val returnNecriteDesert
    get() = traversal(Mine(), { Tile.of(3459, 3137, 0).withinDistance(localPlayer.tile, 15) }) {
        if (healthPercent < 80)
            walkExact(Tile.of(1149, 1804, 1)) { healthPercent > 95 }
        if (!equipment.hasItem(39370, 39372))
            interactObj(127271, "Load Last Preset from") { equipment.hasItem(39370, 39372) }
        val travellersNecklaceEagle = IFSlot(1464, 15, 2)
        clickIFSlot(travellersNecklaceEagle, 4) { Tile.of(3426, 3140, 0).withinDistance(localPlayer.tile, 10) }
        path(Tile.of(3426, 3140, 0), Tile.of(3459, 3138, 0))
    }

private val bankUmSmithyLightAnimica
    get() = traversal(returnToLightAnimica, { inventory.freeSlots > 1 }) {
        val umSmithyGrimoire = IFSlot(1464, 15, 17)
        clickIFSlot(umSmithyGrimoire, 2) { Tile.of(1146, 1809, 1).withinDistance(localPlayer.tile, 4) }
        interactObj(127312, "Deposit-all (into metal bank)")
    }

private val returnToLightAnimica
    get() = traversal(Mine(), { Tile.of(2278, 3163, 0).withinDistance(localPlayer.tile, 1) }) {
        if (healthPercent < 80)
            walkExact(Tile.of(1149, 1804, 1)) { healthPercent > 95 }
        clickIFSlot(IFSlot(1465, 18), 1) { interfaces.isOpen(1092) }
        clickIFSlot(IFSlot(1092, 32), 1) { Tile.of(2254, 3149, 0).withinDistance(localPlayer.tile, 1) }
        path(Tile.of(2254, 3149, 0), Tile.of(2270, 3160, 0))
        interactObj(3922, "Pass")
    }

private val bankClosestAnvil
    get() = traversal(Mine(), { inventory.freeSlots > 1 }) {
        interactObj("Deposit-all (into metal bank)")
    }