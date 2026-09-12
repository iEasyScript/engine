package com.projectx.script.impl.qb.bosses.gaytescript
import com.projectx.game.tileOfLocal

import world.gregs.voidps.map.ObjectShape
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import org.projectx.core.game.combat.Ability
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.InstanceSystem
import org.projectx.core.game.combat.Effect
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import world.gregs.voidps.collision.CollisionStrategies
import com.projectx.pathfinder.routedDestination
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.XPDrop
import com.projectx.script.impl.qb.bosses.gaytescript.ProcessFight.currentTarget
import com.projectx.util.random
import com.projectx.script.ConfigurableScript
import com.projectx.script.ConfigItem

private val DUNK_VAR_TO_PILLAR_OBJECT = mapOf(
	0 to 130994, 1 to 130996, 2 to 130998, 3 to 131000
)
private val GATE_NPC_ID = 17645
private val MOONSTONE_FRAGMENTS = 57516
private val YIKES_PROJECTILE = 8361
private val AOE_3x3_SPOTANIM = 8279
private val AOE_7x7_SPOTANIM = 8285

@ScriptDescription(
	name = "GayteScript", version = "1.0.0", author = "Query", description = "Gets rid of the Gaytes"
)
class GayteScript : StateMachineScript<GayteScript>(), ConfigurableScript {
	val farmCraftingXp = BooleanConfigItem(
		name = "Farm Crafting XP",
		description = "Farms moonstone fragments for crafting XP instead of killing boss.",
		initialValue = false
	)

	val openChest = BooleanConfigItem(
		name = "OpenChest",
		description = "",
		initialValue = false
	)

	val targetFragments get() = if (farmCraftingXp.value) 50000 else 45

	private var _cachedBoss: NPC? = null
	private val bossNpc: NPC?
		get() =
			_cachedBoss?.takeIf { it.exists() } ?: npcs.values.firstOrNull { it.id == 17645 }
				.also { _cachedBoss = it }

	val chargingLaser
		get() = bossNpc?.headbars?.any {
			it.type == 45 && it.durationMillis == 24000L && it.timeLeftMillis >= -1200L
		} == true

	val pillarsUp
		get() = bossNpc?.headbars?.any {
			it.type == 45 && it.durationMillis == 24000L && it.timeLeftMillis >= -1200L && it.timeLeftMillis < ((24000L - 7200L) + pillarTimeOffset)
		} == true

	val pillarTimeOffset: Long
		get() {
			val dist = tileOfLocal(31, 28, 2).getDistance(localPlayer.tile)
			return 7200L + when {
				dist <= 5 -> 0L
				dist <= 8 -> 2000L
				dist <= 12 -> 4000L
				else -> 10000L
			}
		}

	var instanceStartTime = System.currentTimeMillis()
	val instanceTimeRemaining get() = (55L * 60L * 1000L) - (System.currentTimeMillis() - instanceStartTime)
	var gargoyleCast = false
	override fun getStartState() = StartInstance

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

	override fun onEvent(event: Event) {
		if (event is Chat) {
			GayteScriptDiscordHelper.handleChatEvent(event)

			if (event.message.contains("You have already cast this")) gargoyleCast = true
		}
		if (event is XPDrop) {
			if (event.skill == Skill.MINING) {
				gargoyleCast = false
				if (familiarSummoned && !gargoyleCast) {
					println(familiarName.lowercase())
					if (familiarName.lowercase().contains("gargoyle")) {
						if (familiarScrolls == 0 && (inventory.getItem(49410)?.amount ?: 0) > 0) familiarStoreScrolls()
						println("Gargoyle summoned")
						if (familiarSpecialPoints > 10 && familiarScrolls > 0) if (familiarCastSpecial()) {
							println("Gargoyle cast special")
							gargoyleCast = true
						}
					}
				}
			}
		}
	}
}

object StartInstance : State<GayteScript>() {
	override suspend fun GayteScript.checkNext() = if (inInstancedArea) {
		instanceStartTime = System.currentTimeMillis()
		StartFight()
	} else null

