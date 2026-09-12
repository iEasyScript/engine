package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.BooleanConfigItem
import com.projectx.script.StateMachineScript
import com.projectx.script.State
import com.projectx.script.ConfigurableScript
import com.projectx.script.event.Event
import com.projectx.script.api.*
import com.projectx.script.ScriptDescription
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random
import com.projectx.script.ConfigItem

@ScriptDescription(
	name = "Bowls of water",
	version = "1.0.0",
	author = "QB",
	description = "Fully automated bowls of water using state machine pattern",
)
class BowlsOfWaterStateMachine : StateMachineScript<BowlsOfWaterStateMachine>(), ConfigurableScript {
	val worldHop = BooleanConfigItem("World hop", "Enable world hop", false)

	override fun getStartState(): State<BowlsOfWaterStateMachine> = MiningClayState()

	val produtionArea = Area.Rectangular(
		Tile.of(3070, 3393, 0), Tile.of(3089, 3430, 0)
	)

	val bankArea = Area.Rectangular(
		Tile.of(3091, 3488, 0), Tile.of(3098, 3499, 0)
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
}

val pathToProductionArea = listOf<Tile>(
	Tile.of(3066, 3503, 0),
	Tile.of(3089, 3493, 0),
	Tile.of(3074, 3478, 0),
	Tile.of(3073, 3453, 0),
	Tile.of(3071, 3437, 0),
	Tile.of(3070, 3417, 0),
	Tile.of(3082, 3417, 0)
)

private val toProductionArea
	get() = traversal(MiningClayState(), { produtionArea.contains(localPlayer.tile) }) {
		chebychevPath(
			localPlayer.tile,
			pathToProductionArea,
			fallback = { useLodestone(Lodestone.EDGEVILLE) },
			reached = { localPlayer.tile.getDistance(pathToProductionArea.last()) < 5 })
	}

val pathToBank = listOf<Tile>(
	Tile.of(3078, 3417, 0), Tile.of(3070, 3428, 0), Tile.of(3074, 3479, 0), Tile.of(3094, 3492, 0)
)

private val toBank
	get() = traversal(BankingClayState(), { localPlayer.tile.getDistance(pathToBank.last()) < 8 }) {
		chebychevPath(
			localPlayer.tile, pathToBank, reached = { localPlayer.tile.getDistance(pathToBank.last()) < 5 })
	}

class MiningClayState : State<BowlsOfWaterStateMachine>() {
	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (!produtionArea.contains(localPlayer.tile)) {
			return toProductionArea
		}

		if (inventory.getItem("Clay") != null && inventory.isFull) {
			return WettingClayState()
		}
		if (inventory.getItem("Soft clay") != null && inventory.isFull) {
			return MouldingClayState()
		}
		if (inventory.getItem(Regex(".*unfired.*", RegexOption.IGNORE_CASE)) != null && inventory.isFull) {
			return FireClayState()
		}
		if (inventory.isFull) {
			if (inventory.getItem("Empty pot") != null) {
				while (inventory.getItem("Empty pot") != null) {
					inventory.getItem("Empty pot")?.click("Drop")
					delay(1000)
				}
			} else {
				if (inventory.hasItem("Bowl")) {
					return FillBowlsState()
				}

				return BankingClayState()
			}
		}

		return null
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (worldHop.value && checkWorldPop()) return
		var currentRock =
			allObjects.filter { it.name() == "Clay rock" && it.hasOption("Mine") && produtionArea.contains(it.tile) }
				.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		val rockertunity = spotAnims.find { rockertunitySpotAnims.contains(it.id) }
		if (rockertunity != null) {
			currentRock = findClosestObjectToTile(rockertunity.tile) {
				it.name() == "Clay rock" && it.hasOption("Mine")
			}
		}
		if (currentRock?.interact("Mine") == true) {
			waitForXPDrop(Skill.MINING)
			delayUntil(30529, 10592) {
				inventory.isFull || !localPlayer.isAnimating || (spotAnims.find { rockertunitySpotAnims.contains(it.id) } != null) || (localPlayer.headbars.first { it.type == 5 }.toFill * 100 / 255) < random(
					25,
					50
				)
			}
		}
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}

class FillBowlsState : State<BowlsOfWaterStateMachine>() {
	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (!inventory.hasItem("Bowl")) {
			return MiningClayState()
		}
		return null
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (hasActiveMakeXProgress) return

