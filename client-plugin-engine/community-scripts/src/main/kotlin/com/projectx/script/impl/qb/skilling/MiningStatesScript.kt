package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.Lodestone
import com.projectx.script.api.bank
import com.projectx.script.api.bankOpen
import com.projectx.script.api.checkWorldPop
import com.projectx.script.api.depositBankItem
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.spotAnims
import com.projectx.script.api.varps
import com.projectx.script.api.walkTo
import com.projectx.script.api.withdrawBankItem
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.impl.qb.Quest.faladorLodestone
import com.projectx.script.impl.qb.Quest.varrockLodestone
import com.projectx.script.impl.qb.Quest.yanilleLodestone
import com.projectx.traversal.Traversal
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random
import kotlin.random.Random
import com.projectx.script.api.depositAllInventory

@ScriptDescription(
	name = "Mining State Machine",
	version = "2.0.0",
	author = "QB",
	description = "Fully automated mining with rockertunity detection, ore box support, and enhanced travel system",
)
class MiningStatesScript : StateMachineScript<MiningStatesScript>(), ConfigurableScript {

	val selectedOreType = EnumConfigItem(
		name = "Ore Type",
		description = "Select which ore type to mine",
		enumValues = MiningSpot.entries.toTypedArray(),
		initialValue = MiningSpot.COPPER_DWARVEN_MINE
	)

	val goByLevel = BooleanConfigItem(
		name = "Mine by Level", description = "Automatically select best ore based on mining level", initialValue = true
	)


	val worldHop = BooleanConfigItem(
		name = "World Hop", description = "Enable world hop", initialValue = false
	)

	val upgradePickaxe = BooleanConfigItem(
		name = "Auto Upgrade Pickaxe",
		description = "Automatically upgrade pickaxe when level requirements are met",
		initialValue = false
	)


	val enableRockertunity = BooleanConfigItem(
		name = "Enable Rockertunity",
		description = "Automatically detect and mine rockertunity spots for bonus XP",
		initialValue = true
	)

	override fun getStartState(): State<MiningStatesScript> {
		return MiningState()

	}

	var bankedPickaxe = true
	var selectedOre: MiningSpot? = null

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

	fun getNextPickaxe(): String {
		val toolbeltPickaxeVarbit = varps.getVarBit(18521)
		val currentLevel = getRealLevel(Skill.MINING)
		return when {
			currentLevel >= 90 -> "Elder rune pickaxe"
			currentLevel >= 80 -> "Bane pickaxe"
			currentLevel >= 70 -> "Necronium pickaxe"
			currentLevel >= 60 -> "Orikalkum pickaxe"
			currentLevel >= 50 -> "Rune pickaxe"
			currentLevel >= 40 -> "Adamant pickaxe"
			currentLevel >= 30 -> "Mithril pickaxe"
			currentLevel >= 20 -> "Steel pickaxe"
			currentLevel >= 10 -> "Iron pickaxe"
			else -> "Bronze pickaxe"
		}
	}

	fun hasToUpgradePickaxe(): Boolean {
		val toolbeltPickaxeVarbit = varps.getVarBit(18521)
		val currentLevel = getRealLevel(Skill.MINING)
		return when {
			currentLevel >= 90 && toolbeltPickaxeVarbit !in 40..46 -> true
			currentLevel in 80..89 && toolbeltPickaxeVarbit !in 35..39 -> true
			currentLevel in 70..79 && toolbeltPickaxeVarbit !in 30..34 -> true
			currentLevel in 60..69 && toolbeltPickaxeVarbit !in 25..29 -> true
			currentLevel in 50..59 && toolbeltPickaxeVarbit !in 16..20 -> true
			currentLevel in 40..49 && toolbeltPickaxeVarbit !in 12..15 -> true
			currentLevel in 30..39 && toolbeltPickaxeVarbit !in 8..11 -> true
			currentLevel in 20..29 && toolbeltPickaxeVarbit !in 5..7 -> true
			currentLevel in 10..19 && toolbeltPickaxeVarbit !in 2..4 -> true
			else -> false
		}
	}

}

private val oreboxes = """.*ore box$""".toRegex()
val rockertunitySpotAnims = intArrayOf(7164, 7165)