	override suspend fun GayteScript.stateLoop() {
		if (!InstanceSystem.isOpen() && interactClosestObject(
				130974, "Enter"
			)
		) return delayUntil(5000) { InstanceSystem.isOpen() }
		else {
			InstanceSystem.startInstance()
			delay(1200, 3000)
		}
	}

}

class StartFight() : State<GayteScript>() {
	override suspend fun GayteScript.checkNext() = if (fightActive) ProcessFight
	else if (!inInstancedArea) StartInstance
	else null

	override suspend fun GayteScript.stateLoop() {
		if (instanceTimeRemaining <= 0) {
			if (continueDialogueContaining("Yes.")) {
				delayUntil(5000) { !inInstancedArea }
				return
			}
			if (interactClosestNPC("Icthlarin", "Leave", 60)) delayUntil(5000) { dialogueOptionVisible("Yes.") }
			return
		}
		if (localPlayer.tile != tileOfLocal(39, 8, 2)) {
			walkTo(tileOfLocal(39, 8, 2), false)
			waitThenDelayUntil(1200, 2500) { !localPlayer.isAniMoving }
			return
		}

		if (healthCurrent < healthMax && adrenaline >= 20 && !inCombat) {
			IFSlot(1430, 11, -1).click(1)
			delay(1200, 400)
		}

		if ((Effect.FAMILIAR_SUMMONED.notActive || Effect.FAMILIAR_SUMMONED.timeRemaining < 300000) && (inventory.getItem(
				"Bunyip pouch"
			) != null || inventory.getItem(49408) != null)
		) {

			if (summoningPoints < 100 && inventory.getItem(Regex(".*restore.*", RegexOption.IGNORE_CASE)) != null) {
				inventory.getItem(Regex(".*restore.*", RegexOption.IGNORE_CASE))!!.click("Drink")
				delay(1000, 400)
			}
			val bunyip = inventory.getItem("Bunyip pouch")
			if (bunyip != null) {
				bunyip.click("Summon")
			} else {
				val garg = inventory.getItem(49408)
				if (garg != null) garg.click("Summon")
			}

			delayUntil(5000) { Effect.FAMILIAR_SUMMONED.active }
		}

		if ((healthCurrent < healthMax || adrenaline < 25) && hasRelic(Relics.PERSISTENT_RAGE)) return

		if (fightOnCooldown) return delay(500, 400)
		if (interactClosestNPC("Icthlarin", "Start")) waitThenDelayUntil(1000, 3200) { fightActive }
	}

	override fun GayteScript.onStateEvent(event: Event) {
	}
}

object GatherFragments : State<GayteScript>() {
	override suspend fun GayteScript.checkNext() = if (!fightActive || !inArena || healthCurrent <= 0) StartFight()
	else if (numFragments >= targetFragments) ProcessFight
	else null

	override suspend fun GayteScript.stateLoop() {
		if (!Effect.ENHANCED_EXCALIBUR.active && inventory.getItem("Augmented enhanced Excalibur") != null && healthMax - healthCurrent > 1500) {
			val item = inventory.getItem("Augmented enhanced Excalibur")
			item?.click("Activate")
		}
		val moonstone = findClosestObjectToTile(
			if (attacksBeforeSpec <= 0 || attacksBeforeSpec >= 10) tileOfLocal(
				38, 9, 2
			) else localPlayer.tile
		) {
			it.name() == "Moonstone"
		}
		if (moonstone?.interact("Gather") == true) waitThenDelayUntil(3000, 30000) { numFragments >= targetFragments }
		delay(100)
	}
}

object ProcessFight : State<GayteScript>() {
	internal var currentTarget: SceneObject? = null

	override suspend fun GayteScript.checkNext() = if (!fightActive || !inArena) StartFight()
	else if (pillarsUp) DunkAndHeal()
	else if (numFragments < 6) GatherFragments
	else null

