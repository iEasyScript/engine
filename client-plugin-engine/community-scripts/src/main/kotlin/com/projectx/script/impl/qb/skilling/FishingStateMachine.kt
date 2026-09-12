package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.EnumConfigItem
import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Lodestone
import com.projectx.script.api.bank
import com.projectx.script.api.bankOpen
import com.projectx.script.api.checkWorldPop
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.withdrawBankItem
import com.projectx.traversal.Traversal
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random
import com.projectx.script.ScriptDescription
import com.projectx.script.ConfigurableScript
import com.projectx.script.api.depositAllInventory
import com.projectx.script.api.openClosestBank
import com.projectx.script.impl.qb.Quest.umLodestone
import com.projectx.traversal.nodes.DoorInfo
import com.projectx.traversal.nodes.NpcNode

@ScriptDescription(
	name = "Fishing State Machine",
	version = "1.0.0",
	author = "QB",
	description = "Fully automated fishing using state machine pattern",
)
class FishingStateMachine : StateMachineScript<FishingStateMachine>(), ConfigurableScript {
	override fun getStartState(): State<FishingStateMachine> {
		return FishingState()
	}

	val selectedFishType = EnumConfigItem(
		name = "Fish Type",
		description = "Select which fish type to fish",
		enumValues = FishingSpot.entries.toTypedArray(),
		initialValue = FishingSpot.SHRIMP
	)

	val goByLevel = BooleanConfigItem(
		name = "Fish by Level",
		description = "Automatically select best fish based on fishing level",
		initialValue = true
	)

	val worldHop = BooleanConfigItem(
		name = "World Hop", description = "Enable world hop", initialValue = false
	)

	val shouldDropFish = BooleanConfigItem(
		name = "Drop Fish", description = "Drop fish when inventory is full", initialValue = false
	)

	fun onConfigUpdated() {
		println("Config updated")
		this::class.java.declaredFields.filter { ConfigItem::class.java.isAssignableFrom(it.type) }.forEach { field ->
			field.isAccessible = true
			val configItem = field.get(this) as? ConfigItem<*>
			val name = configItem?.name
			val value = configItem?.value
			println("$name: $value")
		}
	}

	var selectedFish: FishingSpot = FishingSpot.SHRIMP
	var baitMissing = false
	var animationDelay = 0
	val rawFishNames = Regex(".*Raw.*", RegexOption.IGNORE_CASE)
	val cookedFishNames = Regex(
        "^(Sailfish|Blue blubber jellyfish|Rocktail|Cavefish|Manta ray)$",
        RegexOption.IGNORE_CASE
    )
	suspend fun Script.dropAllFish() {

		while (inventory.hasItem(rawFishNames)) {
			inventory.getItem(rawFishNames)?.click("Drop")
			delay(random(400, 600))
		}

	}

	fun needsBait(): Boolean {
		return selectedFish.bait != "None" && !inventory.hasItem(selectedFish.bait)
	}

}

class FishingState : State<FishingStateMachine>() {

	var timeControl: Long = 0

	override suspend fun FishingStateMachine.checkNext(): State<FishingStateMachine>? {
		if (getRealLevel(Skill.FISHING) == 1) {
			if (timeControl == 0L) {
				timeControl = System.currentTimeMillis()
				return null
			}
			if (System.currentTimeMillis() - timeControl < 10000) {
				return null
			}
		}
		timeControl = 0L
		if (goByLevel.value) {
			var newSpot = FishingSpot.entries.filter { it.level <= getRealLevel(Skill.FISHING) && it.canAccess() }
				.maxBy { it.level }
			if (newSpot != selectedFish)
				baitMissing = false
			selectedFish = newSpot
		} else {
			selectedFish = selectedFishType.value
		}

		if (selectedFish.areaTile.getDistance(localPlayer.tile) > 15) {
			return selectedFish.initialTraversal()
		}

		if (needsBait() && !baitMissing) {
			println("Need bait")
			return selectedFish.bankTraversal()
		}

		if (inventory.freeSlots < 1 && !shouldDropFish.value) {
			println("Free Slots: ${inventory.freeSlots}")

			return selectedFish.bankTraversal()
		}


		return null
	}

