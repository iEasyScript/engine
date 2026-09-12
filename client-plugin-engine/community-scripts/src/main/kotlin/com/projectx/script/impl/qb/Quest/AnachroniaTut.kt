package com.projectx.script.impl.qb.Quest
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.State
import com.projectx.script.api.*
import com.projectx.traversal.Traversal.Companion.traversal

class AnachroniaTut : State<DebugScript>() {
	companion object {
		enum class QuestDialogues(val number: Int, val text: String) {
			YES(1, "Yes.")
		}

		private var boatInteraction = false
		private var interactedWithFootPrint = false
		private var arrivedAtAnachronia = false
		private var firstBoneDone = false
		private var secondBoneDone = false
		private var secondBoneTile: Tile? = null
		private var talkedToGiles = false
		private var assignedWorkers = false
		private var buildBasePrompted = false

		private val startCoord = Tile.of(3387, 3421, 0)
		private val startArea = Area.Circular(startCoord, 10.0)

		private val pathToStart: List<Tile> = listOf(
			Tile.of(3215, 3375, 0),
			Tile.of(3236, 3372, 0),
			Tile.of(3259, 3370, 0),
			Tile.of(3297, 3373, 0),
			Tile.of(3314, 3392, 0),
			Tile.of(3338, 3396, 0),
			Tile.of(3365, 3398, 0),
			Tile.of(3371, 3417, 0),
			Tile.of(3385, 3418, 0)
		)

		public var pathToBoneOne: List<Tile> = listOf(
		)
		public var pathToBoneTwo: List<Tile> = listOf(
		)

	}