	override suspend fun GayteScript.stateLoop() {
		dodgeAoes(currentTarget)
		if (!Effect.ENHANCED_EXCALIBUR.active && inventory.getItem("Augmented enhanced Excalibur") != null && healthMax - healthCurrent > 1500) {
			val item = inventory.getItem("Augmented enhanced Excalibur")
			item?.click("Activate")
		}

		if (familiarSummoned && !gargoyleCast) {
			println(familiarName.lowercase())
			if (familiarName.lowercase().contains("gargoyle")) {
				if (familiarScrolls == 0 && (inventory.getItem(49410)?.amount ?: 0) > 0) familiarStoreScrolls()
				println("Gargoyle summoned")
				if (familiarSpecialPoints > 10 && familiarScrolls > 0) if (familiarCastSpecial()) {
					println("Gargoyle cast special")
					gargoyleCast = true
					return
				}
			}
		}

		if (shouldDunk()) {
			IFSlot(743, 1, -1).click(1)
			delayUntil(2000) { chargingLaser }
		}
		if (barrierPercent <= 10) return repairBarrier()
		if (killAkhs()) return
		var wasInDanger = inCriticalDanger
		if (wasInDanger && Effect.BONE_SHIELD.active && Ability.BARRICADE.offCd) if (castWithAdren(
				Ability.BARRICADE, 100
			)
		) {
			delay(210)
		}
		wasInDanger = inCriticalDanger
		if (findAndGatherNode("Cleansed shard of Elidinis", "Mine", wasInDanger)) return
		if (findAndGatherNode("Corrupt shard of Elidinis", "Transmute", wasInDanger)) return
		delay(210, 502)
	}
}

private suspend fun GayteScript.findAndGatherNode(
	nodeName: String, option: String, wasInDanger: Boolean, tile: Tile? = null
): Boolean {
	var tempDanger = wasInDanger
	if (getAllObjectsWithinRange(50).count { (it.name() == "Cleansed shard of Elidinis" || it.name() == "Corrupt shard of Elidinis") && it.tile.yInMapSquare >= 26 } < 4) tempDanger =
		true

	findClosestNode(nodeName, tile ?: localPlayer.tile, safeZone = if (tempDanger) true else null)?.let {
		gatherNode(option, it, wasInDanger)
		return true
	}
	return false
}

private suspend fun GayteScript.gatherNode(option: String, node: SceneObject, wasInDanger: Boolean) {
	currentTarget = node
	val targetTile = routedDestination(localPlayer.tile, node, collision = CollisionStrategies.NOCLIP)
	if (targetTile?.let { inAoe(it) } == true) return delay(100)
	node.interact(option)
	delayUntil {
		!fightActive || inAoe || pillarsUp || (!wasInDanger && inCriticalDanger) || (wasInDanger && !inCriticalDanger) || !node.exists || findAkh() != null || shouldDunk()
	}
}

private fun findAkh() = findClosestNPC { it.name == "Feline akh" && it.currentHealth > 0 }

private suspend fun GayteScript.killAkhs(): Boolean {
	val akh = findAkh()
	if (akh != null) {
		while (akh.exists() && akh.currentHealth > 0) {
			akh.interact("Dismiss")
			delay(212, 100)
		}
		return true
	}
	return false
}

private suspend fun GayteScript.repairBarrier() {
	findClosestNPC("Moonstone conduit", 30)?.let { conduit ->
		if (conduit.tile.getDistance(localPlayer.tile) > 15) {
			if (dive(localPlayer.tile.transform(0, random(9, 11)))) {
				delay(125, 100)
				surge()
				waitThenDelayUntil(800, 2000) { findClosestNPC("Moonstone conduit") != null }
				return
			} else {
				walkTo(localPlayer.tile.transform(0, random(9, 11)), false)
				delay(600, 100)
				surge()
				delay(125, 100)
				walkTo(conduit.tile.transform(2, random(0,3)), false)
				waitThenDelayUntil(800, 2000) { findClosestNPC("Moonstone conduit") != null }
			}
		}
		if (conduit.interact("Repair")) {
			waitThenDelayUntil(1200, 30000) { barrierPercent >= 90 }
		}
		return
	}
}

class DunkAndHeal() : State<GayteScript>() {
	var dunked = false
	var madeIt = false
	var tanked = false

	override suspend fun GayteScript.checkNext() = if (!fightActive || !inArena) StartFight()
	else if (bossHealthCurrent <= 0 && openChest.value) OpenChest()
	else if (dunked && tanked) ProcessFight
	else null