	override suspend fun FishingStateMachine.stateLoop() {
		if (worldHop.value && checkWorldPop()) return

		if (inventory.freeSlots < 1 && !shouldDropFish.value) {
			dropAllFish()
			return
		}
		if (localPlayer.isAnimating) {
			animationDelay = 0
			return
		} else {
			animationDelay++
			if (animationDelay < 3) {
				delay(random(600, 1200))
				return
			}
		}

		val fishingSpot = when (selectedFish) {
			FishingSpot.SWARM -> {
			    findClosestNPC {
			        it.name().equals("Swarm", ignoreCase = true) &&
			                it.hasOption(selectedFish.action)
			    }
			}

			FishingSpot.GHOSTLY_SOLE -> {
				findClosestNPC(60) {
					it.name().equals("Ghostly sole spot", ignoreCase = true) && it.hasOption(selectedFish.action)
				}
			}

			else -> {
				findClosestNPC(60) {
					it.name().equals("Fishing spot", ignoreCase = true) && it.hasOption(selectedFish.action)
				}
			}
		}


		if (fishingSpot == null) {
			delay(random(1000, 2000))
		} else {
			fishingSpot.interact(selectedFish.action)
			delay(random(1800, 2400))

			delayUntil(3000) { localPlayer.isAnimating }
			delay(random(800, 1400))
		}

	}

}

class FishingBanking : State<FishingStateMachine>() {
	override suspend fun FishingStateMachine.checkNext(): State<FishingStateMachine>? {

		if (bankOpen) {
			if (!bank.hasItem(selectedFish.bait) && selectedFish.bait != "None" && !inventory.hasItem(selectedFish.bait)) {

				baitMissing = true
				return FishingState()
			}

		}

		if(inventory.freeSlots > 7 && !needsBait()){
			return FishingState()
		}

		return null
	}

	override suspend fun FishingStateMachine.stateLoop() {
		if (!bankOpen) {
			openClosestBank(false, 100)
			delayUntil(8000) { bankOpen }
			return
		}

		depositAllInventory()
		delay(1200, 200)


		if (needsBait()) {
			if (bank.hasItem(selectedFish.bait)) {
				withdrawBankItem(selectedFish.bait)
			}
			delay(1200, 200)
		}

	}
}

enum class FishingSpot(
	val fishName: String,
	val areaTile: Tile,
	val level: Int,
	val bait: String,
	val action: String,
	val bankTraversal: () -> Traversal<FishingStateMachine>,
	val initialTraversal: () -> Traversal<FishingStateMachine>,
	val canAccess: () -> Boolean = { true }
) {
	SHRIMP(
		"Shrimp",
		Tile.of(3086, 3232, 0),
		1,
		"None",
		"Net",
		{ bankDraynor },
		{ toDraynor }),
	TROUT_SALMON(
		"Trout and Salmon",
		Tile.of(3240, 3243, 0),
		20,
		"Feather",
		"Lure",
		{ bankLumbridge },
		{ toLumbridge }),
	GHOSTLY_SOLE(
		"Ghostly Sole",
		Tile.of(1133, 1724, 1),
		66,
		"Fishing bait",
		"Bait",
		{ bankUmFish },
		{ toUmFish },
		{ umLodestone }),
	SWARM(
		"Swarm Fishing",
		Tile.of(2095, 7078, 0),
		68,
		"None",
		"Net",
		{ bankSwarm },
		{ toSwarm },
		{ getRealLevel(Skill.FISHING) >= 80 }),

}

private val pathToDraynor = listOf(
	Tile.of(3105, 3297, 0),
	Tile.of(3109, 3284, 0),
	Tile.of(3105, 3270, 0),
	Tile.of(3105, 3255, 0),
	Tile.of(3087, 3247, 0),
	Tile.of(3084, 3235, 0)
)
private val pathToDraynorBank = listOf(
	Tile.of(3119, 3253, 0), Tile.of(3093, 3248, 0), Tile.of(3093, 3243, 0)
)

private val toDraynor
	get() = traversal(FishingState(), { FishingSpot.SHRIMP.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToDraynor,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToDraynor.last()) < 5 })
	}

private val bankDraynor
	get() = traversal(
		FishingBanking(), { inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToDraynorBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile,
			pathToDraynorBank,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToDraynorBank.last()) < 5 })
	}


private val pathToLumbridge = listOf(
	Tile.of(3231, 3225, 0),
	Tile.of(3218, 3257, 0),
	Tile.of(3241, 3261, 0),
	Tile.of(3242, 3250, 0)
)
private val pathToLumbridgeBank = listOf(
	Tile.of(3241, 3245, 0), Tile.of(3242, 3262, 0), Tile.of(3213, 3258, 0)
)

