package com.projectx.script.impl.qb.skilling

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.StateMachineScript
import com.projectx.script.State
import com.projectx.script.event.Event
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.api.*
import com.projectx.script.ScriptDescription
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.script.BooleanConfigItem

@ScriptDescription(
	name = "Whirligigs State Machine",
	version = "1.0.0",
	author = "QB",
	description = "Fully automated whirligigs using state machine pattern",
)
class WhirligigsStateMachine : StateMachineScript<WhirligigsStateMachine>(), ConfigurableScript {

	override fun getStartState(): State<WhirligigsStateMachine> {
		return HuntingWhirligigs()
	}

	val selectedWhirligig = EnumConfigItem(
		name = "Whirligig Type",
		description = "Select which whirligig type to fish",
		enumValues = WHIRLIGIGS.entries.toTypedArray(),
		initialValue = WHIRLIGIGS.PLAIN
	)

	val enableWorldHop = BooleanConfigItem(
		name = "Enable World Hop", description = "Enable world hopping", initialValue = false
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

	val closeBasketFlowerCount = 50787
	val dundeeBasketFlowerCount = 50786
	val closeBasketObjectId = 122490
	val dundeeBasketObjectId = 122489
	val closeBasketStateVarbit = 50791
	val dundeeBasketStateVarbit = 50790

	val handlingCrocodile = 50810
	val activeCrocodile = 50811
	val scarabStackCount = 50812


	val catchCount = 50819
	val increasedCatchRate = 50813
	val doubleLootChance = 50816
	val flowerSavingChance = 50815
	val increasedXPRate = 50814
	val autoLoot = 51014
	val stackSizeUnlock = 50818


	val area = Area.Rectangular(Tile.of(3331, 3259, 0), Tile.of(3359, 3243, 0))

	var currentTarget: NPC? = null

	enum class WHIRLIGIGS(val level: Int, val displayName: String, val flower: String, val componentIndex: Int) {
		PLAIN(1, "Plain whirligig", "NONE", -1), DAZZLING(1, "Dazzling whirligig", "Golden roses", 28), GLIDING(
			30,
			"Gliding whirligig",
			"Roses",
			8
		),
		SWIFT(50, "Swift whirligig", "Irises", 13), HASTY(70, "Hasty whirligig", "Hydrangeas", 18), SPEEDY(
			90,
			"Speedy whirligig",
			"Hollyhocks",
			23
		);

	}

}

class HuntingWhirligigs : State<WhirligigsStateMachine>() {

	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		if (!area.contains(localPlayer.tile)) {
			println("HuntingWhirligigs.checkNext: -> walkToWhirligigs (out of area)")
			return walkToWhirligigs
		}
		if ((varps.getVarBit(closeBasketStateVarbit) == 0 || varps.getVarBit(dundeeBasketStateVarbit) == 0) && (inventory.count(
				"Teak plank"
			) >= 5 && (inventory.getItem("Steel nails")?.amount ?: 0) >= 10)
		) {
			println("HuntingWhirligigs.checkNext: -> BuildBasket (basket missing and have supplies)")
			return BuildBasket()
		}

		if (varps.getVarBit(activeCrocodile) == 1) {
			println("HuntingWhirligigs.checkNext: -> StackWhirligigs")
			return StackWhirligigs()
		}

		if (varps.getVarBit(closeBasketFlowerCount) < 20 && varps.getVarBit(dundeeBasketFlowerCount) < 20 && inventory.count(
				selectedWhirligig.value.flower
			) > 0 && varps.getVarBit(autoLoot) == 1 && selectedWhirligig.value != WhirligigsStateMachine.WHIRLIGIGS.PLAIN
		) {

			return RestockBasket()
		}

		var caughtCount = varps.getVarBit(50819)
		if (varps.getVarBit(increasedCatchRate) == 0 && caughtCount >= 250 || varps.getVarBit(doubleLootChance) == 0 && caughtCount >= 500 || varps.getVarBit(
				flowerSavingChance
			) == 0 && caughtCount >= 1000 || varps.getVarBit(increasedXPRate) == 0 && caughtCount >= 1500 || varps.getVarBit(
				autoLoot
			) == 0 && caughtCount >= 2500 || varps.getVarBit(stackSizeUnlock) == 0 && caughtCount >= 3000
		) {
			println("HuntingWhirligigs.checkNext: -> UnlockHandler (caught=${'$'}caughtCount)")
			return UnlockHandler()
		}

