package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.api.*
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random

class VioletIsBlue : State<DebugScript>() {

	companion object {
		enum class QuestDialogues(val number: Int, val text: String) {
			BREAK_THE_DOOR_DOWN(3, "Break the door down."),
			READY_FOR_ADVENTURE1(1, "To go on an adventure."),
			YOUVE_CAPTURED_A_HUMAN_GIRL(1, "YOU'VE CAPTURED A HUMAN GIRL!"),
			YES(1, "Yes!"),
			READY_FOR_ADVENTURE(1, "Go on an adventure!"),
			VIOLET_YES(1, "Yes."),
			USEFUL(1, "Do you have anything useful?");
		}

		private val SNOWMAN_VARBITS = listOf(42957, 42956, 42955)
		private val SNOWMAN_REQUIRED_ITEMS = listOf("Coal", "Carrot", "Top hat", "Snowball", "Branch")
		private val SUPPLY_ITEMS = listOf("Coal", "Carrot", "Top hat")
		private const val REQUIRED_BRANCH_AMOUNT = 6
		private const val SNOWBALL_TARGET_AMOUNT = 75
		private const val SNOWMAN_NAME = "Snowman"
		private const val BROKEN_SNOWMAN_NAME = "Broken snowman"
		private const val MELTED_SNOWMAN_NAME = "Melted snowman"
		private const val VIOLET_NAME = "Violet"
		private const val TREE_NAME = "Tree"
		private const val SNOW_PILE_NAME = "Pile of snow"
		private const val ABANDONED_CRATE_NAME = "Abandoned crate"
	}

	private val startCord = Tile.of(2854, 3459, 0)
	private val startArea = Area.Circular(startCord, 15.0)
	private var leave = false
	private var allitemsgot = false
	private var allitemstaken = false

	var start: Tile? = null

	override suspend fun DebugScript.checkNext(): State<DebugScript>? {
		val QuestVarp: Int = varps.getVarBit(5325)
		val QuestProg: Int = varps.getVarBit(5326)
		QuestDialogs.resetDialogOptions()
		QuestDialogs.updateQuestDialogOptions(QuestDialogues.entries.map { it.text })
		if (QuestDialogs.isDialogOpen()) {
			return QuestDialogState()
		}

		println(QuestProg.toString())
		println(QuestVarp.toString())

		if (localPlayer.isMoving && QuestProg != 5) {
			return null
		}

		if (QuestVarp == 0) {
			println("Starting quest... Violet is blue!");

			if (!startArea.contains(localPlayer.tile)) {

				return moveToStart
			} else {
				talkToViolet()
			}
		} else {
			if (startArea.contains(localPlayer.tile)) {
				var portal = allObjects.filter { it.name() == "Land of Snow portal" }
					.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (portal != null) {
					portal.interact("Enter")
					delay(700, 100)
					return null
				}
			}
			when (QuestProg) {
				5 -> snowball()
				10, 11, 12 -> doorandtrevor()
				15, 20, 48, 55, 75, 82 -> talkToViolet()
				25 -> storageGame()
				30 -> talktoTrevor()
				32 -> leavedoor()
				35 -> honeyshit()
				44 -> honeyshit2()
				50 -> {
					val snow: SceneObject? = allObjects.filter { it.name() == "Icy snow" }
						.minByOrNull { it.tile.getDistance(localPlayer.tile) }
					if (snow != null) {
						val snowx: Int = snow.tile.x
						val snowy: Int = snow.tile.y
						if (localPlayer.tile.x == snowx - 2 && localPlayer.tile.y == snowy + 1) {
							if (!localPlayer.isMoving) {
								headlesspeople()
							}
						} else {
							QuestDialogs.setAnyOption(true)
							interactwithsnow()
						}
					}
				}

				60 -> {
					QuestDialogs.setAnyOption(false)
					buildsnowmen()
				}

				65 -> icywater()
				70 -> puzzle()
				85 -> makesledge()
				90 -> println("Quest Complete")
				90, 120 -> {
					println("Quest Complete")
				}
			}

		}

		delay(200, 50)
		return null
	}