private val toLumbridge
	get() = traversal(FishingState(), { FishingSpot.TROUT_SALMON.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToLumbridge,
			fallback = { useLodestone(Lodestone.LUMBRIDGE) },
			reached = { localPlayer.tile.getDistance(pathToLumbridge.last()) < 5 })
	}

private val bankLumbridge
	get() = traversal(
		FishingBanking(), { inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToLumbridgeBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile,
			pathToLumbridgeBank,
			reached = { localPlayer.tile.getDistance(pathToLumbridgeBank.last()) < 5 })
	}


private val pathToUmFish = listOf(
	Tile.of(1085, 1768, 1),
	Tile.of(1099, 1768, 1),
	Tile.of(1100, 1748, 1),
	Tile.of(1113, 1745, 1),
	Tile.of(1134, 1723, 1)
)
private val pathToUmFishBank = listOf(
	Tile.of(1134, 1723, 1), Tile.of(1108, 1740, 1)
)

private val toUmFish
	get() = traversal(FishingState(), { FishingSpot.GHOSTLY_SOLE.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToUmFish,
			fallback = { useLodestone(Lodestone.UM) },
			reached = { localPlayer.tile.getDistance(pathToUmFish.last()) < 5 })
	}

private val bankUmFish
	get() = traversal(
		FishingBanking(), { inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToUmFishBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile, pathToUmFishBank, reached = { localPlayer.tile.getDistance(pathToUmFishBank.last()) < 5 })
	}


private val pathToSwarmBank = listOf(
	Tile.of(2102, 7077, 0),
	Tile.of(2100, 7113, 0)

)


private val bankSwarm = traversal(
	FishingBanking(), { inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToSwarmBank.last()) < 8 }) {
	chebychevPath(
		localPlayer.tile, pathToSwarmBank, reached = { localPlayer.tile.getDistance(pathToSwarmBank.last()) < 5 })
}


private val pathToFishingGuildGate = listOf(
	Tile.of(2634, 3352, 0),
	Tile.of(2614, 3385, 0)

)

private val pathToFishingNPCTELEPORT = listOf(
	Tile.of(2613, 3388, 0),
	Tile.of(2595, 3409, 0)
)

private val pathToSwarm = listOf(
	Tile.of(2134, 7105, 0),
	Tile.of(2122, 7112, 0),
	Tile.of(2110, 7116, 0),
	Tile.of(2103, 7107, 0),
	Tile.of(2103, 7078, 0),
	Tile.of(2100, 7078, 0)

)


val doorInfoNorth = DoorInfo(
	realIdOpen = 28693,
	realIdClosed = 49014,
	locationOpen = Tile.of(2943, 3440, 0),
	locationClosed = Tile.of(2613, 3386, 0),
	tileInside = Tile.of(2613, 3387, 0),
	tileOutside = Tile.of(2613, 3386, 0)
)

val doorInfoSouth = DoorInfo(
	realIdOpen = 28692,
	realIdClosed = 49016,
	locationOpen = Tile.of(2943, 3439, 0),
	locationClosed = Tile.of(2614, 3386, 0),
	tileInside = Tile.of(2614, 3387, 0),
	tileOutside = Tile.of(2614, 3386, 0)
)

private val toSwarm = traversal(FishingState(), { FishingSpot.SWARM.areaTile.withinDistance(localPlayer.tile, 12) }) {
	chebychevPath(
		localPlayer.tile,
		pathToFishingGuildGate,
		fallback = { useLodestone(Lodestone.ARDOUGNE) },
		reached = { localPlayer.tile.getDistance(pathToFishingGuildGate.last()) < 5 })

	if (random(1, 2) == 1) {
		doorIn(doorInfoNorth, reached = { localPlayer.tile.matches(doorInfoNorth.tileInside) })
	} else {
		doorIn(doorInfoSouth, reached = { localPlayer.tile.matches(doorInfoNorth.tileInside) })
	}

	chebychevPath(
		localPlayer.tile,
		pathToFishingNPCTELEPORT,
		reached = { localPlayer.tile.getDistance(pathToFishingNPCTELEPORT.last()) < 5 })
	interactNpc(25190, "Travel") { localPlayer.tile.getDistance(Tile.of(2135, 7107, 0)) < 5 }
	chebychevPath(
		localPlayer.tile,
		pathToSwarm,
		reached = { localPlayer.tile.getDistance(pathToSwarm.last()) < 5 })

}