package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import com.projectx.game.items.Item
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.api.woodbox as woodboxInventory
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.traversal.Traversal
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random
import com.projectx.script.impl.qb.Quest.seersLodestone

@ScriptDescription(
	name = "Woodcutting State Machine",
	version = "2.0.0",
	author = "QB",
	description = "Fully automated woodcutting with woodbox support and traversal-based movement",
)
class WoodcuttingStateScript : StateMachineScript<WoodcuttingStateScript>(), ConfigurableScript {

	val selectedTreeType = EnumConfigItem(
		name = "Tree Type",
		description = "Select which tree type to cut",
		enumValues = TreeSpot.entries.toTypedArray(),
		initialValue = TreeSpot.TREE_DRAYNOR
	)

	val goByLevel = BooleanConfigItem(
		name = "Cut by Level",
		description = "Automatically select best tree based on woodcutting level",
		initialValue = true
	)

	val enableDebug = BooleanConfigItem(
		name = "Debug Mode", description = "Enable debug output", initialValue = true
	)

	val upgradeHatchet = BooleanConfigItem(
		name = "Auto Upgrade Hatchet",
		description = "Automatically upgrade hatchet when level requirements are met",
		initialValue = false
	)

	val worldHop = BooleanConfigItem(
		name = "World Hop", description = "Enable world hop", initialValue = false
	)
	var selectedTree: TreeSpot? = null

	override fun getStartState(): State<WoodcuttingStateScript> = InitWoodcutting

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

	var totalCapacity = 0

	fun needsBanking(): Boolean {
		val woodbox = getWoodbox()
		return isWoodboxFull(woodbox) && inventory.freeSlots < 1
	}

	private fun getWoodbox(): Item? {

		return inventory.getItem("Wood box") ?: inventory.getItem("Oak wood box")
		?: inventory.getItem("Willow wood box") ?: inventory.getItem("Teak wood box")
		?: inventory.getItem("Maple wood box") ?: inventory.getItem("Acadia wood box")
		?: inventory.getItem("Mahogany wood box") ?: inventory.getItem("Yew wood box")
		?: inventory.getItem("Magic wood box") ?: inventory.getItem("Elder wood box")
		?: inventory.getItem("Eternal magic wood box")
	}

	private fun isWoodboxFull(woodbox: Item?): Boolean {
		if (woodbox == null) return true

		val woodboxName = woodbox.name
		totalCapacity = getBaseCapacity(woodboxName) + getAdditionalCapacity()

		var count = 0

		if (enableDebug.value) {
			println("[WOODBOX DEBUG] Checking log counts in $woodboxName:")
			println("Logs: ${woodboxInventory.getItem("Logs")?.amount ?: 0}")
			println("Oak logs: ${woodboxInventory.getItem("Oak logs")?.amount ?: 0}")
			println("Willow logs: ${woodboxInventory.getItem("Willow logs")?.amount ?: 0}")
			println("Maple logs: ${woodboxInventory.getItem("Maple logs")?.amount ?: 0}")
			println("Acadia logs: ${woodboxInventory.getItem("Acadia logs")?.amount ?: 0}")
			println("Yew logs: ${woodboxInventory.getItem("Yew logs")?.amount ?: 0}")
			println("Magic logs: ${woodboxInventory.getItem("Magic logs")?.amount ?: 0}")
			println("Elder logs: ${woodboxInventory.getItem("Elder logs")?.amount ?: 0}")
		}

		val logTypes = listOf(
			"Logs",
			"Oak logs",
			"Willow logs",
			"Teak logs",
			"Maple logs",
			"Acadia logs",
			"Mahogany logs",
			"Yew logs",
			"Magic logs",
			"Elder logs"
		)

		logTypes.forEach { logType ->
			val logItem = woodboxInventory.getItem(logType)
			if (logItem != null) {
				count += logItem.amount
			}
		}

		if (enableDebug.value) {
			println("[WOODBOX] Total logs: $count / $totalCapacity")
		}

		return count >= totalCapacity
	}

