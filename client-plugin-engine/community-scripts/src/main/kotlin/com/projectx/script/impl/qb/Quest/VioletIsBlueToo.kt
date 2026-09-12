package com.projectx.script.impl.qb.Quest

import world.gregs.voidps.type.Tile
import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.api.*
import com.projectx.traversal.Traversal.Companion.traversal

private var currentStep = ""
private var currentStep2 = ""
private var currentStep3 = ""
private var currentStep4 = ""
private var currentStep5 = ""

class VioletIsBlueToo : State<DebugScript>() {
	companion object {
		enum class QuestDialogues(val number: Int, val text: String) {
			NO(1, "No."),
			VIOLETISBLUETOO(1, "Violet is Blue Too."),
			IREMEBERHIM(1, "I remember him."),
			What_should_we_do(2, "What should we do?"),
			PEEK(1, "Peek."),
			At_least_you_got_out_before_Christmas(3, "At least you got out before Christmas."),
			How_can_we_help_out(1, "How can we help out?");
		}

		private val SNOW_IMPS = arrayOf(
			"Marius Claus", "Barry Claus", "Benny Claus", "Boris Claus",
			"Magnus Claus", "Marcus Claus", "Charlie Claus", "Marvin Claus",
			"Morris Claus", "Murphy Claus", "Dennis Claus", "Freddie Claus",
			"Norris Claus", "Rasmus Claus", "Murray Claus"
		)

		private val START_TILE = Tile.of(2854, 3457, 0)
		private val START_AREA = Area.Circular(START_TILE, 10.0)
	}

	private val QuestVarpID: Int = 36215
	private val varbitArray: IntArray = intArrayOf(40610, 48722, 48723, 48724, 48725, 48726, 48727, 48728, 48729)

	fun checkStep(varpQuest: Int, vararg varbitValues: Int): Boolean {
		require(varbitValues.size == varbitArray.size) { "Number of varbit values must match the length of varbitArray." }

		if (varps.getVarBit(QuestVarpID) != varpQuest) {
			return false
		}

		for (i in varbitArray.indices) {
			if (varps.getVarBit(varbitArray[i]) != varbitValues[i]) {
				return false
			}
		}
		return true
	}

	fun printStepValues() {
		println("Varp Quest Value: " + varps.getVarBit(QuestVarpID))
		println("Varbit Values:")
		for (varbit in varbitArray) {
			println(varbit.toString() + ": " + varps.getVarBit(varbit))
		}
	}

