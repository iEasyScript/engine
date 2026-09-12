package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.continueMakeX
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.makeXOpen
import com.projectx.script.api.timeSinceLastXpDrop
import com.projectx.util.gaussian
import com.projectx.script.ScriptDescription
import com.projectx.script.api.Lodestone
import com.projectx.script.api.checkWorldPop
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.script.ConfigItem

@ScriptDescription(
	name = "CookingStateMachine", version = "1.0.0", author = "QB", description = "Cooks using last preset"
)
class CookingStateMachine : StateMachineScript<CookingStateMachine>(), ConfigurableScript {
	val worldHop = BooleanConfigItem(
		name = "World Hop", description = "Enable world hop", initialValue = false
	)

	override fun getStartState(): State<CookingStateMachine> = Cook()

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

private val raw = Regex("Raw\\s+.*")
private val sweetcorn = "Sweetcorn"

class Cook : State<CookingStateMachine>() {
	override suspend fun CookingStateMachine.checkNext(): State<CookingStateMachine>? {
		println("CookingCheckNext")

		if (findClosestObject( 40) {it.hasOption("Load Last Preset from")} == null) {
			println("Walking")
			return walkToCatherbyCooking
		}

		if (!inventory.hasItem(raw) && !inventory.hasItem(sweetcorn)) {
			println("Banking")
			return BankCooking
		}

		return null
	}

	override suspend fun CookingStateMachine.stateLoop() {
		println("CookingStateLoop")

		if (worldHop.value && checkWorldPop()) return

		if (interfaces.isOpen(1251)) {
			println("Still cooking")
			delayUntil(5000) { !interfaces.isOpen(1251) }
			delay(1500, 1000)
			return
		}

		if (timeSinceLastXpDrop > gaussian(10000, 2059)) {
			if (!makeXOpen) {
				if (interactClosestObject("Cook-at", 40)) delayUntil(10000) { makeXOpen }
				return
			}

             continueMakeX()
			waitForXPDrop(Skill.COOKING)
		}
	}
}

val walkToCatherbyCooking = traversal(BankCooking, { localPlayer.tile.getDistance(Tile.of(2797, 3444, 0)) < 8 }) {
	chebychevPath(
		localPlayer.tile,
		listOf(
			Tile.of(2810, 3449, 0), Tile.of(2797, 3444, 0)
		),
		fallback = { useLodestone(Lodestone.CATHERBY) },
		reached = { localPlayer.tile.getDistance(Tile.of(2797, 3444, 0)) < 8 })
}

object BankCooking : State<CookingStateMachine>() {
	override suspend fun CookingStateMachine.checkNext(): State<CookingStateMachine>? {
		println("BankingCheckNext")
		println(inventory.hasItem(raw))

		if (inventory.hasItem(raw) || inventory.hasItem(sweetcorn)) return Cook()

		return null
	}

	override suspend fun CookingStateMachine.stateLoop() {
		println("BankingStateLoop")
		if (!inventory.hasItem(raw) && !inventory.hasItem(sweetcorn) && interactClosestObject("Load Last Preset from")) delayUntil(
			6000
		) { inventory.hasItem(raw) || inventory.hasItem(sweetcorn) }
	}
}