	private fun getBaseCapacity(woodboxName: String?): Int {
		return when (woodboxName) {
			"Wood box" -> 70
			"Oak wood box" -> 80
			"Willow wood box" -> 90
			"Teak wood box" -> 100
			"Maple wood box" -> 110
			"Acadia wood box" -> 120
			"Mahogany wood box" -> 130
			"Yew wood box" -> 140
			"Magic wood box" -> 150
			"Elder wood box" -> 160
			"Eternal magic wood box" -> 170
			else -> 70
		}
	}

	private fun getAdditionalCapacity(): Int {
		val level = getRealLevel(Skill.WOODCUTTING)
		return when {
			level >= 105 -> 110
			level >= 95 -> 100
			level >= 85 -> 90
			level >= 75 -> 80
			level >= 65 -> 70
			level >= 55 -> 60
			level >= 45 -> 50
			level >= 35 -> 40
			level >= 25 -> 30
			level >= 15 -> 20
			else -> 10
		}
	}

	suspend fun fillWoodbox(): Boolean {
		val woodbox = getWoodbox()
		if (woodbox == null || isWoodboxFull(woodbox)) {
			return false
		}
		if (enableDebug.value) println("[Woodbox] Filling woodbox ${woodbox.name}")

		woodbox.click("Fill")
		delay(random(600, 1200))
		return true
	}

	suspend fun emptyWoodbox() {
		val woodbox = getWoodbox() ?: return

		if (enableDebug.value) println("[Woodbox] Emptying ${woodbox.name} contents")

		woodbox.click("Empty - logs and bird's nests")
		delay(random(750, 1000))
	}

	fun hasToUpgradeHatchet(): Boolean {
		if (!upgradeHatchet.value) return false
		val currentLevel = getRealLevel(Skill.WOODCUTTING)

		val toolbeltHatchetVarbit = varps.getVarBit(18522)

		return when {
			currentLevel >= 90 && toolbeltHatchetVarbit < 11 -> true
			currentLevel >= 80 && toolbeltHatchetVarbit < 10 -> true
			currentLevel >= 70 && toolbeltHatchetVarbit < 9 -> true
			currentLevel >= 60 && toolbeltHatchetVarbit < 8 -> true
			currentLevel >= 50 && toolbeltHatchetVarbit < 6 -> true
			currentLevel >= 40 && toolbeltHatchetVarbit < 5 -> true
			currentLevel >= 30 && toolbeltHatchetVarbit < 4 -> true
			currentLevel >= 20 && toolbeltHatchetVarbit < 2 -> true
			currentLevel >= 10 && toolbeltHatchetVarbit < 1 -> true
			else -> false
		}

	}

}

object InitWoodcutting : State<WoodcuttingStateScript>() {
	override suspend fun WoodcuttingStateScript.checkNext(): State<WoodcuttingStateScript> = WoodcuttingState()
	override suspend fun WoodcuttingStateScript.stateLoop() {}
	override fun WoodcuttingStateScript.onStateEvent(event: Event) {}
}

class WoodcuttingState : State<WoodcuttingStateScript>() {
	var woodboxFull = false

	var timeControl = 0L
	override suspend fun WoodcuttingStateScript.checkNext(): State<WoodcuttingStateScript>? {
		if (enableDebug.value) {
			println(selectedTreeType.value.areaTile.getDistance(localPlayer.tile))
			println(selectedTreeType.value.areaTile)
			println(localPlayer.tile)
		}

		if (getRealLevel(Skill.WOODCUTTING) == 1) {
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
			var newSpot = TreeSpot.entries.filter { it.level <= getRealLevel(Skill.WOODCUTTING) }.maxBy { it.level }
			selectedTree = newSpot
		} else {
			selectedTree = selectedTreeType.value
		}

		if (selectedTree!!.areaTile.getDistance(localPlayer.tile) > 15) {
			return selectedTree!!.initialTraversal()
		}

		if (needsBanking()) {			
			return selectedTree!!.bankTraversal()
		}

		return null
	}

