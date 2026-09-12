package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.game.interfaces.IFSlot
import com.projectx.traversal.nodes.DoorInfo
import com.projectx.util.*

class P2PLodestone : State<DebugScript>() {

	enum class QuestDialogueOption(val number: Int, val text: String) {
		YES_PLEASE(1, "Yes please.")
	}

	fun allLodesActive(): Boolean {
		println("Checking if all member lodestones are active")
		val result = catherbyLodestone
				&& ardougneLodestone
				&& seersLodestone
				&& fremennikLodestone
				&& eaglePeakLodestone
				&& yanilleLodestone
				&& ooglogLodestone
				&& karamjaLodestone
		return result
	}


	override suspend fun DebugScript.checkNext(): State<DebugScript>? {
		println("Checking next state")

		QuestDialogs.resetDialogOptions()
		QuestDialogs.updateQuestDialogOptions(QuestDialogueOption.entries.map { it.text })

		if (QuestDialogs.isDialogOpen()) {
			return QuestDialogState()
		}

		if (!karamjaLodestone) {
			println("Karamja lodestone not active")
			return handleKaramja
		}

		if (!ardougneLodestone) {
			println("Ardougne lodestone not active")
			return handleArdougne
		}

		if (!seersLodestone) {
			println("Seers lodestone not active")
			return handleSeers
		}

		if (!catherbyLodestone) {
			println("Catherby lodestone not active")
			return handleCatherby
		}

		if (!fremennikLodestone) {
			println("Fremennik lodestone not active")
			return handleFremennik
		}

		if (!eaglePeakLodestone) {
			println("Eagle Peak lodestone not active")
			return handleEaglePeak
		}

		if (!yanilleLodestone) {
			println("Yanille lodestone not active")
			return handleYanille
		}

		if (!ooglogLodestone) {
			println("Ooglog lodestone not active")
			return handleOoglog
		}

		if (allLodesActive()) {
			println("All lodestones active")
		}
		return null
	}

	override suspend fun DebugScript.stateLoop() {

	}
}

val catherbyLodestone: Boolean get() = varps.getVarBit(31) == 1
val ardougneLodestone: Boolean get() = varps.getVarBit(29) == 1
val seersLodestone: Boolean get() = varps.getVarBit(37) == 1
val fremennikLodestone: Boolean get() = varps.getVarBit(18525) == 1
val eaglePeakLodestone: Boolean get() = varps.getVarBit(18524) == 1
val yanilleLodestone: Boolean get() = varps.getVarBit(40) == 1
val ooglogLodestone: Boolean get() = varps.getVarBit(18527) == 1
val karamjaLodestone: Boolean get() = varps.getVarBit(18526) == 1

val pathSarimKaramjaPort: List<Tile> = listOf(
	Tile.of(3010, 3214, 0),
	Tile.of(3027, 3218, 0)
)

val pathPortKaramjaGate: List<Tile> = listOf(
	Tile.of(2956, 3147, 0),
	Tile.of(2907, 3153, 0),
	Tile.of(2882, 3155, 0),
	Tile.of(2853, 3149, 0),
	Tile.of(2825, 3149, 0),
	Tile.of(2818, 3182, 0)
)

val karamjaGateSouth = DoorInfo(
	realIdOpen = 24373,
	realIdClosed = 24369,

	locationOpen = Tile.of(2815, 3182, 0),
	locationClosed = Tile.of(2816, 3182, 0),

	tileInside = Tile.of(2815, 3182, 0),
	tileOutside = Tile.of(2816, 3182, 0)
)

val karamjaGateNorth = DoorInfo(
	realIdOpen = 24374,
	realIdClosed = 24370,

	locationOpen = Tile.of(2815, 3183, 0),
	locationClosed = Tile.of(2816, 3183, 0),

	tileInside = Tile.of(2815, 3183, 0),
	tileOutside = Tile.of(2816, 3183, 0)

)

val pathKaramjaLodestone: List<Tile> = listOf(
	Tile.of(2813, 3183, 0),
	Tile.of(2793, 3178, 0),
	Tile.of(2781, 3186, 0),
	Tile.of(2764, 3184, 0),
	Tile.of(2763, 3149, 0)
)


private val handleKaramja
	get() = traversal(P2PLodestone(), { karamjaLodestone }) {

		chebychevPath(
			localPlayer.tile,
			pathSarimKaramjaPort,
			fallback = { useLodestone(Lodestone.PORT_SARIM) },
			reached = { localPlayer.tile.getDistance(pathSarimKaramjaPort.last()) < 5 })

		interactNpc(376, "Pay fare") { interfaces.isOpen(1184) }
		clickIFSlot(IFSlot(1184, 15, -1), 0) { interfaces.isOpen(1188) }
		clickIFSlot(IFSlot(1188, 8, -1), 0) { !interfaces.isOpen(1188) }
		clickIFSlot(IFSlot(1191, 15, -1), 0) { localPlayer.tile.getDistance(pathPortKaramjaGate.first()) < 5 }

		chebychevPath(
			localPlayer.tile,
			pathPortKaramjaGate,
			reached = { localPlayer.tile.getDistance(pathPortKaramjaGate.last()) < 5 })
		if (random(1, 2) == 1) {
			doorIn(karamjaGateNorth, reached = { localPlayer.tile.matches(karamjaGateNorth.tileInside) })
		} else {
			doorIn(karamjaGateSouth, reached = { localPlayer.tile.matches(karamjaGateSouth.tileInside) })
		}

		chebychevPath(
			localPlayer.tile,
			pathKaramjaLodestone,
			reached = { localPlayer.tile.getDistance(pathKaramjaLodestone.last()) < 5 })
		interactObj(84751, "Activate") { karamjaLodestone }
	}