class UpgradePickaxe : State<MiningStatesScript>() {
	override suspend fun MiningStatesScript.checkNext(): State<MiningStatesScript>? {
		if (!hasToUpgradePickaxe() || !bankedPickaxe) {
			return MiningState()
		}

		if (findClosestObject { it.hasOption("Bank") } == null) {
			if (localPlayer.isAnimating) {
				walkTo(localPlayer.tile.transform(random(-2, 2), random(-2, 2), 0), true)
				waitThenDelayUntil(1200, 1200) { !localPlayer.isAniMoving }
			}
			return toBankUpgrade
		}

		return null
	}

	override suspend fun MiningStatesScript.stateLoop() {
		if (!bankOpen) {
			interactClosestObject("Bank", 100)
			delayUntil(8000) { bankOpen }
			return
		}

		if (inventory.freeSlots < 1) {
			depositAllInventory()
			delay(600, 200)
			withdrawBankItem(oreboxes)
			delay(600, 200)
			return
		}

		val nextPickaxe = Regex(".*${getNextPickaxe()}.*", RegexOption.IGNORE_CASE)
		if (upgradePickaxe.value && hasToUpgradePickaxe()) {
			if (inventory.hasItem(nextPickaxe)) {
				inventory.getItem(nextPickaxe)?.click("Add to tool belt")
				delay(random(1000, 1500))
				depositBankItem(Regex(".*pickaxe.*", RegexOption.IGNORE_CASE))
				delay(600, 200)
				return
			} else {
				if (bank.getItem(nextPickaxe) != null) {
					depositAllInventory()
					delay(600, 200)
					withdrawBankItem(nextPickaxe)
					delay(600, 200)
					withdrawBankItem(oreboxes)
					delay(600, 200)
					return
				} else {
					bankedPickaxe = false
				}
			}
		}
	}

}

class MiningBank : State<MiningStatesScript>() {
	override suspend fun MiningStatesScript.checkNext(): State<MiningStatesScript>? {
		if (!(inventory.freeSlots < 20 && inventory.count(Regex(".*ore.*")) < 8)) {
			return MiningState()
		}

		if (findClosestObject { it.hasOption("Bank") } == null) {
			if (localPlayer.isAnimating) {
				walkTo(localPlayer.tile.transform(random(-2, 2), random(-2, 2), 0), true)
				waitThenDelayUntil(1200, 1200) { !localPlayer.isAniMoving }
			}
			return toBankMining
		}

		return null
	}

	override suspend fun MiningStatesScript.stateLoop() {
		if (!bankOpen) {
			interactClosestObject("Bank", 100)
			delayUntil(8000) { bankOpen }
			return
		}

		depositAllInventory()
		delay(600, 200)
		withdrawBankItem(oreboxes)

		val nextPickaxe = Regex(".*${getNextPickaxe()}.*", RegexOption.IGNORE_CASE)
		if (upgradePickaxe.value && hasToUpgradePickaxe()) {
			if (inventory.hasItem(nextPickaxe)) {
				inventory.getItem(nextPickaxe)?.click("Add to tool belt")
				delay(random(1000, 1500))
				depositBankItem(Regex(".*pickaxe.*", RegexOption.IGNORE_CASE))
				return
			} else {
				if (bank.getItem(nextPickaxe) != null) {
					depositAllInventory()
					withdrawBankItem(nextPickaxe)
					return
				} else {
					bankedPickaxe = false
				}
			}
		}
	}

}

private val pathToBankUpgrade = listOf(
	Tile.of(2898, 3544, 0),
	Tile.of(2889, 3537, 0)
)
private val toBankUpgrade
	get() = traversal(UpgradePickaxe(), { findClosestObject { it.hasOption("Bank") } != null }) {
		chebychevPath(
			localPlayer.tile,
			pathToBankUpgrade,
			fallback = { useLodestone(Lodestone.BURTHOPE) },
			reached = { localPlayer.tile.getDistance(pathToBankUpgrade.last()) < 5 })
	}

private val toBankMining
	get() = traversal(MiningBank(), { findClosestObject { it.hasOption("Bank") } != null }) {
		chebychevPath(
			localPlayer.tile,
			pathToBankUpgrade,
			fallback = { useLodestone(Lodestone.BURTHOPE) },
			reached = { localPlayer.tile.getDistance(pathToBankUpgrade.last()) < 5 })
	}

