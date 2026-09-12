package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.traversal.nodes.DoorInfo
import com.projectx.util.random

class F2PLoadstone : State<DebugScript>() {
	private val taverlyCord = Tile(2882, 3443, 0)
	private val taverlyArea = Area.Circular(taverlyCord, 10.0)
	private val lummyCord = Tile(3235, 3224, 0)
	private val lummyArea = Area.Circular(lummyCord, 10.0)
	private val alkaridCord = Tile(3294, 3185, 0)
	private val alkaridArea = Area.Circular(alkaridCord, 10.0)
	private val draynorCord = Tile(3109, 3298, 0)
	private val draynorArea = Area.Circular(draynorCord, 10.0)
	private val varrockCord = Tile(3214, 3376, 0)
	private val varrockArea = Area.Circular(varrockCord, 10.0)
	private val edgevilleCord = Tile(3070, 3504, 0)
	private val edgevilleArea = Area.Circular(edgevilleCord, 10.0)
	private val wildernessCord = Tile(3141, 3631, 0)
	private val wildernessArea = Area.Circular(wildernessCord, 10.0)
	private val fallyCord = Tile(2967, 3406, 0)
	private val fallyArea = Area.Circular(fallyCord, 10.0)
	private val portsarimCord = Tile(3014, 3216, 0)
	private val portsarimArea = Area.Circular(portsarimCord, 10.0)
	private val umCord = Tile(1084, 1768, 1)
	private val umArea = Area.Circular(umCord, 10.0)

	fun allLodesActive(): Boolean {
		println("Checking if all lodestones are active")
		val result = faladorLodestone && lummyLodestone && taverlyLodestone && alkaridLodestone &&
				draynorLodestone && varrockLodestone && edgevilleLodestone &&
				wildernessLodestone && portsarimLodestone &&
				(umQuestComplete && umLodestone || !umQuestComplete)
		return result
	}

	override suspend fun DebugScript.checkNext(): State<DebugScript>? {
		if (QuestDialogs.isDialogOpen()) {
			return QuestDialogState()
		}

		if (!taverlyLodestone) {
			println("Taverly Loadstone")
			return handleTaverly
		} else if (!lummyLodestone) {
			println("Lummy Loadstone")
			return handleLummy
		} else if (!alkaridLodestone) {
			println("Alkarid Loadstone")
			return handleAlkarid
		} else if (!faladorLodestone) {
			println("Falador Loadstone")
			return handleFalador
		} else if (!portsarimLodestone) {
			println("Portsarim Loadstone")
			return handlePortsarim
		} else if (!draynorLodestone) {
			println("Draynor Loadstone")
			return handleDraynor
		} else if (umQuestComplete && !umLodestone) {
			println("UM Loadstone")
			return handleUM
		} else if (!varrockLodestone) {
			println("Varrock Loadstone")
			return handleVarrock
		} else if (!edgevilleLodestone) {
			println("Edgeville Loadstone")
			return handleEdgeville
		} else if (!wildernessLodestone) {
			println("Wilderness Loadstone")
			return handleWilderness
		} else {
			if (allLodesActive()) {
				println("All lodestones active")
			}

			return QuestMainState()
		}
	}

	override suspend fun DebugScript.stateLoop() {
	}
}

val faladorLodestone: Boolean get() = varps.getVarBit(34) == 1
val lummyLodestone: Boolean get() = varps.getVarBit(35) == 1
val taverlyLodestone: Boolean get() = varps.getVarBit(38) == 1
val alkaridLodestone: Boolean get() = varps.getVarBit(28) == 1
val draynorLodestone: Boolean get() = varps.getVarBit(32) == 1
val varrockLodestone: Boolean get() = varps.getVarBit(39) == 1
val edgevilleLodestone: Boolean get() = varps.getVarBit(33) == 1
val wildernessLodestone: Boolean get() = varps.getVarBit(18529) == 1
val portsarimLodestone: Boolean get() = varps.getVarBit(36) == 1
val umQuestComplete: Boolean get() = varps.getVarBit(53549) == 70
val umLodestone: Boolean get() = varps.getVarBit(53270) == 1