	override suspend fun DebugScript.checkNext(): State<DebugScript>? {
		val questVarp = varps.getVarBit(44451)
		if (questVarp == 1)
			{
				return QuestMainState()
			}

		println("RunningStep For AnaChroniaTut")

		QuestDialogs.resetDialogOptions()
		QuestDialogs.updateQuestDialogOptions(AnachroniaTut.Companion.QuestDialogues.entries.map { it.text })
		if (QuestDialogs.isDialogOpen()) {
			println("[AnachroniaTut] Dialog open -> QuestDialogState")
			return QuestDialogState()
		}

		if (varcs.getVarBit(16903) == 1) {
			return null
		}

		if (localPlayer.isMoving)
			return null

		if (!boatInteraction) {
			println("[AnachroniaTut] Handling Boat Interaction To Anachronia")
			boatInteraction = true
			return moveToStart
		}

		if (inInstancedArea) {
			println("In Instance")
			if (!interactedWithFootPrint) {
				println("Interacting with footprint")
				delay(3000)
				if (allObjects.filter { it.name() == "Footprint" && it.hasOption("Inspect") }
						.minByOrNull { it.tile.getDistance(localPlayer.tile) }?.interact("Inspect") == true) {
					waitThenDelayUntil(1200, 5000) { interfaces.isOpen(1188) }

					allObjects.filter { it.name() == "Footprint" && it.hasOption("Inspect") }
						.minByOrNull { it.tile.getDistance(localPlayer.tile) }?.tile?.getDistance(localPlayer.tile)
						?.let {
							if (it < 2) {
								interactedWithFootPrint = true
								var firstTile = tileOfLocal(29, 32, 0)
								var secondile = Tile.of(firstTile.transform(22, 17, 0))
								var thirdTile = Tile.of(secondile.transform(40, -1, 0))
								var fourthTile = Tile.of(thirdTile.transform(9, -35, 0))

								pathToBoneOne = listOf(
									firstTile,
									secondile,
									thirdTile,
									fourthTile
								)
							}
						}
				}
				return null
			}

			if (!firstBoneDone && pathToBoneOne.isNotEmpty()) {
				firstBoneDone = true
				return moveToBoneOne
			}

			if ((allObjects.firstOrNull { it.id == 113907 && it.tile.getDistance(localPlayer.tile) < 100 } != null || secondBoneTile != null) && !secondBoneDone && firstBoneDone) {
				var bone2 =
					allObjects.firstOrNull { it.id == 113907 && it.tile.getDistance(localPlayer.tile) < 100 }
				println("Dealing With Second Bone")
				if (bone2 != null) {
					println("Bone2 Not null")
					secondBoneTile = bone2.tile.transform(15, -30)

				}

				println(secondBoneTile)
				println(localPlayer.tile)

				if (secondBoneTile != null && secondBoneTile!!.getDistance(localPlayer.tile) > 15) {
					println("walking")
					walkTo(secondBoneTile!!, true)
					waitThenDelayUntil(1200, 10000) { secondBoneTile!!.getDistance(localPlayer.tile) < 15 }
					return null
				}

				if (bone2 != null && bone2.tile.getDistance(localPlayer.tile) < 40) {
					bone2.interact("Investigate")
					waitThenDelayUntil(1200, 5000) { interfaces.isOpen(1188) }
					if (interfaces.isOpen(1188)) {
						secondBoneDone = true
					}
				}

				return null
			}
		}

		if(!talkedToGiles){
			var giles= npcs.values.firstOrNull { it.name() == "Giles" && it.hasOption("Talk to") }
			if(giles!=null){
				giles.interact("Talk to")
				delayUntil(5000) { interfaces.isOpen(1188) || interfaces.isOpen(1191) || interfaces.isOpen(1184)}
				if(interfaces.isOpen(1188) || interfaces.isOpen(1191) || interfaces.isOpen(1184)) {
					talkedToGiles = true
				}

			}
			return null
		}

		if(!assignedWorkers){
			if(!interfaces.isOpen(176)) {
				var giles = npcs.values.firstOrNull { it.name() == "Giles" && it.hasOption("Talk to") }
				if (giles != null) {
					giles.interact("Talk to")
				}
				return null
			}
			IFSlot(176,7,-1).click()
			delay(200)
			IFSlot(176,36,2).click()
			delay(200)
			IFSlot(176,36,2).click()
			delay(200)
			IFSlot(176,36,5).click()
			delay(200)
			IFSlot(176,36,5).click()
			delay(200)
			IFSlot(176,36,14).click()
			delay(200)
			IFSlot(176,36,14).click()
			delay(200)
			IFSlot(176,46,-1).click()
			delayUntil(5000) { interfaces.isOpen(1188) || interfaces.isOpen(1191) || interfaces.isOpen(1184)}
			if(interfaces.isOpen(1188) || interfaces.isOpen(1191) || interfaces.isOpen(1184)) {
				assignedWorkers  = true
			}
		}

		if(assignedWorkers && interfaces.isOpen(176)){
			IFSlot(176,60,-1).click()
			delay(5000)
		}

		return null
	}

	override suspend fun DebugScript.stateLoop() {
		println("RunningLoop For AnaChroniaTut")
	}

	private val moveToStart
		get() = traversal(
			AnachroniaTut(),
			{ interfaces.isOpen(1188) }) {
			chebychevPath(
				localPlayer.tile,
				pathToStart,
				fallback = { useLodestone(Lodestone.VARROCK) },
				reached = { startArea.contains(localPlayer.tile) }
			)
			interactObj(
				"The Stormbreaker",
				"Board"
			) { interfaces.isOpen(1188) }
		}

	private val moveToBoneOne
		get() = traversal(
			AnachroniaTut(),
			{ interfaces.isOpen(1188) || interfaces.isOpen(1191) }) {
			chebychevPath(
				localPlayer.tile,
				pathToBoneOne,
				reached = { pathToBoneOne.last().getDistance(localPlayer.tile) < 10 }
			)
			interactObj(
				113905,
				"Investigate"
			) { interfaces.isOpen(1188) }
		}

	private val moveToBoneTwo
		get() = traversal(
			AnachroniaTut(),
			{ interfaces.isOpen(1188) }) {
			chebychevPath(
				localPlayer.tile,
				pathToBoneOne,
				reached = { pathToBoneOne.last().getDistance(localPlayer.tile) < 10 }
			)
			interactObj(
				113905,
				"Investigate"
			) { interfaces.isOpen(1188) }
		}
}