class MiningState : State<MiningStatesScript>() {
	var oreboxFull = false

	var timeControl=0L
	override suspend fun MiningStatesScript.checkNext(): State<MiningStatesScript>? {

		if (getRealLevel(Skill.MINING) == 1) {
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
			var newSpot = MiningSpot.entries.filter { it.level <= getRealLevel(Skill.MINING) && it.canAccess() }.maxBy { it.level }
			selectedOre = newSpot
		} else {
			selectedOre = selectedOreType.value
		}

		if (hasToUpgradePickaxe() && upgradePickaxe.value) {
			return UpgradePickaxe()
		}

		if (inventory.freeSlots < 15 && (inventory.count(Regex(".*ore.*")) < 9 && inventory.count("Coal") < 9)) {
			return MiningBank()
		}

		if ((selectedOre?.areaTile?.getDistance(localPlayer.tile) ?: 0) > 20) {
			if (localPlayer.isAnimating) {
				walkTo(localPlayer.tile.transform(random(-2, 2), random(-2, 2), 0), true)
				waitThenDelayUntil(1200, 1200) { !localPlayer.isAniMoving }
			}
			return selectedOre?.initialTraversal()
		}

		println("Orebox Full: $oreboxFull")
		println("Free Slots: ${inventory.freeSlots}")
		println("Has Orebox: ${inventory.hasItem(oreboxes)}")

		if ((inventory.isFull && !inventory.hasItem(oreboxes)) || (inventory.hasItem(oreboxes) && oreboxFull && inventory.isFull)) {
			if (localPlayer.isAnimating) {
				walkTo(localPlayer.tile.transform(random(-2, 2), random(-2, 2), 0), true)
				waitThenDelayUntil(1200, 1200) { !localPlayer.isAniMoving }
			}
			return selectedOre?.bankTraversal()
		}

		return null

	}

	override suspend fun MiningStatesScript.stateLoop() {
		if (worldHop.value && checkWorldPop()) return

		if (inventory.isFull && inventory.hasItem(oreboxes) && !oreboxFull) {
			inventory.clickItem(oreboxes, "Fill")
			delayUntil(2500) { !inventory.isFull }
			return
		}

		var currentRock = findClosestReachableObject(24) { it.name() == selectedOre?.rockName && it.hasOption("Mine") }
		val rockertunity = spotAnims.find { rockertunitySpotAnims.contains(it.id) }
		if (rockertunity != null && Random.nextBoolean() && enableRockertunity.value) {
			currentRock = findClosestObjectToTile(rockertunity.tile) {
				it.name() == selectedOre?.rockName && it.hasOption(
					"Mine"
				)
			}
		}
		if (currentRock?.interact("Mine") == true) {
			waitForXPDrop(Skill.MINING)
			delayUntil(
				30529, 10592
			) {
				(inventory.isFull || !localPlayer.isAnimating || (spotAnims.find { rockertunitySpotAnims.contains(it.id) } != null && enableRockertunity.value)
						|| (localPlayer.headbars.firstOrNull { it.type == 5 }?.toFill ?: (255 * 100 / 255)) < random(
					25,
					50
				))

			}
		}

	}

	override fun MiningStatesScript.onStateEvent(event: Event) {
		if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("You are not able to deposit anything in your backpack into your ore box.")) oreboxFull =
			true
	}
}

