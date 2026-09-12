package com.projectx.script.impl.trent.urncrafter

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.StringConfigItem
import com.projectx.script.api.MakeX
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.getXp
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.makeX
import com.projectx.script.api.makeXConfirm
import com.projectx.script.api.makeXReaction
import com.projectx.script.api.walkTo
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import com.projectx.util.formatElapsedTime
import com.projectx.util.gaussian
import com.projectx.util.getFormattedXpPerHour
import com.projectx.util.getUnitsPerHour

private const val MINE = 66876
private const val CLAY_ROCK = 113032
private const val WELL = 66664
private const val WHEEL = 66848
private const val OVEN = 66847
private const val MINE_EXIT = 67002

private const val CLAY = "Clay"
private const val SOFT_CLAY = "Soft clay"
private val ANY_UNFIRED = Regex(".* urn \\(unfired\\)$", RegexOption.IGNORE_CASE)
private val ANY_NO_RUNE = Regex(".* urn \\(no rune\\)$", RegexOption.IGNORE_CASE)

private val CRAFT_HUB = Tile.of(2885, 3500, 0)

private const val PRODUCE_TIMEOUT = 120_000L
private const val MINE_RECLICK_MS = 11_000L

enum class Phase(val label: String) {
    IDLE("Idle"), MINING("Mining clay"), EXITING("Leaving mine"),
    SOFTENING("Softening clay"), FORMING("Forming urns"),
    FIRING("Firing urns"), ADDING_RUNES("Adding runes")
}