		if (varps.getVarBit(handlingCrocodile) == 0) {
			println("HuntingWhirligigs.checkNext: -> HandleCrocodile")
			return HandleCrocodile()
		}

		println("HuntingWhirligigs.checkNext: stay (continue hunting)")
		return null
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		if (enableWorldHop.value && checkWorldPop()) return

		println("HuntingWhirligigs.stateLoop: selected=${selectedWhirligig.value.displayName}")
		currentTarget = findClosestNPC(30) { it.name.contains(selectedWhirligig.value.displayName) }
		println("HuntingWhirligigs.stateLoop: target=${currentTarget?.name ?: "null"} id=${currentTarget?.id ?: -1}")
		currentTarget?.interact("Catch")
		delayUntil(15000) { varps.getVarBit(activeCrocodile) == 1 }
	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {

	}
}

val pathToWhirligigs = listOf(
	Tile.of(3296, 3183, 0),
	Tile.of(3291, 3195, 0),
	Tile.of(3301, 3200, 0),
	Tile.of(3301, 3212, 0),
	Tile.of(3305, 3213, 0),
	Tile.of(3308, 3224, 0),
	Tile.of(3326, 3243, 0),
	Tile.of(3334, 3253, 0)
)

val walkToWhirligigs = traversal(HuntingWhirligigs(), { localPlayer.tile.getDistance(Tile.of(3091, 3488, 0)) < 8 }) {
	chebychevPath(
		localPlayer.tile,
		pathToWhirligigs,
		fallback = { useLodestone(Lodestone.AL_KHARID) },
		reached = { localPlayer.tile.getDistance(Tile.of(3091, 3488, 0)) < 8 })
}

class BuildBasket : State<WhirligigsStateMachine>() {
	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		if (inventory.count("Teak plank") < 5 && (inventory.getItem("Steel nails")?.amount ?: 0) < 10) {
			return HuntingWhirligigs()
		}

		if (varps.getVarBit(closeBasketStateVarbit) > 0 && varps.getVarBit(dundeeBasketStateVarbit) > 0) {
			return HuntingWhirligigs()
		}

		return null
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		println("BuildBasket.stateLoop: start")
		if (interfaces.isOpen(1188)) {
			println("BuildBasket.stateLoop: continuing dialogue 'Yes'")
			continueDialogueContaining("Yes")
			waitThenDelayUntil(1800, 10000) { !localPlayer.isAniMoving }
			return
		}

		if (varps.getVarBit(closeBasketStateVarbit) == 0) {
			println("BuildBasket.stateLoop: building Close basket (objectId=$closeBasketObjectId)")
			interactClosestObject(closeBasketObjectId, "Build")
			delayUntil(10000) { interfaces.isOpen(1188) }
			return
		}

		if (varps.getVarBit(dundeeBasketStateVarbit) == 0) {
			println("BuildBasket.stateLoop: building Dundee basket (objectId=$dundeeBasketObjectId)")
			interactClosestObject(dundeeBasketObjectId, "Build")
			delayUntil(10000) { interfaces.isOpen(1188) }

			return
		}

	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {
	}
}

class StackWhirligigs : State<WhirligigsStateMachine>() {
	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		val scarabMaxStackCount = if (varps.getVarBit(stackSizeUnlock) == 1) 5 else 3

		if (varps.getVarBit(activeCrocodile) == 0) {
			return HuntingWhirligigs()
		}

		return null
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		if (enableWorldHop.value && checkWorldPop()) return

		val scarabMaxStackCount = if (varps.getVarBit(stackSizeUnlock) == 1) 5 else 3

		var currentCount = varps.getVarBit(scarabStackCount)