		if (makeXOpen) {
			continueMakeX()
			delay(1000, 200)
			return
		}

		var well = allObjects.filter { it.name() == "Well" && it.hasOption("Fill") && produtionArea.contains(it.tile) }
			.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (well?.interact("Fill") == true) {
			delayUntil(10000) { makeXOpen }
		}
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}

class WettingClayState : State<BowlsOfWaterStateMachine>() {
	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (inventory.getItem("Soft clay") != null && inventory.getItem("Clay") == null) {
			return MouldingClayState()
		}
		return null
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (hasActiveMakeXProgress) return

		if (makeXOpen) {
			continueMakeX()
			delay(1000, 200)
			return
		}

		var well = allObjects.filter { it.name() == "Well" && it.hasOption("Fill") && produtionArea.contains(it.tile) }
			.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (well?.interact("Fill") == true) {
			delayUntil(10000) { makeXOpen }
		}
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}

class MouldingClayState : State<BowlsOfWaterStateMachine>() {
	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (inventory.getItem(
				Regex(
					".*unfired.*", RegexOption.IGNORE_CASE
				)
			) != null && inventory.getItem("Soft clay") == null
		) {
			return FireClayState()
		}
		return null
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (hasActiveMakeXProgress) return

		if (makeXOpen) {
			if (getRealLevel(Skill.CRAFTING) >= 8) {
				IFSlot(1371, 22, 9).click()
				delay(1000, 222)
			} else {
				IFSlot(1371, 22, 1).click()
				delay(1000, 222)
			}

			continueMakeX()
			delay(1000, 200)
			return
		}

		var potters =
			allObjects.filter { it.name() == "Potter's Wheel" && it.hasOption("Form") && produtionArea.contains(it.tile) }
				.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (potters?.interact("Form") == true) {
			delayUntil(10000) { makeXOpen }
		}
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}

class FireClayState : State<BowlsOfWaterStateMachine>() {
	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (inventory.getItem(Regex(".*unfired.*", RegexOption.IGNORE_CASE)) == null && inventory.isFull) {
			return MiningClayState()
		}
		return null
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (hasActiveMakeXProgress) return

		if (makeXOpen) {
			continueMakeX()
			delay(1000, 200)
			return
		}

		var potters =
			allObjects.filter { it.name() == "Pottery Oven" && it.hasOption("Fire") && produtionArea.contains(it.tile) }
				.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (potters?.interact("Fire") == true) {
			delayUntil(10000) { makeXOpen }
		}
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}

class BankingClayState : State<BowlsOfWaterStateMachine>() {
	var hasBanked = false

	override suspend fun BowlsOfWaterStateMachine.checkNext(): State<BowlsOfWaterStateMachine>? {
		if (hasBanked) {
			return MiningClayState()
		}

		if (!bankArea.contains(localPlayer.tile)) {
			return toBank
		}

		return null
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
			currentLevel < 10 && toolbeltPickaxeVarbit < 2 -> true
			else -> false
		}
	}

	override suspend fun BowlsOfWaterStateMachine.stateLoop() {
		if (!bankOpen) {
			interactClosestObject("Bank", 100)
			delayUntil(8000) { bankOpen }
			return
		}

		val nextPickaxe = Regex(".*${getNextPickaxe()}.*", RegexOption.IGNORE_CASE)
		if (hasToUpgradePickaxe()) {
			if (inventory.hasItem(nextPickaxe)) {
				inventory.getItem(nextPickaxe)?.click("Add to tool belt")
				delay(random(1000, 1500))
				depositBankItem(Regex(".*pickaxe.*", RegexOption.IGNORE_CASE))
				return
			} else {
				if (bank.getItem(nextPickaxe) != null) {
					withdrawBankItem(nextPickaxe)
					return
				}
			}
		}

		depositAllInventory()
		delay(random(1000, 1500))
		hasBanked = true
	}

	override fun BowlsOfWaterStateMachine.onStateEvent(event: Event) {
	}
}
    