enum class MiningSpot(
	val rockName: String,
	val areaTile: Tile,
	val bankTraversal: () -> Traversal<MiningStatesScript>,
	val initialTraversal: () -> Traversal<MiningStatesScript>,
	val level: Int,
	val canAccess: () -> Boolean = { true }
) {
	COPPER_DWARVEN_MINE(
		"Copper rock",
		Tile.of(3025, 9808, 0),
		{ bankDwarvenMine(Tile.of(3025, 9808, 0)) },
		{ toDwarvenMine(toCopperTin) },
		1,
		{ faladorLodestone }),

	TIN_DWARVEN_MINE(
		"Tin rock",
		Tile.of(3025, 9808, 0),
		{ bankDwarvenMine(Tile.of(3025, 9808, 0)) },
		{ toDwarvenMine(toCopperTin) },
		1,
		{ faladorLodestone }),

	IRON_VARROCK_SOUTHWEST(
		"Iron rock",
		Tile.of(3182, 3373, 0),
		{ toVarrockWestBank },
		{ toIronMithril },
		10,
		{ varrockLodestone }

	),
	COAL_DWARVEN_MINE(
		"Coal rock",
		Tile.of(3050, 9823, 0),
		{ bankDwarvenMine(Tile.of(3050, 9823, 0)) },
		{ toDwarvenMine(toCoal) },
		20,
		{ faladorLodestone }),

	MITHRIL_VARROCK_SOUTHWEST(
		"Mithril rock",
		Tile.of(3182, 3373, 0),
		{ toVarrockWestBank },
		{ toIronMithril },
		30,
		{ varrockLodestone }),
	ADAMANTITE(
		"Adamantite rock",
		Tile.of(3290, 3361, 0),
		{ toVarrockWestBankAdamant },
		{ toAdamant },
		40,
		{ varrockLodestone }),
	LUMINITE(
		"Luminite rock",
		Tile.of(2473, 3253, 0),
		{ toLuminiteBank },
		{ toLuminite },
		40,
		{ false }),
	RUNITE(
		"Runite rock",
		Tile.of(2628, 3145, 0),
		{ toRuniteBank },
		{ toRunite },
		50,
		{ yanilleLodestone }),
	ORIK_MINING_GUILD(
		"Orichalcite rock",
		Tile.of(3037, 9738, 0),
		{ miningGuildBank },
		{ toMiningGuild },
		60,
		{ faladorLodestone }),
	;

	companion object {
		fun find(rockName: String, tile: Tile) = entries.find { spot ->
			spot.rockName.equals(rockName, true) && spot.areaTile.withinDistance(tile, 25)
		}
	}
}

private val none get() = traversal(MiningState(), { Tile.of(2875, 12638, 2).withinDistance(localPlayer.tile, 25) }) { }


private val pathToDwarvenBank = listOf(
	Tile.of(3050, 9820, 0), Tile.of(3035, 9834, 0), Tile.of(3026, 9813, 0), Tile.of(3015, 9813, 0)
)