	suspend fun Script.interactwithopenspace() {
		var openspace =
			allObjects.filter { it.name() == "Open space" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (openspace != null) {
			openspace.interact("Investigate");
			delay(700, 100);
		}
	}

	suspend fun Script.makesledge() {
		val progress = varps.getVarBit(42962)

		if (progress == 0) {
			interactwithopenspace();
			return;
		} else if (progress == 1) {
			if (inventory.hasItems("Barrel parts", "Rope", "Unlit lantern", "Charcoal", "Yeti sign")) {
				interactwithopenspace();
				return;
			}
			val barrle =
				allObjects.filter { it.name() == "Rotten barrel" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (barrle != null && !inventory.hasItem("Barrel parts")) {
				barrle.interact("Investigate");
				delay(600, 100);
				return;
			}
			val crate = allObjects.filter { it.name() == "Crate" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (crate != null && !inventory.hasItem("Rope")) {
				crate.interact("Investigate");
				delay(600, 100);
				return;
			}
			val ice = allObjects.filter { it.name() == "Ice" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (ice != null && !inventory.hasItem("Unlit lantern")) {
				ice.interact("Investigate");
				delay(3500, 500);
				return;
			}
			val fire = allObjects.filter { it.name() == "Fire" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (fire != null && !inventory.hasItem("Charcoal")) {
				fire.interact("Investigate");
				delay(random(600, 100));
				return;
			}
			val tree = allObjects.filter { it.name() == "Yeti village sign" }
				.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (tree != null && !inventory.hasItem("Yeti sign")) {
				tree.interact("Chop down");
				delay(3500, 500);
				return;
			}
		} else if (progress > 1 && progress != 6) {
			interactwithopenspace();
			delay(2500, 500);
			return;
		} else {
			talkToViolet();
			return;
		}
	}

	suspend fun Script.icywater() {
		if (inventory.hasItem("Barrel")) {
			talkToViolet();
		} else {
			val barrel =
				allObjects.filter { it.name() == "Icy water" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (barrel != null) {
				barrel.interact("Reach into");
				delay(600, 100);
				return;
			}
		}
	}

	suspend fun Script.buildsnowmen() {
		val snowmanProgress = SNOWMAN_VARBITS.map { varps.getVarBit(it) }
		snowmanProgress.forEach { println(it.toString()) }

		val hasPartialSnowman = snowmanProgress.any { it in 1..4 }
		val allSnowmenComplete = snowmanProgress.all { it == 5 }

		when {
			hasPartialSnowman -> {
				println("11")
				handlePartialSnowmen(snowmanProgress)
			}

			allSnowmenComplete -> {
				println("222")
				talkToViolet()
			}

			else -> {
				println("222")
				println("222")
				gatherSnowmanMaterials()
			}
		}
	}

	private suspend fun Script.handlePartialSnowmen(progress: List<Int>) {
		val brokenSnowmanCount = countObjects(BROKEN_SNOWMAN_NAME)
		println(brokenSnowmanCount.toString())
		if (brokenSnowmanCount >= 1 && progress.all { it >= 4 }) {
		    talkToViolet()
		    return
		}

		val completeSnowmanCount = countObjects(SNOWMAN_NAME)
		println(completeSnowmanCount.toString())
		if (completeSnowmanCount >= 3) {
			talkToViolet()
			return
		}

		if (decorateIncompleteSnowman()) {
			return
		}

		rebuildMeltedSnowman()
	}

	private suspend fun Script.gatherSnowmanMaterials() {
		if (hasAllSnowmanMaterials()) {
			rebuildMeltedSnowman()
			return
		}

		logSnowmanInventorySnapshot()

		if (hasRequiredSupplyAmounts()) {
			if (ensureBranchSupply()) {
				return
			}
		}

		if (collectSnowballsIfNeeded()) {
			return
		}

		searchAbandonedCrate()
	}

	private suspend fun Script.decorateIncompleteSnowman(): Boolean {
		val snowman = findClosestObject(SNOWMAN_NAME) { !it.hasOption("Admire") } ?: return false
		snowman.interact("Add branches")
		delay(700, 100)
		snowman.interact("Add face")
		delay(700, 100)
		snowman.interact("Add hat")
		delay(700, 100)
		delay(700, 100)
		return true
	}

	private suspend fun Script.rebuildMeltedSnowman(): Boolean {
		val meltedSnowman = findClosestObject(MELTED_SNOWMAN_NAME) ?: return false
		meltedSnowman.interact("Build")
		delay(700, 100)
		return true
	}

	private fun Script.hasAllSnowmanMaterials(): Boolean {
		return inventory.hasItems(*SNOWMAN_REQUIRED_ITEMS.toTypedArray()) &&
				inventory.getItem("Branch")?.amount == REQUIRED_BRANCH_AMOUNT
	}

	private fun Script.hasRequiredSupplyAmounts(): Boolean {
		return inventory.hasItems(*SUPPLY_ITEMS.toTypedArray()) &&
				inventory.getItem("Coal")?.amount == 21 &&
				inventory.getItem("Carrot")?.amount == 3 &&
				inventory.getItem("Top hat")?.amount == 3
	}

	private fun Script.logSnowmanInventorySnapshot() {
		println(inventory.getItem("Coal")?.amount)
		println(inventory.getItem("Carrot")?.amount)
		println(inventory.getItem("Top hat")?.amount)
		println(inventory.hasItems(*SUPPLY_ITEMS.toTypedArray()).toString())
	}

	private suspend fun Script.ensureBranchSupply(): Boolean {
		println("Hi")
		if (inventory.hasItem("Branch") && inventory.getItem("Branch")?.amount == REQUIRED_BRANCH_AMOUNT) {
			return true
		}

		val tree = findClosestObject(TREE_NAME) ?: return false
		tree.interact("Prune")
		delay(600, 100)
		return true
	}

	private suspend fun Script.collectSnowballsIfNeeded(): Boolean {
		if (!inventory.hasItem("Snowball") || (inventory.getItem("Snowball")?.amount ?: 0) < SNOWBALL_TARGET_AMOUNT) {
			println("Hi")
			val snowPile = findClosestObject(SNOW_PILE_NAME) ?: return false
			snowPile.interact("Collect snow")
			delay(600, 100)
			return true
		}

		return false
	}

	private suspend fun Script.searchAbandonedCrate(): Boolean {
		val crate = findClosestObject(ABANDONED_CRATE_NAME) ?: return false
		crate.interact("Search")
		delay(600, 100)
		return true
	}

	private fun Script.countObjects(name: String): Int {
		return allObjects.count { it.name() == name }
	}

	suspend fun Script.interactwithsnow() {
		val snow = allObjects.filter { it.name() == "Icy snow" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (snow != null) {
			snow.interact("Take")
			delay(700, 100)
		}
	}

	suspend fun Script.headlesspeople() {
		val headless = npcs.values.filter { it.name() == "Headless ice golem" }.randomOrNull()
		if (headless != null) {
			walkTo(headless.tile, false)
			delay(200, 100)
			return;

		} else {
			val violet =
				npcs.values.filter { it.name() == "Violet" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (violet != null) {
				violet.interact("Talk to")
				delay(700, 100)
				return;
			}
		}
	}

	suspend fun Script.talkToViolet() {
		var violet =
			npcs.values.firstOrNull { it.name() == "Violet" }
		println("SUP")
		println(violet.toString())
		if (violet != null) {
			violet.interact("Talk to")
			delay(700, 100)
			return
		}
	}

	suspend fun Script.talktoTrevor() {
		var trevor =
			npcs.values.filter { it.name() == "Trevor" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (trevor != null) {
			trevor.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.leavedoor() {
		val door = allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Exit") }
		if (door != null) {
			if (localPlayer.isMoving) {
				return
			}
			door.interact("Exit")
			delay(700, 100)
		} else {
			talktoTrevor()
		}
	}

	suspend fun Script.honeyshit() {

		if (inventory.hasItem("Bucket of syrup")) {
			val tree3 =
				allObjects.filter { it.name() == "Leaning tree" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (tree3 != null) {
				tree3.interact("Check");
				delay(700, 100);
			}
			return;
		}

		val tree2 = allObjects.filter { it.name() == "Maple tree with spile" }
			.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (tree2 != null && !inventory.hasItem("Bucket of syrup")) {
			tree2.interact("Investigate");
			delay(700, 100);
			return;
		}

		if (!inventory.hasItem("Empty bucket")) {
			val bucket =
				allObjects.filter { it.name() == "Frozen bucket" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (bucket != null) {
				bucket.interact("Investigate");
				delay(700, 100);
			}
			return;
		}

		if (!inventory.hasItem("Maple logs") || inventory.hasItem("Spile")) {
			val tree =
				allObjects.filter { it.name() == "Maple tree" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (tree != null) {
				tree.interact("Investigate");
				delay(700, 100);
			}
			println("maple log");
			return;
		}

		if (inventory.hasItem("Maple logs") && !inventory.hasItem("Spile")) {
			inventory.clickItem("Maple logs", "Investigate");
			delay(700, 100);
			return;
		}

		if (inventory.hasItem("Bucket of syrup")) {
			val tree3 =
				allObjects.filter { it.name() == "Leaning tree" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (tree3 != null) {
				tree3.interact("Check");
				delay(700, 100);
			}
			return;

		}

	}

	suspend fun Script.honeyshit2() {
		if (varps.getVarBit(42947) < 10) {
			if (inventory.hasItem("Empty jar") || inventory.hasItem("Jar of fireflies")) {
				val firefly =
					npcs.values.filter { it.name() == "Firefly" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (firefly != null) {
					firefly.interact("Catch");
					delay(700, 100);
					return;

				}
				val bush =
					allObjects.filter { it.name() == "Bush" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (bush != null) {
					bush.interact("Shake");
					delay(700, 100);
				}
				return;
			} else {
				talktoTrevor();
			}
		} else {
			talkToViolet();
		}
	}

	override suspend fun DebugScript.stateLoop() {}

	suspend fun Script.puzzle() {
		var player = localPlayer.tile
		if (start == null) {
			start = player
		}
		val spot1violet: Tile = Tile.of(start!!.x, start!!.y + 1, start!!.plane)
		val spot1me: Tile = Tile.of(start!!.x, start!!.y, start!!.plane)
		val spot2violet: Tile = Tile.of(start!!.x, start!!.y + 5, start!!.plane)
		val spot2me: Tile = Tile.of(start!!.x, start!!.y + 4, start!!.plane)
		val spot3me: Tile = Tile.of(start!!.x + 5, start!!.y + 4, start!!.plane)
		val spot4me: Tile = Tile.of(start!!.x + 5, start!!.y + 5, start!!.plane)
		val spot5me: Tile = Tile.of(start!!.x + 1, start!!.y + 5, start!!.plane)
		val spot5violet: Tile = Tile.of(start!!.x, start!!.y + 5, start!!.plane)
		val spot6me: Tile = Tile.of(start!!.x - 4, start!!.y + 5, start!!.plane)
		val spot7me: Tile = Tile.of(start!!.x - 4, start!!.y, start!!.plane)
		val spot8me: Tile = Tile.of(start!!.x - 5, start!!.y + 1, start!!.plane)
		val spot9me: Tile = Tile.of(start!!.x - 5, start!!.y + 4, start!!.plane)
		val spot9violt: Tile = Tile.of(start!!.x - 5, start!!.y + 5, start!!.plane)
		val spot10me: Tile = Tile.of(start!!.x - 5, start!!.y + 9, start!!.plane)
		val spot11me: Tile = Tile.of(start!!.x - 8, start!!.y + 9, start!!.plane)
		val spot12me: Tile = Tile.of(start!!.x - 6, start!!.y + 7, start!!.plane)
		val spot13me: Tile = Tile.of(start!!.x - 6, start!!.y + 10, start!!.plane)
		val spot13violt: Tile = Tile.of(start!!.x - 5, start!!.y + 10, start!!.plane)
		val spot14me: Tile = Tile.of(start!!.x + 2, start!!.y + 10, start!!.plane)
		val spot15me: Tile = Tile.of(start!!.x + 2, start!!.y + 7, start!!.plane)
		val spot16me: Tile = Tile.of(start!!.x + 5, start!!.y + 7, start!!.plane)
		val spot17me: Tile = Tile.of(start!!.x + 3, start!!.y + 9, start!!.plane)
		val spot17violt: Tile = Tile.of(start!!.x + 3, start!!.y + 10, start!!.plane)
		val spot18me: Tile = Tile.of(start!!.x + 3, start!!.y + 12, start!!.plane)
		val spot19me: Tile = Tile.of(start!!.x + 4, start!!.y + 12, start!!.plane)
		val spot20me: Tile = Tile.of(start!!.x + 4, start!!.y + 15, start!!.plane)
		val spot21me: Tile = Tile.of(start!!.x + 8, start!!.y + 15, start!!.plane)
		val spot22me: Tile = Tile.of(start!!.x + 8, start!!.y + 13, start!!.plane)
		val spot23me: Tile = Tile.of(start!!.x + 4, start!!.y + 13, start!!.plane)
		val spot23violt: Tile = Tile.of(start!!.x + 3, start!!.y + 13, start!!.plane)
		val spot24me: Tile = Tile.of(start!!.x, start!!.y + 9, start!!.plane)
		val spot25me: Tile = Tile.of(start!!.x, start!!.y + 12, start!!.plane)
		val spot25violt: Tile = Tile.of(start!!.x, start!!.y + 13, start!!.plane)
		val spot26me: Tile = Tile.of(start!!.x, start!!.y + 20, start!!.plane)

		println(start)

		val violet = npcs.values.filter { it.name() == "Violet in a barrel" }
			.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		println(violet)
		if (violet != null) {
			if (localPlayer.isMoving) {
				return
			}
			if (player.equals(spot25me) && !violet.tile.equals(spot25violt)) {
				walkTo(spot26me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot25me) && violet.tile.equals(spot25violt)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (player.equals(spot24me)) {
				walkTo(spot25me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot23me) && !violet.tile.equals(spot23violt)) {
				walkTo(spot24me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot23me) && violet.tile.equals(spot23violt)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (player.equals(spot22me)) {
				walkTo(spot23me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot21me)) {
				walkTo(spot22me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot20me)) {
				walkTo(spot21me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot19me)) {
				walkTo(spot20me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot18me)) {
				walkTo(spot19me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot17me) && !violet.tile.equals(spot17violt)) {
				walkTo(spot18me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot17me) && violet.tile.equals(spot17violt)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (player.equals(spot16me)) {
				walkTo(spot17me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot15me)) {
				walkTo(spot16me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot14me)) {
				walkTo(spot15me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot13me) && !violet.tile.equals(spot13violt)) {
				walkTo(spot14me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot13me) && violet.tile.equals(spot13violt)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (player.equals(spot12me)) {
				walkTo(spot13me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot11me)) {
				walkTo(spot12me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot10me)) {
				walkTo(spot11me, false)
				delay(700, 100)
				return
			}

			if (player.equals(spot9me) && !violet.tile.equals(spot9violt)) {
				walkTo(spot10me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot9me) && violet.tile.equals(spot9violt)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}

			if (player.equals(spot8me)) {
				walkTo(spot9me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot7me)) {
				walkTo(spot8me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot6me)) {
				walkTo(spot7me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot5me) && !violet.tile.equals(spot5violet)) {
				walkTo(spot6me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot5me) && violet.tile.equals(spot5violet)) {
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (player.equals(spot4me)) {
				walkTo(spot5me, false)
				delay(700, 100)
				return
			}
			if (player.equals(spot3me)) {
				walkTo(spot3me.transform(0, 1, 0), false)
				delay(700, 100)
				return
			}
			if (player.equals(spot2me)) {
				walkTo(spot2me.transform(1, 0, 0), false)
				delay(700, 100)
				return
			}
			if (violet.tile.equals(spot1violet) && player.equals(spot1me)) {
				println("yo")
				violet.interact("Push")
				delay(700, 100)
				return
			}
			if (violet.tile.equals(spot2violet) && player.equals(start)) {
				walkTo(spot1violet, false)
				delay(700, 100)
				return
			}
		} else {
			println("Violet nul")
		}
	}

	suspend fun Script.snowball() {
		val portal: SceneObject? =
			allObjects.filter { it.name() == "Land of Snow portal" && it.hasOption("Exit") }
				.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (portal != null) {
			val portalcord: Tile = portal.tile
			println(localPlayer.toString())
			val bottomright: Tile = Tile((portalcord.x - 1), portalcord.y, portalcord.plane)
			val topright: Tile = Tile((portalcord.x - 6), (portalcord.y + 57), portalcord.plane)
			val area: Area = Area.Rectangular(bottomright, topright)

			if (!area.contains(localPlayer.tile)) {
				val Violet =
					npcs.values.filter { it.name() == "Violet" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (Violet != null) {
					Violet.interact("Talk to")
					delay(700, 100)
					return
				}
			}
			val playerfakecord: Tile =
				Tile(localPlayer.tile.x, (localPlayer.tile.y + 4), localPlayer.tile.plane)
			val middle: Tile = Tile((portalcord.x - 3), localPlayer.tile.y, localPlayer.tile.plane)
			val violet =
				npcs.values.filter { it.name() == "Violet" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
			if (violet != null) {
				println(violet.tile.getDistance(localPlayer.tile).toString())
				if (violet.tile.getDistance(localPlayer.tile) <= 7) {
					violet.interact("Talk to")
					delay(700, 100)
				}
			}
			val snowball =
				npcs.values.filter { it.name() == "Snowball" && it.tile.y >= localPlayer.tile.y }
					.minByOrNull { it.tile.y }
			if (snowball != null) {
				val x1: Int = snowball.tile.x + 2
				val x2: Int = snowball.tile.x + 1
				val x3: Int = snowball.tile.x
				if (localPlayer.tile.x == x1 || localPlayer.tile.x == x2 || localPlayer.tile.x == x3) {
					val moveright: Tile =
						Tile((snowball.tile.x + 3), (playerfakecord.y), playerfakecord.plane)
					val moveleft: Tile =
						Tile((snowball.tile.x - 3), (playerfakecord.y), playerfakecord.plane)

					if (moveright.getDistance(middle) < moveleft.getDistance(middle)) {
						if (area.contains(moveright)) {
							walkTo(moveright, false)
							delay(100, 50)
						} else {
							walkTo(moveleft, false)
							delay(100, 50)
						}
					} else {
						if (area.contains(moveleft)) {
							walkTo(moveleft, false)
							delay(100, 50)
						} else {
							walkTo(moveright, false)
							delay(100, 50)
						}
					}
				} else {
					if (area.contains(localPlayer.tile)) {
						walkTo(playerfakecord, false)
						delay(100, 50)
					}
				}

				if (snowball.tile.y < localPlayer.tile.y) {
					return
				}
			} else {
				if (area.contains(localPlayer.tile)) {
					walkTo(playerfakecord, false)
					delay(200, 50)
				} else {
					walkTo(localPlayer.tile.transform(0, 2, 0), false)
					delay(200, 50)
				}
			}
			return
		}
	}

	suspend fun Script.doorandtrevor() {

		val QuestProg = varps.getVarBit(5326)
		if (QuestProg == 10) {
			talkToViolet()
		}
		val trevor = npcs.values.filter { it.name() == "Trevor" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (trevor != null) {
			trevor.interact("Talk to")
			delay(700, 100)
		}

		val door = allObjects.filter { it.name() == "Door" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
		if (door != null) {
			door.interact("Knock")
			delay(700, 100)
		}
	}

	suspend fun Script.storageGame() {
		val currentitem = varps.getVarBit(5327)
		val itemnum = varps.getVarBit(42876)

		val pattern = Regex(
			listOf(
				"Spare sock", "Weapon gizmo", "Empty jar", "Yo-yo", "Wooden carving",
				"Iron figure", "Scarf", "Climbing boots", "Toy lion",
				"'How to be a Witch'", "Mittens", "Fluffy unicorn"
			).joinToString(separator = "|") { Regex.escape(it) }
		)

		println(itemnum.toString())
		println(currentitem.toString())

		if (itemnum < 6) {
			if (!inventory.hasItem(pattern) && currentitem != 0) {
				openItemInter(currentitem)
			} else {
				talkToViolet();
			}
		}

	}

	suspend fun Script.openItemInter(num: Int) {

		when (num) {
			1, 2, 3, 4 -> {
				val bed = allObjects.filter { it.name() == "Bed" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (bed != null) {
					bed.interact("Look under");
					delayUntil(5000) { QuestDialogs.isDialogOpen() }
				}
			}

			5, 6, 7, 8 -> {
				val wardrobe =
					allObjects.filter { it.name() == "Wardrobe" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (wardrobe != null) {
					wardrobe.interact("Search");
					delayUntil(5000) { QuestDialogs.isDialogOpen() }
				}
			}

			9, 10, 12, 11 -> {
				val toybox =
					allObjects.filter { it.name() == "Toybox" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (toybox != null) {
					toybox.interact("Search");
					delayUntil(5000) { QuestDialogs.isDialogOpen() }

				}
			}
		}
	}

	val pathToStart = listOf(
		Tile.of(2879, 3442, 0),
		Tile.of(2881, 3425, 0),
		Tile.of(2856, 3456, 0)
	)
	private val moveToStart
		get() = traversal(VioletIsBlue(), { localPlayer.tile.getDistance(pathToStart.last()) < 5 }) {
			chebychevPath(
				localPlayer.tile,
				pathToStart,
				fallback = { useLodestone(Lodestone.TAVERLEY) },
				reached = { localPlayer.tile.getDistance(pathToStart.last()) < 5 })

		}
}