val pathTaverly: List<Tile> = listOf(
	Tile.of(2898, 3543, 0),
	Tile.of(2893, 3455, 0),
	Tile.of(2880, 3443, 0)
)

private val handleTaverly
	get() = traversal(F2PLoadstone(), { taverlyLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathTaverly,
			fallback = { useLodestone(Lodestone.BURTHOPE) },
			reached = { localPlayer.tile.getDistance(pathTaverly.last()) < 5 })
		interactObj(69839, "Activate") { taverlyLodestone }
	}

val pathBoat: List<Tile> = listOf(
	Tile.of(2880, 3443, 0),
	Tile.of(2883, 3413, 0),
	Tile.of(2874, 3408, 0)

)

private val handleLummy
	get() = traversal(F2PLoadstone(), { lummyLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathBoat,
			fallback = { useLodestone(Lodestone.TAVERLEY) },
			reached = { localPlayer.tile.getDistance(pathBoat.last()) < 5 })
		interactObj(114172, "Board") { interfaces.isOpen(847) }
		clickIFSlot(IFSlot(847, 22, -1), 0)
		interactObj(69836, "Activate") { lummyLodestone }
	}

val pathAlkarid: List<Tile> = listOf(
	Tile.of(3236, 3225, 0),
	Tile.of(3254, 3225, 0),
	Tile.of(3276, 3228, 0),
	Tile.of(3283, 3200, 0),
	Tile.of(3294, 3187, 0)

)

private val handleAlkarid
	get() = traversal(F2PLoadstone(), { alkaridLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathAlkarid,
			fallback = { useLodestone(Lodestone.LUMBRIDGE) },
			reached = { localPlayer.tile.getDistance(pathAlkarid.last()) < 5 })
		interactObj(69829, "Activate") { alkaridLodestone }
	}

val pathToGate: List<Tile> = listOf(
	Tile.of(2881, 3442, 0),
	Tile.of(2888, 3414, 0),
	Tile.of(2908, 3414, 0),
	Tile.of(2923, 3439, 0),
	Tile.of(2938, 3439, 0)
)

val pathToFalador: List<Tile> = listOf(
	Tile.of(2945, 3439, 0),
	Tile.of(2967, 3409, 0)
)

val doorInfoNorth = DoorInfo(
	realIdOpen = 28693,
	realIdClosed = 28691,
	locationOpen = Tile.of(2943, 3440, 0),
	locationClosed = Tile.of(2942, 3440, 0),
	tileInside = Tile.of(2942, 3440, 0),
	tileOutside = Tile.of(2943, 3440, 0)
)

val doorInfoSouth = DoorInfo(
	realIdOpen = 28692,
	realIdClosed = 28690,
	locationOpen = Tile.of(2943, 3439, 0),
	locationClosed = Tile.of(2942, 3439, 0),
	tileInside = Tile.of(2942, 3439, 0),
	tileOutside = Tile.of(2943, 3439, 0)
)

private val handleFalador
	get() = traversal(F2PLoadstone(), { faladorLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToGate,
			fallback = { useLodestone(Lodestone.TAVERLEY) },
			reached = { localPlayer.tile.getDistance(pathToGate.last()) < 5 })
		if (random(1, 2) == 1) {
			doorOut(doorInfoNorth, reached = { localPlayer.tile.matches(doorInfoNorth.tileOutside) })
		} else {
			doorOut(doorInfoSouth, reached = { localPlayer.tile.matches(doorInfoSouth.tileOutside) })
		}

		chebychevPath(
			localPlayer.tile,
			pathToFalador,
			reached = { localPlayer.tile.getDistance(pathToFalador.last()) < 5 })
		interactObj(69835, "Activate") { faladorLodestone }
	}

val pathPortsarim: List<Tile> = listOf(
	Tile.of(2966, 3403, 0),
	Tile.of(2967, 3382, 0),
	Tile.of(3005, 3363, 0),
	Tile.of(3008, 3220, 0)

)

private val handlePortsarim
	get() = traversal(F2PLoadstone(), { portsarimLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathPortsarim,
			fallback = { useLodestone(Lodestone.FALADOR) },
			reached = { localPlayer.tile.getDistance(pathPortsarim.last()) < 5 })
		interactObj(69837, "Activate") { portsarimLodestone }
	}