	override suspend fun WoodcuttingStateScript.stateLoop() {
		if (worldHop.value && checkWorldPop()) return

		if (selectedTree != null) {
			if (inventory.freeSlots < 1) {
				fillWoodbox()
				return
			}

			var currentTree = findClosestReachableObject(24) {
				it.name().equals(selectedTree!!.treeName, true) && it.hasOption(selectedTree!!.action)
			}
			val targetTile = selectedTree!!.areaTile
			if (currentTree == null) {
				currentTree = findClosestObjectToTile(targetTile) {
					it.name().equals(selectedTree!!.treeName, true) && it.hasOption(selectedTree!!.action)
				}
			}
			if (currentTree?.interact(selectedTree!!.action) == true) {
				waitThenDelayUntil(5529, random(5552, 12123).toLong()) { !localPlayer.isAniMoving }

			}
		}
	}

	override fun WoodcuttingStateScript.onStateEvent(event: Event) {
		if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains(
				"not able to deposit anything in your backpack into your wood box", true
			)
		) woodboxFull = true
	}
}

enum class TreeSpot(
	val treeName: String,
	val action: String,
	val areaTile: Tile,
	val bankTraversal: () -> Traversal<WoodcuttingStateScript>,
	val initialTraversal: () -> Traversal<WoodcuttingStateScript>,
	val level: Int,
	val canAccess: () -> Boolean = { true }
) {
	TREE_DRAYNOR(
		"Tree", "Chop down", Tile.of(3122, 3255, 0), { bankDraynor }, { toDraynorTrees }, 1
	),
	OAK_DRAYNOR(
		"Oak", "Chop down", Tile.of(3122, 3255, 0), { bankDraynor }, { toDraynorTrees }, 10
	),
	WILLOW_DRAYNOR(
		"Willow", "Chop down", Tile.of(3090, 3230, 0), { bankDraynor }, { toWillows }, 20
	),
	MAPLE_SEERS(
		"Maple Tree", "Chop down", Tile.of(2728, 3500, 0), { bankSeers }, { toSeersMaples }, 40, { seersLodestone }
	),
	YEW_SEERS("Yew", "Chop down", Tile.of(2710, 3463, 0), { bankSeers }, { toSeersYews }, 70), MAGIC_SEERS(
		"Magic tree",
		"Chop down",
		Tile.of(2702, 3397, 0),
		{ bankSeers },
		{ toSeersMagics },
		80,
		{ seersLodestone }
	),
	;

	companion object {
		fun find(treeName: String, tile: Tile) = entries.find { spot ->
			spot.treeName.equals(treeName, true) && spot.areaTile.withinDistance(tile, 25)
		}
	}
}

private val noneWood get() = traversal(WoodcuttingState(), { true }) { }

private val pathToDraynorTrees = listOf(
	Tile.of(3105, 3297, 0),
	Tile.of(3109, 3294, 0),
	Tile.of(3109, 3281, 0),
	Tile.of(3105, 3274, 0),
	Tile.of(3104, 3265, 0),
	Tile.of(3120, 3252, 0)
)
private val pathToDraynorBank = listOf(
	Tile.of(3119, 3253, 0), Tile.of(3093, 3248, 0), Tile.of(3093, 3243, 0)
)
private val pathToDraynorWillows = listOf(
	Tile.of(3105, 3297, 0),
	Tile.of(3110, 3294, 0),
	Tile.of(3105, 3274, 0),
	Tile.of(3105, 3251, 0),
	Tile.of(3087, 3235, 0)
)
private val toDraynorTrees
	get() = traversal(WoodcuttingState(), { TreeSpot.TREE_DRAYNOR.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToDraynorTrees,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToDraynorTrees.last()) < 5 })
	}

private val toWillows
	get() = traversal(WoodcuttingState(), { TreeSpot.WILLOW_DRAYNOR.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToDraynorWillows,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(TreeSpot.WILLOW_DRAYNOR.areaTile) < 5 })
	}

private val bankDraynor
	get() = traversal(
		WoodcuttingBanking(),
		{ inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToDraynorBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile,
			pathToDraynorBank,
			fallback = { useLodestone(Lodestone.DRAYNOR_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToDraynorBank.last()) < 5 })
	}