	override suspend fun DebugScript.checkNext(): State<DebugScript>? {
		val questVarp = varps.getVarBit(QuestVarpID)
		printStepValues()

		QuestDialogs.resetDialogOptions()
		QuestDialogs.updateQuestDialogOptions(QuestDialogues.entries.map { it.text })
		if (QuestDialogs.isDialogOpen()) {
			return QuestDialogState()
		}

		if (localPlayer.isMoving) {
			return null
		}

		if (questVarp == 0) {
			if (!START_AREA.contains(localPlayer.tile)) {
				return moveToStart
			} else {
				talkToPostiePete()
			}
			return null
		} else {
			if (START_AREA.contains(localPlayer.tile)) {
				var portal = allObjects.filter { it.name() == "Land of Snow portal" }
					.minByOrNull { it.tile.getDistance(localPlayer.tile) }
				if (portal != null) {
					portal.interact("Enter")
					delay(700, 100)
					return null
				}
			}
		}

		when (questVarp) {
			5 -> {
				val portalEnter = allObjects.firstOrNull { it.name() == "Land of Snow portal" && it.hasOption("Enter") }
				if (portalEnter != null) {
					portalEnter.interact("Enter")
					delay(700, 100)
					return null
				}
				val portalExit = allObjects.firstOrNull { it.name() == "Land of Snow portal" && it.hasOption("Exit") }
				if (portalExit != null) {
					talkToPosty()
				}
			}

			10 -> {
				val posty =
					npcs.values.minByOrNull { if (it.name() == "Posty") it.tile.getDistance(localPlayer.tile) else Int.MAX_VALUE }
				if (posty != null && posty.name() == "Posty") {
					val target = posty.tile.transform(0, 10, 0)
					walkTo(target, false)
					delay(600, 200)
				}
			}

			15 -> {
				allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Knock") }?.let {
					it.interact("Knock")
					delayUntil(15000) { isDialogOpen() }
				}
			}

			25 -> {
				talkToViolet()
			}

			30 -> {
				if (checkStep(questVarp, 0, 0, 0, 0, 0, 0, 0, 0, 0)) {
					allObjects.firstOrNull { it.name() == "Chest" }?.interact("Search")
					delayUntil(15000) { isDialogOpen() }
					return null
				}
				if (checkStep(questVarp, 1, 0, 0, 0, 0, 0, 0, 0, 0)) {
					allObjects.firstOrNull { it.name() == "Rug" && it.hasOption("Search") }?.interact("Search")
					delayUntil(15000) { isDialogOpen() }
					return null
				}
				if (checkStep(questVarp, 2, 0, 0, 0, 0, 0, 0, 0, 0)) {
					allObjects.firstOrNull { it.name() == "Fireplace" && it.hasOption("Search") }?.interact("Search")
					delayUntil(15000) { isDialogOpen() }
					return null
				}
				if (checkStep(questVarp, 3, 0, 0, 0, 0, 0, 0, 0, 0)) {
					allObjects.firstOrNull { it.name() == "Barrel of fish" && it.hasOption("Search") }
						?.interact("Search")
					delayUntil(15000) { isDialogOpen() }
					return null
				}
			}

			35 -> {
				talkToViolet()
				delayUntil(15000) { isDialogOpen() }
				talkToSnowImp()
				delayUntil(15000) { isDialogOpen() }
			}

			40 -> {
				val gate =
					allObjects.minByOrNull { if (it.name() == "Gate") it.tile.getDistance(localPlayer.tile) else Int.MAX_VALUE }
				if (gate != null && gate.name() == "Gate") {
					val dest = Tile.of(gate.tile.x, gate.tile.y - 3, gate.tile.plane)
					walkTo(dest, true)
					delayUntil(15000) { localPlayer.tile == dest }
				}
			}

			45 -> {
				val imp = npcs.values.firstOrNull { it.name() in SNOW_IMPS }
				if (imp != null) {
					imp.interact("Talk to")
					delayUntil(15000) { isDialogOpen() }
				} else {
					allObjects.firstOrNull { it.name() == "Chest" }?.interact("Open")
					delay(600, 100)
				}
			}

			60 -> {
				if (checkStep(questVarp, 4, 0, 0, 0, 0, 0, 0, 0, 0)) {
					println("Starting town tasks... $currentStep")
					if (currentStep == "") {
						delay(4000, 500)
						talkToAssistantBrad()
						delay(4000, 500)
						currentStep = "GOTOTAYLOR"
						println("Current step: $currentStep")
					} else if (currentStep == "GOTOTAYLOR") {
						println("Finding Taylor...")
						val npc = npcs.values.firstOrNull { it.name() in SNOW_IMPS }
						if (npc != null) {
							println("Going to Taylor at ${npc.tile}")
							val p1 = npc.tile.transform(-1, 33, 0)
							walkTo(p1, true)
							delay(1800, 200)
							val p2 = npc.tile.transform(-29, 39, 0)
							walkTo(p2, true)
						}
						currentStep = "TALKTOTAYLOR"
						println("current step: $currentStep")
					} else if (currentStep == "TALKTOTAYLOR") {
						talkToTaylor()
						currentStep = "TALKTOBRAD"
						println("current step: $currentStep")
					} else if (currentStep == "TALKTOBRAD") {
						val npc = npcs.values.firstOrNull { it.name() == "Taylor" }
						if (npc != null) {
							println("Going to Brad at ${npc.tile}")
							val p2 = npc.tile.transform(29, -39, 0)
							walkTo(p2, true)
							delay(1800, 200)
							val p1 = npc.tile.transform(1, -33, 0)
							walkTo(p1, true)
						}

						talkToAssistantBrad()
						delay(1800, 200)
						currentStep = ""
					}
				}

				if (checkStep(questVarp, 4, 0, 0, 1, 0, 0, 0, 0, 0)) {
					println("Inspecting wonky tree...")
					allObjects.firstOrNull { it.name() == "Wonky tree" }?.interact("Inspect")
					delay(600, 100)
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 0, 0, 0, 0, 0)) {
					println("Going to town center to talk to Brad and Susi... $currentStep2")
					if (currentStep2 == "") {
						walkTo(localPlayer.tile.transform(21, +5, 0), true)
						delay(10000, 500)
						walkTo(localPlayer.tile.transform(21, -33, 0), true)
						delay(10000, 500)
						talkToAssistantBrad()
						delayUntil(5000) { isDialogOpen() }
						if (isDialogOpen())
							currentStep2 = "TALKTOSUSI"
					} else if (currentStep2 == "TALKTOSUSI") {
						talkToSusi()
					}
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 1, 1, 1, 1, 0)) {
					println("Knocking door 1 near Susi...")
					npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.tile?.let {
						walkTo(it.transform(13, 1, 0), true)
						delayUntil(10000) { it.transform(13, 1, 0) == localPlayer.tile }
					}
					allObjects.filter { it.name() == "Door" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
						?.interact("Knock")
					delayUntil(6000) { isDialogOpen() }
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 1, 2, 1, 1, 0)) {
					println("Knocking door 2 near Susi...")
					npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.tile?.let {
						walkTo(it.transform(3, 20, 0), true)
						delayUntil(10000) { it.transform(3, 20, 0) == localPlayer.tile }
					}
					allObjects.filter { it.name() == "Door" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
						?.interact("Knock")
					delayUntil(6000) { isDialogOpen() }
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 2, 2, 1, 1, 0)) {
					println("Knocking door 3 near Susi...")
					npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.tile?.let {
						walkTo(it.transform(-10, 16, 0), true)
						delayUntil(10000) { it.transform(-10, 16, 0) == localPlayer.tile }
					}
					allObjects.filter { it.name() == "Door" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
						?.interact("Knock")
					delayUntil(6000) { isDialogOpen() }
					return null
				}
				if (checkStep(questVarp, 4, 0, 1, 1, 2, 2, 1, 2, 0)) {
					println("Knocking door 4 near Susi...")
					npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.tile?.let {
						walkTo(it.transform(-15, -4, 0), true)
						delayUntil(10000) { it.transform(-15, -4, 0) == localPlayer.tile }
					}
					allObjects.filter { it.name() == "Door" }.minByOrNull { it.tile.getDistance(localPlayer.tile) }
						?.interact("Knock")
					delayUntil(6000) { isDialogOpen() }
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 2, 2, 2, 2, 0)) {
					talkToSusi()
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 4, 4, 4, 4, 0)) {
					if (allObjects.firstOrNull { it.name() == "Lamppost" && it.hasOption("Decorate!") }
							?.interact("Decorate!") == true) {
						delay(600, 100)
					} else if (allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Decorate!") }
							?.interact("Decorate!") == true) {
						delay(600, 100)
					}
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 4, 4, 5, 4, 0)) {
					allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Decorate!") }?.interact("Decorate!")
					delay(600, 100)
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 4, 4, 5, 5, 0)) {
					allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Decorate!") }?.interact("Decorate!")
					delay(600, 100)
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 4, 5, 5, 0)) {
					allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Decorate!") }?.interact("Decorate!")
					delay(600, 100)
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 0)) {
					if (currentStep4 == "") {
						talkToSusi()
						currentStep4 = "TALKTOTIMOTHY"
					} else if (currentStep4 == "TALKTOTIMOTHY") {
						talkToTimothy()
						currentStep4 = "TALKTOPOSTY"
					} else if (currentStep4 == "TALKTOPOSTY") {
						talkToPosty()
						currentStep4 = "DONE"
					}
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 1)) {
					npcs.values.firstOrNull { it.name() == "Elizabeth" }?.interact("Talk to")
					delay(600, 100)
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 2)) {
					npcs.values.firstOrNull { it.name() == "Peter" }?.interact("Talk to")
					delay(600, 100)
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 3)) {
					npcs.values.firstOrNull { it.name() == "Mozzie" }?.interact("Talk to")
					delay(600, 100)
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 4)) {
					npcs.values.firstOrNull { it.name() == "Neal" }?.interact("Talk to")
					delay(600, 100)
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 5)) {
					allObjects.firstOrNull { it.name() == "Santa's 'naughty or nice' list" }?.interact("Take")
					delay(600, 100)
					return null
				}

				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 6)) {
					if (npcs.values.firstOrNull { it.name() == "Posty" } == null) {
						println("Posty null")
						allObjects.firstOrNull { it.name() == "Wonky tree" }?.let {
							walkTo(it.tile, true)
							delayUntil(6000) { it.tile.getDistance(localPlayer.tile) < 5 }
						}
					}
					talkToPosty()
				}
			}

			65 -> {
				if (checkStep(questVarp, 4, 0, 1, 1, 5, 5, 5, 5, 7)) {
					if (currentStep5 == "") {
						talkToMarvinClaus()
						currentStep5 = "TALKTOHAL"
						return null
					} else if (currentStep5 == "TALKTOHAL") {
						talkToHal()
						currentStep5 = "GOUPHILL"
						return null
					} else if (currentStep5 == "GOUPHILL") {
						npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.tile?.let {
							walkTo(it.transform(-4, -17, 0), true)
							delayUntil{it.transform(-4, -17, 0).getDistance(localPlayer.tile) < 2}
						}

						currentStep5 = "THROWIMP"					
						return null
					}
					if (currentStep5 == "THROWIMP") {
						inventory.clickItem("Snow impling", "Launch")
						delayUntil { isDialogOpen() }
						delay(2000, 100)
						return null
					}
				}

				if (checkStep(questVarp, 4, 0, 4, 1, 5, 5, 5, 5, 7)) {
					allObjects.firstOrNull { it.name() == "Wonky tree" }?.interact("Admire")
					delay(600, 100)
					return null
				} else {
					if (currentStep5 == "THROWIMP") {
						inventory.clickItem("Snow impling", "Launch")
						delayUntil { isDialogOpen() }
						delay(2000, 100)
						return null
					}
				}
			}

			70 -> {
				if (checkStep(questVarp, 4, 0, 4, 1, 5, 5, 5, 5, 7)) {
					talkToMarvinClaus()
				}
			}

			75 -> {
				if (checkStep(questVarp, 4, 0, 4, 1, 5, 5, 5, 5, 7)) {
					if(allObjects.firstOrNull { it.name() == "Door" && it.hasOption("Knock")}?.interact("Knock") == true){
						delayUntil(6000) { isDialogOpen() }
						return null
					}
					
					talkToTrevor()
				}
			}

			100 -> {
				println("Quest Completed!")
			}
		}

		delay(200, 50)
		return null
	}

	suspend fun Script.talkToTrevor() {
		npcs.values.firstOrNull { it.name() == "Trevor" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToMarvinClaus() {
		npcs.values.firstOrNull { it.name() in SNOW_IMPS }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToHal() {
		npcs.values.firstOrNull { it.name() == "Hal the snow impling" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToTaylor() {
		npcs.values.firstOrNull { it.name() == "Taylor" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToTimothy() {
		npcs.values.firstOrNull { it.name() == "Assistant Timothy" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToSusi() {
		npcs.values.firstOrNull { it.name() == "Assistant Susi" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToPostiePete() {
		npcs.values.firstOrNull { it.name() == "Postie Pete" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToViolet() {
		npcs.values.firstOrNull { it.name() == "Violet" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToSnowImp() {
		npcs.values.firstOrNull { it.name() == "Snow imp" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToAssistantBrad() {
		npcs.values.firstOrNull { it.name() == "Assistant Brad" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	suspend fun Script.talkToPosty() {
		npcs.values.firstOrNull { it.name() == "Posty" }?.let {
			it.interact("Talk to")
			delay(700, 100)
		}
	}

	private val pathToStart = listOf(
		Tile.of(2879, 3442, 0),
		Tile.of(2881, 3425, 0),
		Tile.of(2856, 3456, 0)
	)

	private val moveToStart
		get() = traversal(VioletIsBlueToo(), { localPlayer.tile.getDistance(pathToStart.last()) < 5 }) {
			chebychevPath(
				localPlayer.tile,
				pathToStart,
				fallback = { useLodestone(Lodestone.TAVERLEY) },
				reached = { localPlayer.tile.getDistance(pathToStart.last()) < 5 }
			)
		}

	override suspend fun DebugScript.stateLoop() {}
}