val pathDraynor: List<Tile> = listOf(
	Tile.of(3015, 3215, 0),
	Tile.of(3037, 3246, 0),
	Tile.of(3045, 3273, 0),
	Tile.of(3066, 3275, 0),
	Tile.of(3089, 3288, 0),
	Tile.of(3100, 3295, 0)
)

private val handleDraynor
	get() = traversal(F2PLoadstone(), { draynorLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathDraynor,
			fallback = { useLodestone(Lodestone.PORT_SARIM) },
			reached = { localPlayer.tile.getDistance(pathDraynor.last()) < 5 }
		)
		interactObj(69833, "Activate") { draynorLodestone }
	}

val pathVarrock: List<Tile> = listOf(
	Tile.of(3105, 3295, 0),
	Tile.of(3138, 3294, 0),
	Tile.of(3163, 3288, 0),
	Tile.of(3178, 3310, 0),
	Tile.of(3177, 3361, 0),
	Tile.of(3212, 3376, 0)
)

private val handleVarrock
	get() = traversal(F2PLoadstone(), { varrockLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathVarrock,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathVarrock.last()) < 5 }
		)
		interactObj(69840, "Activate") { varrockLodestone }

	}

val pathEdgeville: List<Tile> = listOf(
	Tile.of(3211, 3378, 0),
	Tile.of(3211, 3421, 0),
	Tile.of(3205, 3428, 0),
	Tile.of(3179, 3429, 0),
	Tile.of(3174, 3450, 0),
	Tile.of(3137, 3468, 0),
	Tile.of(3136, 3515, 0),
	Tile.of(3123, 3514, 0),
	Tile.of(3103, 3503, 0),
	Tile.of(3066, 3502, 0)
)

private val handleEdgeville
	get() = traversal(F2PLoadstone(), { edgevilleLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathEdgeville,
			fallback = { useLodestone(Lodestone.VARROCK) },
			reached = { localPlayer.tile.getDistance(pathEdgeville.last()) < 5 }
		)
		interactObj(69834, "Activate") { edgevilleLodestone }
	}

val pathToWildernessWall: List<Tile> = listOf(
	Tile.of(3069, 3504, 0),
	Tile.of(3072, 3520, 0)
)
val pathToWildernessLode: List<Tile> = listOf(
	Tile.of(3072, 3525, 0),
	Tile.of(3142, 3631, 0)
)

private val handleWilderness
	get() = traversal(F2PLoadstone(), { wildernessLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToWildernessWall,
			fallback = { useLodestone(Lodestone.EDGEVILLE) },
			reached = { localPlayer.tile.getDistance(pathToWildernessWall.last()) < 5 }
		)

		interactObj("Wilderness wall", "Cross") { localPlayer.tile.y > 3521 }
		chebychevPath(
			localPlayer.tile,
			pathToWildernessLode,
			reached = { localPlayer.tile.getDistance(pathToWildernessLode.last()) < 5 }
		)
		interactObj(84754, "Activate") { wildernessLodestone }
	}

val midStep: List<Tile> = listOf(
	Tile.of(3105, 3297, 0),
	Tile.of(3103, 3310, 0),
)

val pathToUM: List<Tile> = listOf(
	Tile.of(1043, 1757, 1),
	Tile.of(1055, 1767, 1),
	Tile.of(1064, 1776, 1),
	Tile.of(1099, 1776, 1),
	Tile.of(1099, 1768, 1),
	Tile.of(1086, 1768, 1),
)

private val handleUM
	get() = traversal(F2PLoadstone(), { umLodestone }) {
		chebychevPath(
			localPlayer.tile,
			midStep,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(midStep.last()) < 5 }
		)
		interactObj(127139, "Enter") { localPlayer.tile.plane == 1 }
		chebychevPath(
			localPlayer.tile,
			pathToUM,
			reached = { localPlayer.tile.getDistance(pathToUM.last()) < 5 }
		)
		interactObj(127266, "Activate") { umLodestone }

	}
