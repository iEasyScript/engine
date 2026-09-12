package com.projectx.script.impl.qb.skilling

import com.projectx.script.StateMachineScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.api.*
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.api.Area
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.traversal.nodes.LodestoneNode
import com.projectx.script.api.useLodestone
import com.projectx.script.api.Lodestone
import org.projectx.core.game.skill.Skill
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigurableScript

@ScriptDescription(
	name = "Firemaking", version = "1.0.0", author = "QB", description = "Firemaking"
)
class Firemaking : StateMachineScript<Firemaking>(), ConfigurableScript {
	override fun getStartState(): State<Firemaking> = FiremakingState()

	var selectedLogType = LogType.NORMAL_LOGS

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

enum class LogType(val displayName: String, val level: Int) {
	NORMAL_LOGS("Logs", 1),
	ACHEY_LOGS("Achey tree logs", 1),
	OAK_LOGS("Oak logs", 15),
	WILLOW_LOGS("Willow logs", 30),
	TEAK_LOGS("Teak logs", 35),
	ARCTIC_PINE_LOGS("Arctic pine logs", 42),
	MAPLE_LOGS("Maple logs", 45),
	MAHOGANY_LOGS("Mahogany logs", 50),
	EUCALYPTUS_LOGS("Eucalyptus logs", 58),
	YEW_LOGS("Yew logs", 60),
	MAGIC_LOGS("Magic logs", 75),
	ELDER_LOGS("Elder logs", 90);
}

val startArea = Area.Rectangular(Tile.of(5037, 9633, 0), Tile.of(5014, 9649, 0));

private class FiremakingState : State<Firemaking>() {
	override suspend fun Firemaking.checkNext(): State<Firemaking>? {
		if (!startArea.contains(localPlayer.tile)) {
			println("[Firemaking] Player outside start area, moving to start.")
			return moveToStart
		}

		if (!inventory.hasItem(Regex(".*logs.*", RegexOption.IGNORE_CASE))) {
			println("[Firemaking] No logs in inventory, heading to bank.")
			return FiremakingBanking()
		}
		return null
	}

	override suspend fun Firemaking.stateLoop() {
		if (localPlayer.isAnimating || timeSinceLastXpDrop < 30000) {
			println("[Firemaking] Waiting for current action/xp drop to finish.")
			waitThenDelayUntil(1000, 3000) { !localPlayer.isAnimating && timeSinceLastXpDrop >= 30000 }
			return
		}

		var bonfire = allObjects.firstOrNull { it.name() == "Bonfire" }
		if (bonfire != null) {
			println("[Firemaking] Adding logs to bonfire.")
			bonfire.interact("Add logs to")
		} else {
			println("[Firemaking] No bonfire found nearby.")
		}
		waitThenDelayUntil(1000, 1000) { localPlayer.isAnimating }
	}
}

private class FiremakingBanking : State<Firemaking>() {
	override suspend fun Firemaking.checkNext(): State<Firemaking>? {
		println("[FiremakingBanking] Checking for logs in inventory.")
		return if (inventory.hasItem(Regex(".*logs.*", RegexOption.IGNORE_CASE))) FiremakingState() else null
	}

	override suspend fun Firemaking.stateLoop() {
		if (!bankOpen) {
			println("[FiremakingBanking] Opening closest bank.")
			openClosestBank(false, 60)
			delay(1000, 300)
			return
		}

		if (!bank.hasItem(Regex(".*logs.*", RegexOption.IGNORE_CASE))) {
			if (!inventory.hasItem(Regex(".*logs.*", RegexOption.IGNORE_CASE))) {
				println("[FiremakingBanking] Bank and inventory have no logs.")
			}
			return
		}
		depositAllInventory()
		var log = LogType.entries.filter { it.level <= getRealLevel(Skill.FIREMAKING) && bank.hasItem(it.displayName) }
			.firstOrNull()
		if (log != null) {
			println("[FiremakingBanking] Withdrawing ${log.displayName}.")
			withdrawBankItem(log.displayName)
			delay(1000, 300)
			closeBank()
		}
	}
}

var pathToPortal = listOf(
	Tile.of(2878, 3442, 0), Tile.of(2852, 3457, 0)
)

private val moveToStart = traversal(FiremakingState(), { startArea.contains(localPlayer.tile) }) {
	chebychevPath(
		localPlayer.tile,
		pathToPortal,
		fallback = { useLodestone(Lodestone.TAVERLEY) },
		reached = { pathToPortal.last().getDistance(localPlayer.tile) < 5 })
	interactObj(43808, "Enter") { interfaces.isOpen(1184) }
	clickIFSlot(IFSlot(1184, 15, -1), 0) { interfaces.isOpen(1188) }
	clickIFSlot(IFSlot(1188, 13, -1), 0) { Tile.of(5024, 9662, 0).getDistance(localPlayer.tile) < 5 }
	chebychevPath(
		localPlayer.tile, listOf(
			localPlayer.tile, Tile.of(5028, 9643, 0)
		), reached = { startArea.contains(localPlayer.tile) })
}
    