	override suspend fun GayteScript.stateLoop() {
		killAkhs()
		if (!dunked) {
			val nearPillars = tileOfLocal(31, 25, 2)
			if (findClosestObject(130994, range = 8) == null && nearPillars.getDistance(localPlayer.tile) > 3) {
				if (walkTo(nearPillars.randomize(1), false)) waitThenDelayUntil(1500, pollingDelayMillis = 600) {
					!localPlayer.isAniMoving || findClosestObject(
						130994, range = 8
					) != null
				}
				else delay(101)
				return
			}
			if (findClosestObject(130994) != null) dunkIt()
			else delay(100)
			return
		}

		if (bossHealthCurrent <= 0 && openChest.value) return

		if (!tanked && !localPlayer.isAnimating) {
			val safeTile = tileOfLocal(31, 14, 2)
			if (localPlayer.tile != safeTile && !madeIt) {
				if (dive(localPlayer.tile.transform(0, -random(10, 13)))) {
					delay(125, 100)
					surge()
					delay(950)
					surge()
					delay(138, 100)
				} else {
					walkTo(safeTile, false)
					delay(125, 100)
					surge()
					walkTo(safeTile, false)
					delay(950)
					surge()
					walkTo(safeTile, false)
					delay(138, 100)
				}
				if (walkTo(safeTile, false)) {
					while (!localPlayer.isMoving) {
						delay(220, 55)
						walkTo(safeTile, false)
					}
					waitThenDelayUntil(1000, 10000) { !localPlayer.isAniMoving }
				} else delay(100)
			} else madeIt = true
			if (madeIt) {
				var node = findClosestNode("Corrupt shard of Elidinis", tileOfLocal(33, 13, 2))
				if (node != null) {
					if (node.tile == tileOfLocal(33, 13, 2)) {
						node.interact("Transmute")
						delayUntil(10000) { !chargingLaser }
					}
				} else {
					node = findClosestNode("Cleansed shard of Elidinis", tileOfLocal(33, 13, 2))
					if (node != null) {
						if (node.tile == tileOfLocal(33, 13, 2)) {
							node.interact("Mine")
							delayUntil(10000) { !chargingLaser }
						}
					}
				}
			}
			if (!chargingLaser) tanked = true
		}
	}

	private suspend fun GayteScript.dunkIt(): Boolean {
		val startTime = System.currentTimeMillis()
		while (dunkStage <= 3) {
			if (System.currentTimeMillis() - startTime > 30000) break
			val prev = dunkStage
			if (interactClosestObject(
					DUNK_VAR_TO_PILLAR_OBJECT[dunkStage] ?: return false, "Jump"
				)
			) delayUntil(4000) { prev != dunkStage }
			else delay(100)
		}
		delayUntil(2000) { dunkStage == 4 }
		if (dunkStage == 4 && interactClosestNPC(GATE_NPC_ID, "Cleanse")) {
			delayUntil(10000) { dunkStage == 5 }
			dunked = true
		}
		return true
	}
}

class OpenChest : State<GayteScript>() {
	var chestLooted = false
	override suspend fun GayteScript.checkNext(): State<GayteScript>? {
		if (chestLooted) {
			chestLooted = false
			return StartFight()
		}
		return null
	}

	override suspend fun GayteScript.stateLoop() {
		val chest = allObjects.firstOrNull { it.id == 131004 } ?: return
		if (!interfaces.isOpen(168)) {
			chest.interact("Search")
			delayUntil(2000) { interfaces.isOpen(168) }
			return
		}
		IFSlot(168, 27, -1).click(1)
		chestLooted = true
		escape()
	}
}

private val divTransmuteProgress
	get() = npcs.values.firstNotNullOfOrNull { it.headbars.firstOrNull { it -> it.type == 48 }?.fromFill } ?: 0
private val miningProgress
	get() = npcs.values.firstNotNullOfOrNull { it.headbars.firstOrNull { it -> it.type == 49 }?.fromFill } ?: 0

data class NodeProgress(val tile: Tile, val progress: Int)

private val nodeProgresses = mutableListOf<NodeProgress>()
private val fightActive
	get() = npcs.values.any { it.id == 17693 && it.hiddenMenuOpFlags and 1 != 0 }