@ScriptDescription(
    name = "Urn Crafter",
    version = "1.0.0",
    author = "Trent",
    description = "Mines clay, softens it, forms urns, fires them and adds runes. Auto-detects the target urn from your inventory.",
    category = ScriptCategory.CRAFTING
)
class UrnCrafter : Script() {
    private var startTime = 0L
    private var startingXp = 0
    private var family: String? = null
    private var lastEmptyCount = -1
    var urnsCrafted = 0
        private set
    var phase = Phase.IDLE
        private set
    var statusText = "Starting..."
        private set

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.CRAFTING)
        family = detectUrnFamily()
    }

    override suspend fun loop() {
        val fam = family ?: detectUrnFamily()?.also { family = it } ?: run {
            statusText = "No target urn in inventory - add one to start."
            delay(5529, 1195)
            return
        }
        if (lastEmptyCount < 0) lastEmptyCount = inventory.count(emptyRegex(fam))

        val softClay = inventory.count(SOFT_CLAY)
        val moldable = softClay + inventory.count(CLAY)

        when {
            inMine -> mineOrExit()
            inventory.hasItem(ANY_UNFIRED) -> fireUrns()
            inventory.hasItem(ANY_NO_RUNE) -> addRunes(fam)
            softClay >= URN_SOFT_CLAY_COST -> formUrns(fam)
            inventory.hasItem(CLAY) && moldable >= URN_SOFT_CLAY_COST -> softenClay()
            else -> enterMine()
        }
    }

    private suspend fun softenClay() {
        phase = Phase.SOFTENING
        statusText = "Softening clay"
        if (MakeX.inProgress) { delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(CLAY) || !MakeX.inProgress }; return }
        if (!MakeX.isOpen) {
            pause()
            if (!interactClosestObject(WELL, "Fill")) { goToHub(); return }
            if (!waitForMakeX()) return
        }
        makeXConfirm()
        delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(CLAY) }
    }

    private suspend fun formUrns(fam: String) {
        phase = Phase.FORMING
        statusText = "Forming $fam"
        if (MakeX.inProgress) { delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(SOFT_CLAY) || !MakeX.inProgress }; return }
        if (!MakeX.isOpen) {
            pause()
            if (!interactClosestObject(WHEEL, "Form")) { goToHub(); return }
            if (!waitForMakeX()) return
        }
        val target = "$fam (unfired)"
        val category = potteryWheelCategory(fam) ?: run {
            statusText = "No pottery-wheel category for $fam."
            delay(1692, 223)
            return
        }
        if (makeX({ it.equals(target, ignoreCase = true) }, { it.equals(category, ignoreCase = true) })) {
            delayUntil(PRODUCE_TIMEOUT) { inventory.count(SOFT_CLAY) < URN_SOFT_CLAY_COST }
        } else {
            statusText = "Couldn't select $target (category $category)."
            delay(1692, 223)
        }
    }

    private suspend fun fireUrns() {
        phase = Phase.FIRING
        statusText = "Firing urns"
        if (MakeX.inProgress) { delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(ANY_UNFIRED) || !MakeX.inProgress }; return }
        if (!MakeX.isOpen) {
            pause()
            if (!interactClosestObject(OVEN, "Use")) { goToHub(); return }
            if (!waitForMakeX()) return
        }
        makeXConfirm()
        delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(ANY_UNFIRED) }
    }

    private suspend fun addRunes(fam: String) {
        phase = Phase.ADDING_RUNES
        statusText = "Adding runes"
        if (MakeX.inProgress) { delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(ANY_NO_RUNE) || !MakeX.inProgress }; return }
        if (!MakeX.isOpen) {
            val noRune = inventory.getItem(noRuneRegex(fam)) ?: return
            val action = addRuneAction(noRune) ?: run {
                statusText = "No 'Add rune' action on ${noRune.name}."; stop(); return
            }
            val rune = runeNameFromAction(action)
            if (!inventory.hasItem(rune)) { statusText = "Out of $rune - stopping."; stop(); return }
            statusText = "Adding $rune to urns"
            makeXReaction()
            if (!noRune.click(action)) return
            if (!waitForMakeX()) return
        }
        makeXConfirm()
        delayUntil(PRODUCE_TIMEOUT) { !inventory.hasItem(ANY_NO_RUNE) }
        countFinished(fam)
    }

    private suspend fun enterMine() {
        phase = Phase.MINING
        statusText = "Entering mine"
        pause()
        if (interactClosestObject(MINE, "Enter")) delayUntil(8000) { inMine } else goToHub()
    }

    private suspend fun mineOrExit() {
        if (inventory.isFull) { exitMine(); return }
        phase = Phase.MINING
        statusText = "Mining clay"
        pause()
        if (interactClosestObject(CLAY_ROCK, "Mine", range = 25)) {
            delayUntil(gaussian(MINE_RECLICK_MS, MINE_RECLICK_MS/3)) { inventory.isFull }
        } else {
            statusText = "No clay rock nearby."
            delay(1500)
        }
    }

    private suspend fun exitMine() {
        phase = Phase.EXITING
        statusText = "Leaving mine"
        pause()
        val exit = findClosestObject(MINE_EXIT, 30) ?: run {
            statusText = "Mine exit ($MINE_EXIT) not found."
            delay(4459, 1110)
            return
        }
        if (exit.interact(0)) delayUntil(gaussian(8592L, 4210L)) { !inMine }
    }

    private suspend fun waitForMakeX(): Boolean {
        delayUntil(gaussian(4592L, 2210L)) { MakeX.isOpen }
        return MakeX.isOpen
    }

    private fun countFinished(fam: String) {
        val now = inventory.count(emptyRegex(fam))
        if (now > lastEmptyCount) urnsCrafted += now - lastEmptyCount
        lastEmptyCount = now
    }

    private fun goToHub() {
        if (!localPlayer.tile.withinDistance(CRAFT_HUB, 8)) walkTo(CRAFT_HUB.randomize(2), true)
    }

    private val inMine get() = localPlayer.tile.y > 4000

    private suspend fun pause() {
        delay(420, 240)
    }

    override fun render() {
        ImGuiDsl.window("Urn Crafter") {
            image(graphicTexture(GraphicIds.CRAFTING), 32f, 32f)
            text("Urn: ${family ?: "detecting..."}")
            text("Phase: ${phase.label}")
            text("Status: $statusText")
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.CRAFTING), startTime)}")
            text("Urns/hr: ${getUnitsPerHour(urnsCrafted, startTime)}")
            text("Urns made: $urnsCrafted")
            xpProgressBar(Skill.CRAFTING)
        }
    }
}