fun bankDwarvenMine(tile: Tile): Traversal<MiningStatesScript> {

	listOf(
		Tile.of(2967, 3403, 0),
		Tile.of(2984, 3418, 0),
		Tile.of(2994, 3431, 0),
		Tile.of(3014, 3432, 0),
		Tile.of(3017, 3448, 0)
	)
	return traversal(MiningState(), { inventory.freeSlots > 1 && tile.getDistance(localPlayer.tile) < 5 }) {
		chebychevPath(
			localPlayer.tile,
			pathToDwarvenBank,
			reached = { localPlayer.tile.getDistance(pathToDwarvenBank.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }

		chebychevPath(localPlayer.tile, listOf(localPlayer.tile, tile))
	}
}

fun toDwarvenMine(state: State<MiningStatesScript>): Traversal<MiningStatesScript> {

	val toDwarvenPath = listOf(
		Tile.of(2967, 3403, 0),
		Tile.of(2984, 3418, 0),
		Tile.of(2994, 3431, 0),
		Tile.of(3014, 3432, 0),
		Tile.of(3017, 3448, 0)
	)
	return traversal(state, { Tile.of(3018, 9850, 0).getDistance(localPlayer.tile) < 25 }) {
		chebychevPath(
			localPlayer.tile,
			toDwarvenPath,
			fallback = { useLodestone(Lodestone.FALADOR) },
			reached = { localPlayer.tile.getDistance(toDwarvenPath.last()) < 5 })
		interactObj(30942, "Climb-down") { localPlayer.tile == Tile.of(3018, 9850, 0) }
	}
}

private val pathToDwarvenCopperTin = listOf(
	Tile.of(3018, 9850, 0), Tile.of(3025, 9808, 0)
)
private val toCopperTin
	get() = traversal(MiningState(), { MiningSpot.COPPER_DWARVEN_MINE.areaTile.withinDistance(localPlayer.tile, 20) }) {
		chebychevPath(
			localPlayer.tile,
			pathToDwarvenCopperTin,
			reached = { localPlayer.tile.getDistance(pathToDwarvenCopperTin.last()) < 5 })
	}


private val pathToDwarvenCoal = listOf(
	Tile.of(3019, 9848, 0), Tile.of(3021, 9834, 0), Tile.of(3041, 9832, 0), Tile.of(3051, 9824, 0)
)
private val toCoal
	get() = traversal(MiningState(), { MiningSpot.COAL_DWARVEN_MINE.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToDwarvenCoal,
			reached = { localPlayer.tile.getDistance(pathToDwarvenCoal.last()) < 5 })
	}

private val pathToIronMithril = listOf(
	Tile.of(3213, 3375, 0), Tile.of(3183, 3371, 0)
)
private val toIronMithril
	get() = traversal(
		MiningState(), { MiningSpot.IRON_VARROCK_SOUTHWEST.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToIronMithril,
			fallback = { useLodestone(Lodestone.VARROCK) },
			reached = { localPlayer.tile.getDistance(pathToIronMithril.last()) < 5 })
	}

private val pathToVarrockWestBank = listOf(
	Tile.of(3183, 3369, 0),
	Tile.of(3174, 3380, 0),
	Tile.of(3168, 3406, 0),
	Tile.of(3174, 3428, 0),
	Tile.of(3186, 3425, 0)
)
private val toVarrockWestBank
	get() = traversal(
		MiningState(),
		{ MiningSpot.IRON_VARROCK_SOUTHWEST.areaTile.getDistance(localPlayer.tile) < 10 && inventory.freeSlots > 1 }) {
		chebychevPath(
			localPlayer.tile,
			pathToVarrockWestBank,
			reached = { localPlayer.tile.getDistance(pathToVarrockWestBank.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }
		chebychevPath(
			localPlayer.tile,
			pathToVarrockWestBank.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToVarrockWestBank.first()) < 5 })
	}


private val pathToAdamant = listOf<Tile>(
	Tile.of(3214, 3375, 0), Tile.of(3288, 3363, 0)
)
private val toAdamant
	get() = traversal(
		MiningState(), { MiningSpot.ADAMANTITE.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToAdamant,
			fallback = { useLodestone(Lodestone.VARROCK) },
			reached = { localPlayer.tile.getDistance(pathToAdamant.last()) < 5 })
	}

private val pathToVarrockWestBankAdamant = listOf<Tile>(
	Tile.of(3290, 3363, 0), Tile.of(3211, 3378, 0), Tile.of(3211, 3405, 0), Tile.of(3189, 3423, 0)
)
private val toVarrockWestBankAdamant
	get() = traversal(
		MiningState(),
		{ MiningSpot.ADAMANTITE.areaTile.getDistance(localPlayer.tile) < 10 && inventory.freeSlots > 1 }) {
		chebychevPath(
			localPlayer.tile,
			pathToVarrockWestBankAdamant,
			reached = { localPlayer.tile.getDistance(pathToVarrockWestBankAdamant.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }
		chebychevPath(
			localPlayer.tile,
			pathToVarrockWestBankAdamant.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToVarrockWestBankAdamant.first()) < 5 })
	}


private val pathToRunite = listOf<Tile>(
	Tile.of(2528, 3091, 0),
	Tile.of(2574, 3089, 0),
	Tile.of(2585, 3097, 0),
	Tile.of(2607, 3097, 0),
	Tile.of(2621, 3112, 0),
	Tile.of(2628, 3148, 0)
)
private val toRunite
	get() = traversal(
		MiningState(), { MiningSpot.RUNITE.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToRunite,
			fallback = { useLodestone(Lodestone.YANILLE) },
			reached = { localPlayer.tile.getDistance(pathToRunite.last()) < 5 })
	}


private val pathToRuniteBank = listOf<Tile>(
	Tile.of(2628, 3147, 0), Tile.of(2620, 3110, 0), Tile.of(2605, 3096, 0), Tile.of(2614, 3080, 0)
)

private val toRuniteBank
	get() = traversal(
		MiningState(), { MiningSpot.RUNITE.areaTile.withinDistance(localPlayer.tile, 10) && inventory.freeSlots > 1 }) {
		chebychevPath(
			localPlayer.tile, pathToRuniteBank, reached = { localPlayer.tile.getDistance(pathToRuniteBank.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }
		chebychevPath(
			localPlayer.tile,
			pathToRuniteBank.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToRuniteBank.first()) < 5 })
	}


private val pathToLuminite = listOf<Tile>(
	Tile.of(2632, 3347, 0),
	Tile.of(2626, 3339, 0),
	Tile.of(2610, 3338, 0),
	Tile.of(2606, 3310, 0),
	Tile.of(2606, 3297, 0),
	Tile.of(2594, 3296, 0),
	Tile.of(2580, 3263, 0),
	Tile.of(2496, 3262, 0),
	Tile.of(2473, 3253, 0)
)
private val toLuminite
	get() = traversal(
		MiningState(), { MiningSpot.LUMINITE.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToLuminite,
			fallback = { useLodestone(Lodestone.ARDOUGNE) },
			reached = { localPlayer.tile.getDistance(pathToLuminite.last()) < 5 })
	}


private val pathToLuminiteBank = listOf<Tile>(
	Tile.of(2473, 3253, 0),
	Tile.of(2499, 3262, 0),
	Tile.of(2578, 3262, 0),
	Tile.of(2593, 3295, 0),
	Tile.of(2606, 3296, 0),
	Tile.of(2605, 3310, 0)
)
private val toLuminiteBank
	get() = traversal(
		MiningState(),
		{ MiningSpot.LUMINITE.areaTile.withinDistance(localPlayer.tile, 10) && inventory.freeSlots > 1 }) {
		chebychevPath(
			localPlayer.tile,
			pathToLuminiteBank,
			reached = { localPlayer.tile.getDistance(pathToLuminiteBank.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }
		chebychevPath(
			localPlayer.tile,
			pathToLuminiteBank.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToLuminiteBank.first()) < 5 })
	}

private val pathToMiningGuild = listOf<Tile>(
	Tile.of(2966, 3401, 0),
	Tile.of(2966, 3379, 0),
	Tile.of(2992, 3371, 0),
	Tile.of(3023, 3358, 0),
	Tile.of(3030, 3339, 0),
	Tile.of(3021, 3337, 0)
)

private val pathToMiningGuildDown = listOf<Tile>(
	Tile.of(3022, 9738, 0), Tile.of(3042, 9738, 0)
)

private val toMiningGuild
	get() = traversal(
		MiningState(), { MiningSpot.ORIK_MINING_GUILD.areaTile.withinDistance(localPlayer.tile, 10) }) {
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuild,
			fallback = { useLodestone(Lodestone.FALADOR) },
			reached = { localPlayer.tile.getDistance(pathToMiningGuild.last()) < 5 })
		interactObj("Climb-down") {
			Tile.of(3019, 9739, 0).getDistance(localPlayer.tile) < 5
		}
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuildDown,
			reached = { localPlayer.tile.getDistance(pathToMiningGuildDown.last()) < 5 })

	}