		if (currentCount < scarabMaxStackCount) {
			var stackTarget = findClosestNPC(30) { it.name.contains("whirligig") && it.id != currentTarget?.id }
			stackTarget?.interact("Catch")
			delayUntil(1800) { varps.getVarBit(scarabStackCount) != currentCount }
		}
	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {

	}
}

class UnlockHandler : State<WhirligigsStateMachine>() {
	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		var caughtCount = varps.getVarBit(50819)
		if (varps.getVarBit(increasedCatchRate) == 0 && caughtCount >= 250 || varps.getVarBit(doubleLootChance) == 0 && caughtCount >= 500 || varps.getVarBit(
				flowerSavingChance
			) == 0 && caughtCount >= 1000 || varps.getVarBit(increasedXPRate) == 0 && caughtCount >= 1500 || varps.getVarBit(
				autoLoot
			) == 0 && caughtCount >= 2500 || varps.getVarBit(stackSizeUnlock) == 0 && caughtCount >= 3000
		) {
			return null
		}

		return HuntingWhirligigs()
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		var caughtCount = varps.getVarBit(50819)
		println("UnlockHandler.stateLoop: waiting or handling unlocks")
		val interfaceID = 1594
		if (!interfaces.isOpen(interfaceID)) {
			interactClosestNPC("Upgrades")

			delayUntil(15000) { interfaces.isOpen(interfaceID) }

			return
		}

		when {
			varps.getVarBit(increasedCatchRate) == 0 && caughtCount >= 250 -> IFSlot(interfaceID, 23, 0).click()

			varps.getVarBit(doubleLootChance) == 0 && caughtCount >= 500 -> IFSlot(interfaceID, 23, 1).click()

			varps.getVarBit(flowerSavingChance) == 0 && caughtCount >= 1000 -> IFSlot(interfaceID, 23, 2).click()

			varps.getVarBit(increasedXPRate) == 0 && caughtCount >= 1500 -> IFSlot(interfaceID, 23, 3).click()

			varps.getVarBit(autoLoot) == 0 && caughtCount >= 2500 -> IFSlot(interfaceID, 23, 4).click()

			varps.getVarBit(stackSizeUnlock) == 0 && caughtCount >= 3000 -> IFSlot(interfaceID, 23, 5).click()
		}

		interfaces.getComponent(interfaceID, 58)?.let { IFSlot(interfaceID, 58, -1).click() }
		interfaces.getComponent(interfaceID, 64)?.let { IFSlot(interfaceID, 64, -1).click() }
	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {

	}
}

class HandleCrocodile : State<WhirligigsStateMachine>() {
	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		println("HandleCrocodile.checkNext: handling=${varps.getVarBit(handlingCrocodile)}")
		if (varps.getVarBit(handlingCrocodile) == 1) {
			return HuntingWhirligigs()
		}
		return null
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		println("HandleCrocodile.stateLoop: interacting with NPC 28659 (Handle)")
		interactClosestNPC(28659, "Handle")
		delayUntil(10000) { varps.getVarBit(handlingCrocodile) == 1 }
	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {

	}
}

class RestockBasket : State<WhirligigsStateMachine>() {
	override suspend fun WhirligigsStateMachine.checkNext(): State<WhirligigsStateMachine>? {
		if (inventory.count(selectedWhirligig.value.flower) == 0 || varps.getVarBit(closeBasketFlowerCount) > 20 || 
		varps.getVarBit(dundeeBasketFlowerCount) > 20) {
			return HuntingWhirligigs()
		}

		return null
	}

	override suspend fun WhirligigsStateMachine.stateLoop() {
		if (varps.getVarBit(closeBasketFlowerCount) < 20) {
			interactClosestObject(closeBasketObjectId, "Fill all")
			delayUntil(15000) { varps.getVarBit(closeBasketFlowerCount) >= 21 }

		}
		if (varps.getVarBit(dundeeBasketFlowerCount) < 20) {
			interactClosestObject(dundeeBasketObjectId, "Fill all")
			delayUntil(15000) { varps.getVarBit(dundeeBasketFlowerCount) >= 21 }

		}

	}

	override fun WhirligigsStateMachine.onStateEvent(event: Event) {
	}
}