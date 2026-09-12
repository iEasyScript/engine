package com.projectx.script.impl.devin

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.util.gaussian

@ScriptDescription(
    name = "ArchDungTokenFarmer",
    version = "1.0.0",
    author = "Devin",
    description = "Farms arch collection logs at the Daemonheim that rewards dung tokens.",
    visible = false
)
class ArchDungTokenFarmer : StateMachineScript<ArchDungTokenFarmer>() {
    var DepositTile: Tile = Tile.EMPTY
    fun hasDepositTile() = DepositTile != Tile.EMPTY
    lateinit var DepositOp: String
    var DepositNpc = false

    lateinit var excavateName: String
    fun hasExcavateName() = ::excavateName.isInitialized

    var excavateTile: Tile = Tile.EMPTY
    fun hasExcavateTile() = excavateTile != Tile.EMPTY

    override fun getStartState() = InitArchDgTokenFarmer
}

private val gravelIds = intArrayOf(49516, 49517, 49521, 49519, 49523, 50696, 49525)
private val materialIds = intArrayOf(
    49444, 49445, 49460, 49514, 49456, 49510, 49464, 49450, 49454, 49462, 49496, 49506, 49512, 49452,
    49498, 49500, 49502, 49504, 49490, 49494, 49486, 49488, 49492, 49508, 49458, 49472, 49474, 50690,
    50694, 49446, 49468, 49476, 49478, 49480, 49482, 50688, 50686, 50692, 49448, 49484, 49466, 49470
)

object InitArchDgTokenFarmer: State<ArchDungTokenFarmer>() {
    override suspend fun ArchDungTokenFarmer.checkNext(): State<ArchDungTokenFarmer>? {
        return if (hasDepositTile() && hasExcavateName() && hasExcavateTile()) GatherDgArtifacts else null
    }

    override suspend fun ArchDungTokenFarmer.stateLoop() { }

    override fun ArchDungTokenFarmer.onStateEvent(event: Event) {
        if (event !is ManualDoAction) return

        val target = event.target
        if (target !is SceneObject && target !is NPC) return

        val depositOptions = listOf("Deposit materials", "Deposit all", "Load Last Preset from")
        val hasDepositOption = depositOptions.any { option ->
            when (target) {
                is SceneObject -> target.hasOption(option)
                is NPC -> target.hasOption(option)
                else -> false
            }
        }

        if (!hasDepositTile() && hasDepositOption) {
            DepositOp = depositOptions.first { option ->
                when (target) {
                    is SceneObject -> {
                        DepositTile = target.tile
                        target.hasOption(option)
                    }
                    is NPC -> {
                        DepositTile = target.tile
                        target.hasOption(option)
                    }
                    else -> false
                }
            }
            if (target is NPC)
                DepositNpc = true
        }

        if (!hasDepositTile() && inventory.any { it.name.contains(" porter") })
            DepositTile = Tile.of(0, 0, 0)

        if (target is SceneObject && !hasExcavateName() && target.hasOption("Excavate")) {
            excavateName = target.name()
            excavateTile = target.tile
        }
    }
}

object GatherDgArtifacts: State<ArchDungTokenFarmer>() {
    override suspend fun ArchDungTokenFarmer.checkNext(): State<ArchDungTokenFarmer>? {
        if (!inventory.isFull) return null

        val materialCount = inventory.count { materialIds.contains(it.id) }
        val gravelCount = inventory.count(*gravelIds)

        return when {
            inventory.hasItem("Complete tome") -> UseXPBooks
            materialCount > 0 && gravelCount.toDouble() / materialCount > 0.15 -> DropSoils
            else -> DepositArtifacts
        }
    }

    override suspend fun ArchDungTokenFarmer.stateLoop() {
        checkPorter()
        if (localPlayer.isAniMoving) return
        if (interactClosestReachableObject(excavateName, "Excavate")) {
            delay(6629, 5200)
        } else if (excavateTile.withinDistance(localPlayer.tile, 10) || walkTo(excavateTile.randomize(3), true))
            delayUntil(gaussian(5692L, 1059L)) { excavateTile.withinDistance(localPlayer.tile, 11) }
        delay(150, 200)
    }
}