private val pathToMiningGuildBank = listOf<Tile>(
	Tile.of(3042, 9738, 0), Tile.of(3022, 9738, 0)
)


private val pathToMiningGuildBankUp = listOf<Tile>(
	Tile.of(3022, 3337, 0), Tile.of(3041, 3340, 0)
)

private val miningGuildBank
	get() = traversal(
		MiningState(),
		{ MiningSpot.ORIK_MINING_GUILD.areaTile.withinDistance(localPlayer.tile, 10) && inventory.freeSlots > 1 }) {					
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuildBank,
			reached = { localPlayer.tile.getDistance(pathToMiningGuildBank.last()) < 5 })
		interactObj("Climb-up") {
			Tile.of(3019, 3339, 0).getDistance(localPlayer.tile) < 5
		}
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuildBankUp,
			reached = { localPlayer.tile.getDistance(pathToMiningGuildBankUp.last()) < 5 })
		interactObj("Deposit-all (into metal bank)") { inventory.freeSlots > 1 }
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuildBankUp.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToMiningGuildBankUp.first()) < 5 })
		interactObj("Climb-down") {
			Tile.of(3019, 9739, 0).getDistance(localPlayer.tile) < 5
		}
		chebychevPath(
			localPlayer.tile,
			pathToMiningGuildBank.asReversed(),
			reached = { localPlayer.tile.getDistance(pathToMiningGuildBank.first()) < 5 })			
	}
