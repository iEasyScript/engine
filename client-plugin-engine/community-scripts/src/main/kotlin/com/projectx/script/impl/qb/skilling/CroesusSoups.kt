package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.Lodestone
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

@ScriptDescription(
	name = "CroesusSoups",
	version = "1.0.0",
	author = "QB",
	description = "Cooks using presets"
)
class CroesusSoups : StateMachineScript<CroesusSoups>() {
	override fun getStartState(): State<CroesusSoups> = CombineComponents()

	override fun onEvent(event: Event) {
		when (event) {
			is Chat -> {
				println(event.message)
				if (event.message.startsWith("item could not be found:", true)) {
				}				
			}
		}
	}
}

private val enrichedComponent = Regex("Enriched\\s+.*")
private val waterBowl = "Bowl of water"

class CombineComponents : State<CroesusSoups>() {
	override suspend fun CroesusSoups.checkNext() =
		if (interactClosestObject("Load Last Preset from") == false) walkToCatherbySoups else
			if (!inventory.hasItem(enrichedComponent) || !inventory.hasItem(waterBowl)) BankingSoups else null

	override suspend fun CroesusSoups.stateLoop() {
		if (interfaces.isOpen(1251)) {
			println("Still cooking")
			delay(1500, 1000)
			return
		}

		var waterBowlItem = inventory.getItem(waterBowl)
		if (waterBowlItem == null) {
			println("No Water Bowl")
			return
		}

		inventory.getItem(enrichedComponent)?.useOn(waterBowlItem)
		delayUntil(5000) { interfaces.isOpen(1251) }
		waitForXPDrop(Skill.COOKING)
		delayUntil(5000) { !interfaces.isOpen(1251) }
	}
}

val walkToCatherbySoups = traversal(BankingSoups, { localPlayer.tile.getDistance(Tile.of(3091, 3488, 0)) < 8 }) {
	chebychevPath(
		localPlayer.tile,
		listOf(
			Tile.of(2810, 3449, 0),
			Tile.of(2797, 3444, 0)
		),
		fallback = { useLodestone(Lodestone.CATHERBY) },
		reached = { localPlayer.tile.getDistance(Tile.of(3091, 3488, 0)) < 8 }
	)
}

object BankingSoups : State<CroesusSoups>() {
	override suspend fun CroesusSoups.checkNext() =
		if (inventory.hasItem(enrichedComponent) && inventory.hasItem(waterBowl)) CombineComponents() else null

	override suspend fun CroesusSoups.stateLoop() {
		if (interactClosestObject("Load Last Preset from"))
			delayUntil(6000) { inventory.hasItem(enrichedComponent) && inventory.hasItem(waterBowl) }
	}
}