object UseXPBooks: State<ArchDungTokenFarmer>() {
    private const val COMPLETE_TOME = "Complete tome"
    private const val ARCH_JOURNAL = "Archaeology journal"
    private const val RING_OF_KINSHIP = "Ring of kinship"
    private val isAtArchaeologyCampus = { localPlayer.tile.mapSquareId == 13108 || localPlayer.tile.mapSquareId == 13364 }
    private val isAtDaemonheim = { localPlayer.tile.mapSquareId == 13625 || localPlayer.tile.mapSquareId == 13626 }
    private val hasArchJournal = { equipment.hasItem(ARCH_JOURNAL) }
    private val hasRingOfKinship = { equipment.hasItem(RING_OF_KINSHIP) }

    override suspend fun ArchDungTokenFarmer.checkNext() = if (!inventory.hasItem(COMPLETE_TOME) && isAtDaemonheim()) DepositArtifacts else null

    override suspend fun ArchDungTokenFarmer.stateLoop() {
        when {
            isAtDaemonheim() && hasArchJournal() -> {
                equipment.getItem(ARCH_JOURNAL)?.click(2)
                delayUntil(gaussian(10592L, 5220L)) { isAtArchaeologyCampus() && !localPlayer.isAniMoving }
            }
            isAtArchaeologyCampus() && localPlayer.tile.plane == 0 -> {
                findClosestReachableObject("Stairs")?.interact("Climb up")
                delayUntil(gaussian(10592L, 5220L)) { localPlayer.tile.plane == 1 && !localPlayer.isAniMoving  }
            }
            isAtArchaeologyCampus() && localPlayer.tile.plane == 1 && inventory.hasItem(COMPLETE_TOME) -> {
                findClosestReachableObject("Desk")?.interact("Study")
                delayUntil(gaussian(10592L, 5220L)) { !inventory.hasItem(COMPLETE_TOME) && !localPlayer.isAniMoving  }
            }
            isAtArchaeologyCampus() && localPlayer.tile.plane == 1 && !inventory.hasItem(COMPLETE_TOME) && hasRingOfKinship() -> {
                equipment.getItem(RING_OF_KINSHIP)?.click(3)
                delayUntil(gaussian(10592L, 5220L)) { isAtDaemonheim() && !localPlayer.isAniMoving }
            }
            else -> {
                println("Unknown state:")
                println("Arch journal: $hasArchJournal")
                println("Ring of Kinship: $hasRingOfKinship")
                println("Complete tomes: ${inventory.count("Complete tome")}")
            }
        }
    }
}

object DropSoils: State<ArchDungTokenFarmer>() {
    override suspend fun ArchDungTokenFarmer.checkNext() = if (!inventory.hasItem(*gravelIds)) GatherDgArtifacts else null

    override suspend fun ArchDungTokenFarmer.stateLoop() {
        if (inventory.clickItem("Archaeological soil box", "Fill"))
            delay(1200, 1000)
        inventory.filter { gravelIds.contains(it.id) }.forEach {
            it.click("Drop")
            delay(110, 100)
        }
        delay(1200, 1100)
    }
}

object DepositArtifacts: State<ArchDungTokenFarmer>() {
    override suspend fun ArchDungTokenFarmer.checkNext() = if (!inventory.hasItem(*materialIds)) GatherDgArtifacts else null

    override suspend fun ArchDungTokenFarmer.stateLoop() {
        val deposit = if (inventory.hasItem(49976)) {
            if (findClosestObject(range = 15) { it.hasOption("Deposit all") } != null)
                "Deposit all"
            else
                "Deposit materials"
        } else DepositOp
        if (if (DepositNpc && deposit == DepositOp) interactClosestNPC(deposit, 23) else interactClosestReachableObject(deposit, 23))
            waitThenDelayUntil(1200, 60000) { !inventory.hasItem(*materialIds) }
        else if (DepositTile.withinDistance(localPlayer.tile, 10) || walkTo(DepositTile.randomize(3), true))
            delayUntil(gaussian(8220L, 5293L)) { DepositTile.withinDistance(localPlayer.tile, 11) }
        delay(150, 200)
    }
}