private val fightOnCooldown
	get() = npcs.values.any {
		it.id == 17693 && it.headbars.any {
			it.type == 45 && it.durationMillis == 60000L && it.timeLeftMillis >= 0
		} == true
	}

private val barrierHealth
	get() = varps.getVar(10947)
private val barrierPercent
	get() = (barrierHealth.toDouble() / 20000.toDouble()) * 100.0
private val attackNum
	get() = varps.getVar(11843)
private val attacksBeforeSpec
	get() = varps.getVar(11844)
private val dunkPower
	get() = varps.getVar(11837)
private val dunkStage
	get() = varps.getVar(11838)
private val inArena
	get() = localPlayer.localTile.x >= 11 && localPlayer.localTile.x <= 51 && localPlayer.localTile.y >= 6

private fun inDangerous(tile: Tile) = tile.yInMapSquare >= 26
private val inAoe
	get() = inAoe(localPlayer.tile)

var spotAnime7X7Delay = if (actionbarAbilities[Ability.BLADED_DIVE.type] != null)
	1400
else
	330

private fun inAoe(tile: Tile) = !localPlayer.isMoving &&
		spotAnims.any {
			(it.id == AOE_3x3_SPOTANIM && it.timeAliveMillis > 2000 && tile.withinDistance(it.tile, 1))
					|| (it.id == AOE_7x7_SPOTANIM && it.timeAliveMillis > spotAnime7X7Delay && tile.withinDistance(
				it.tile,
				4
			))
		}

private fun findClosestNode(name: String, tile: Tile, safeZone: Boolean? = null) = findClosestObjectToTile(tile) {
	it.name() == name && when (safeZone) {
		true -> !inDangerous(it.tile)
		false -> inDangerous(it.tile)
		null -> true
	}
}

private val inCriticalDanger
	get() = projectiles.any {
		it.id == YIKES_PROJECTILE && it.lockedOnto(localPlayer) && it.tile.withinDistance(localPlayer.tile, 15)
	} && Effect.BARRICADE.notActive

private val numFragments
	get() = inventory.count(MOONSTONE_FRAGMENTS)

private fun GayteScript.shouldDunk() =
	(healthPercent <= 35 || (bossHealthCurrent + 3125) < (inventory.count(57517) * 3125) || inventory.freeSlots < 1) && !chargingLaser

private suspend fun GayteScript.dodgeAoes(currentTarget: SceneObject? = null) {
	if (inAoe) {
		val safeTile = findClosestSafeTile(
			if (currentTarget != null) TileArea(
				currentTarget.tile, currentTarget.defs.sizeX, currentTarget.defs.sizeY
			) else null
		) ?: return
		if (safeTile.getDistance(localPlayer.tile) > 2 && dive(safeTile)) {
			delay(300, 200)
			return
		}

		if (safeTile.getDistance(localPlayer.tile) > 2 && walkTo(safeTile, false)) {
			delay(1330, 100)
			return
		}

		if (walkTo(safeTile, false)) delay(452, 100)
	}
}

private fun findClosestSafeTile(target: TileArea? = null): Tile? {
	if (!inAoe) return null
	val playerPos = localPlayer.tile
	val dangerZones = spotAnims.filter { it.id == AOE_3x3_SPOTANIM || it.id == AOE_7x7_SPOTANIM }.map { spotAnim ->
		val radius = if (spotAnim.id == AOE_3x3_SPOTANIM) 1 else 4
		DangerZone(spotAnim.tile, radius)
	}

	val sceneryDiveBlockers = getAllObjectsWithinRange(10).filter { obj -> obj.shape == ObjectShape.SCENERY_INTERACT }
		.map { obj -> TileArea(tile = obj.tile, sizeX = obj.defs.sizeX, sizeY = obj.defs.sizeY) }

	val conduitDiveBlockers = npcs.values.filter { it.name() == "Moonstone conduit" }
		.map { npc -> TileArea(tile = npc.tile.transform(-1, -2), sizeX = 3, sizeY = 3) }

	val obstacles = sceneryDiveBlockers + conduitDiveBlockers

	return calculateClosestSafeTile(playerPos, dangerZones, target ?: TileArea(playerPos, 1, 1), obstacles)
}