private val pathToSeersBank = listOf(
	Tile.of(2719, 3495, 0), Tile.of(2726, 3493, 0)
)
private val pathToMaples = listOf(
	Tile.of(2689, 3479, 0), Tile.of(2716, 3484, 0), Tile.of(2719, 3499, 0), Tile.of(2726, 3501, 0)
)
private val pathToYews = listOf(
	Tile.of(2690, 3480, 0), Tile.of(2709, 3462, 0)
)
private val pathToMagics = listOf(
	Tile.of(2688, 3480, 0),
	Tile.of(2686, 3450, 0),
	Tile.of(2694, 3393, 0),
	Tile.of(2702, 3389, 0),
	Tile.of(2703, 3397, 0)
)

private val toSeersMaples
	get() = traversal(WoodcuttingState(), { TreeSpot.MAPLE_SEERS.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToMaples,
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToMaples.last()) < 5 })
	}

private val toSeersYews
	get() = traversal(WoodcuttingState(), { TreeSpot.YEW_SEERS.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToYews,
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToYews.last()) < 5 })
	}

private val toSeersMagics
	get() = traversal(WoodcuttingState(), { TreeSpot.MAGIC_SEERS.areaTile.withinDistance(localPlayer.tile, 12) }) {
		chebychevPath(
			localPlayer.tile,
			pathToMagics,
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToMagics.last()) < 5 })
	}

private val bankSeers
	get() = traversal(
		WoodcuttingBanking(), { inventory.freeSlots > 1 && localPlayer.tile.getDistance(pathToSeersBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile,
			listOf(localPlayer.tile, Tile.of(2726, 3490, 0)),
			fallback = { useLodestone(Lodestone.SEERS_VILLAGE) },
			reached = { localPlayer.tile.getDistance(pathToSeersBank.last()) < 5 })

		chebychevPath(localPlayer.tile, pathToSeersBank.asReversed())
	}

class WoodcuttingBanking : State<WoodcuttingStateScript>() {
	override suspend fun WoodcuttingStateScript.checkNext(): WoodcuttingState? {
		if (hasBanked) {
			hasBanked = false
			return WoodcuttingState()
		}

		return null
	}

	var hasBanked = false

	override suspend fun WoodcuttingStateScript.stateLoop() {
		if (!bankOpen) {
			interactClosestObject("Bank", 100)
			delayUntil(8000) { bankOpen }
			return
		}

		val nextHatchet = getNextHatchet()
		if (upgradeHatchet.value && hasToUpgradeHatchet()) {
			if (inventory.hasItem(nextHatchet)) {
				inventory.getItem(nextHatchet)?.click("Add to tool belt")
				return
			} else {
				if (bank.getItem(nextHatchet) != null) {
					depositAllInventory()
					withdrawBankItem(nextHatchet, 1)
					delay(random(1000, 2000))
					return
				}
			}
		}

		depositAllInventory()
		delay(random(1000, 2000))
		if (bank.hasItem(Regex(".*wood box.*", RegexOption.IGNORE_CASE)) && !inventory.hasItem(
				Regex(
					".*wood box.*",
					RegexOption.IGNORE_CASE
				)
			)
		) {
			withdrawBankItem(Regex(".*wood box.*", RegexOption.IGNORE_CASE), 1)
			delayUntil(2000, 1000) { inventory.hasItem(Regex(".*woodbox.*", RegexOption.IGNORE_CASE)) }
		}

		hasBanked = true
	}

	private fun getNextHatchet(): String {
		val currentLevel = getRealLevel(Skill.WOODCUTTING)
		return when {
			currentLevel >= 90 -> "Elder rune hatchet"
			currentLevel >= 80 -> "Bane hatchet"
			currentLevel >= 70 -> "Necronium hatchet"
			currentLevel >= 60 -> "Orikalkum hatchet"
			currentLevel >= 50 -> "Rune hatchet"
			currentLevel >= 40 -> "Adamant hatchet"
			currentLevel >= 30 -> "Mithril hatchet"
			currentLevel >= 20 -> "Steel hatchet"
			currentLevel >= 10 -> "Iron hatchet"
			else -> "Bronze hatchet"
		}
	}
}