val pathToLeverEdgeVille: List<Tile> = listOf(
	Tile.of(3067, 3505, 0),
	Tile.of(3088, 3493, 0),
	Tile.of(3092, 3475, 0)
)

val pathToArdougneLodestone: List<Tile> = listOf(
	Tile.of(2563, 3310, 0),
	Tile.of(2579, 3314, 0),
	Tile.of(2593, 3306, 0),
	Tile.of(2593, 3296, 0),
	Tile.of(2606, 3297, 0),
	Tile.of(2609, 3337, 0),
	Tile.of(2633, 3347, 0)
)


private val handleArdougne
	get() = traversal(P2PLodestone(), { ardougneLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToLeverEdgeVille,
			fallback = { useLodestone(Lodestone.EDGEVILLE) },
			reached = { localPlayer.tile.getDistance(pathToLeverEdgeVille.last()) < 5 })
		interactObj(1814, "Pull") { localPlayer.tile.getDistance(Tile.of(3154, 3924, 0)) < 5 }
		interactObj(1815, "Pull") { localPlayer.tile.getDistance(Tile.of(2562, 3311, 0)) < 5 }
		chebychevPath(
			localPlayer.tile,
			pathToArdougneLodestone,
			reached = { localPlayer.tile.getDistance(pathToArdougneLodestone.last()) < 5 })
		interactObj(69830, "Activate") { ardougneLodestone }
	}


val pathToSeersLodestone: List<Tile> = listOf(
	Tile.of(2635, 3349, 0),
	Tile.of(2620, 3378, 0),
	Tile.of(2630, 3390, 0),
	Tile.of(2646, 3428, 0),
	Tile.of(2674, 3462, 0),
	Tile.of(2688, 3481, 0)
)

private val handleSeers
	get() = traversal(P2PLodestone(), { seersLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToSeersLodestone,
			fallback = { useLodestone(Lodestone.ARDOUGNE) },
			reached = { localPlayer.tile.getDistance(pathToSeersLodestone.last()) < 5 })
		interactObj(69838, "Activate") { seersLodestone }
	}

val pathToCatherbyLodestone: List<Tile> = listOf(
	Tile.of(2691, 3483, 0),
	Tile.of(2732, 3484, 0),
	Tile.of(2777, 3440, 0),
	Tile.of(2811, 3449, 0)
)

private val handleCatherby
	get() = traversal(P2PLodestone(), { catherbyLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToCatherbyLodestone,
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToCatherbyLodestone.last()) < 5 })
		interactObj(69832, "Activate") { catherbyLodestone }
	}

val pathToFremennikLodestone: List<Tile> = listOf(
	Tile.of(2687, 3486, 0),
	Tile.of(2667, 3557, 0),
	Tile.of(2653, 3589, 0),
	Tile.of(2655, 3608, 0),
	Tile.of(2700, 3641, 0),
	Tile.of(2712, 3676, 0)
)

private val handleFremennik
	get() = traversal(P2PLodestone(), { fremennikLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToFremennikLodestone,
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToFremennikLodestone.last()) < 5 })
		interactObj(84750, "Activate") { fremennikLodestone }
	}

val pathToYanilleLodestone: List<Tile> = listOf(
    Tile.of(2634, 3348, 0),
    Tile.of(2609, 3338, 0),
    Tile.of(2606, 3291, 0),
    Tile.of(2603, 3286, 0),
    Tile.of(2599, 3268, 0),
    Tile.of(2603, 3236, 0),
    Tile.of(2628, 3208, 0),
	Tile.of(2626, 3140, 0),
	Tile.of(2612, 3100, 0),
	Tile.of(2580, 3095, 0),
	Tile.of(2559, 3089, 0),
	Tile.of(2528, 3092, 0)
)

private val handleYanille
	get() = traversal(P2PLodestone(), { yanilleLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToYanilleLodestone,
			fallback = { useLodestone(Lodestone.ARDOUGNE) },
			reached = { localPlayer.tile.getDistance(pathToYanilleLodestone.last()) < 5 })
		interactObj(69841, "Activate") { yanilleLodestone }
	}


val pathToEaglePeakLodestone: List<Tile> = listOf(
	Tile.of(2631, 3350, 0),
	Tile.of(2626, 3372, 0),
	Tile.of(2581, 3369, 0),
	Tile.of(2582, 3347, 0),
	Tile.of(2460, 3343, 0),
	Tile.of(2365, 3412, 0),
	Tile.of(2366, 3477, 0)
)

private val handleEaglePeak
	get() = traversal(P2PLodestone(), { eaglePeakLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToEaglePeakLodestone,
			fallback = { useLodestone(Lodestone.ARDOUGNE) },
			reached = { localPlayer.tile.getDistance(pathToEaglePeakLodestone.last()) < 5 })
		interactObj(84749, "Activate") { eaglePeakLodestone }
	}

val pathToOoglogLodestone: List<Tile> = listOf(
	Tile.of(2528, 3093, 0),
	Tile.of(2533, 3066, 0),
	Tile.of(2559, 3061, 0),
	Tile.of(2560, 2956, 0),
	Tile.of(2536, 2875, 0)
)

private val handleOoglog
	get() = traversal(P2PLodestone(), { ooglogLodestone }) {
		chebychevPath(
			localPlayer.tile,
			pathToOoglogLodestone,
			fallback = { useLodestone(Lodestone.YANILLE) },
			reached = { localPlayer.tile.getDistance(pathToOoglogLodestone.last()) < 5 })
		interactObj(84752, "Activate") { ooglogLodestone }
	}

